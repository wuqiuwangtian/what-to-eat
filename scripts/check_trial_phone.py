#!/usr/bin/env python3
"""仅操作隔离的org.whattoeat.app.benchmark，模拟三会话并经系统文件选择器验证导出；不是真人试用。"""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
from analyze_trial import analyze

PACKAGE = 'org.whattoeat.app.benchmark'
WINDOW_XML = '/data/local/tmp/what-to-eat-trial-window.xml'


def node_bounds(node):
    return tuple(map(int, re.findall(r'-?\d+', node.get('bounds', ''))))


def visible_region(node, parents, width, height):
    """使用真实窗口和滚动父级交集，不能把固定导航栏高度当作所有窗口的边界。"""
    rect = node_bounds(node)
    clip = [0, 0, width, height]
    if len(rect) != 4:
        return None, clip
    x1, y1, x2, y2 = rect
    ancestor = parents.get(node)
    enabled = node.get('enabled') == 'true'
    while ancestor is not None:
        enabled = enabled and ancestor.get('enabled', 'true') == 'true'
        if ancestor.get('scrollable') == 'true':
            ar = node_bounds(ancestor)
            clip = [max(clip[0], ar[0]), max(clip[1], ar[1]), min(clip[2], ar[2]), min(clip[3], ar[3])]
        ancestor = parents.get(ancestor)
    visible = [max(x1, clip[0]), max(y1, clip[1]), min(x2, clip[2]), min(y2, clip[3])]
    if (x2 > x1 and y2 > y1 and enabled and visible[2] > visible[0] and visible[3] > visible[1]
            and (visible[2]-visible[0])*(visible[3]-visible[1]) >= .5*(x2-x1)*(y2-y1)):
        return visible, clip
    return None, clip


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--adb-port', type=int)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, default=Path('artifacts/phone-trial-validation'))
    args = parser.parse_args()
    if args.adb_port is not None and not 1 <= args.adb_port <= 65535:
        parser.error('Invalid ADB server port')
    if (args.output / 'summary.json').exists():
        parser.error('Choose a fresh output directory')
    args.output.mkdir(parents=True, exist_ok=True)
    prefix = [args.adb] + (['-P', str(args.adb_port)] if args.adb_port else []) + ['-s', args.serial]

    def adb(*parts, binary=False, timeout=30):
        result = subprocess.run(prefix + list(parts), capture_output=True, text=not binary, timeout=timeout)
        if result.returncode:
            raise RuntimeError('ADB command failed; no success report generated')
        return result.stdout if binary else result.stdout.strip()

    def shell(*parts):
        return adb('shell', *parts)

    def tree():
        shell('uiautomator', 'dump', WINDOW_XML)
        return ET.fromstring(shell('cat', WINDOW_XML))

    sizes = re.findall(r'(\d+)x(\d+)', shell('wm', 'size'))
    if not sizes or not shell('pm', 'path', PACKAGE).startswith('package:'):
        raise RuntimeError('Install the isolated benchmark APK first')
    physical_w, physical_h = map(int, sizes[-1])

    picker = shell('cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.CREATE_DOCUMENT', '-c', 'android.intent.category.OPENABLE', '-t', 'application/json').splitlines()[-1].split('/')[0]
    picker_packages = {picker, 'com.android.documentsui', 'com.google.android.documentsui'}

    def find(predicate, scroll=False, attempts=12):
        for _ in range(attempts):
            root = tree()
            width, height = (physical_h, physical_w) if int(root.get('rotation', '0')) % 2 else (physical_w, physical_h)
            parents = {child: parent for parent in root.iter() for child in parent}
            nodes = [n for n in root.iter('node') if predicate(n)]
            scroll_direction = 'up' if _ < attempts // 2 else 'down'
            for node in nodes:
                rect = node_bounds(node)
                visible, clip = visible_region(node, parents, width, height)
                if visible is not None:
                    node.set('bounds', f'[{visible[0]},{visible[1]}][{visible[2]},{visible[3]}]')
                    return node
                if len(rect) == 4 and rect[3] > rect[1]:
                    scroll_direction = 'down' if (rect[1]+rect[3])/2 < clip[1] else 'up'
            if scroll:
                if not any(n.get('package') == PACKAGE for n in root.iter('node')):
                    raise RuntimeError('Unexpected foreground; refuse to scroll another application')
                start, end = (.8,.3) if scroll_direction == 'up' else (.3,.8)
                shell('input', 'swipe', str(width//2), str(int(height*start)), str(width//2), str(int(height*end)), '250')
            else:
                time.sleep(.2)
        raise RuntimeError('Required test control unavailable; no success report generated')

    def tap(node):
        x1,y1,x2,y2 = node_bounds(node)
        shell('input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

    def text(label, scroll=False):
        return find(lambda n: n.get('package') == PACKAGE and n.get('text') == label, scroll)

    def click(label, scroll=False):
        tap(text(label, scroll))

    def recommend(sequence):
        tap(find(lambda n: n.get('package') == PACKAGE and n.get('resource-id') == 'recommendButton'))
        find(lambda n: n.get('package') == PACKAGE and n.get('resource-id') == f'recommendationResult-{sequence}')

    def assert_blind():
        forbidden = ['RANDOM', 'SIMPLE', 'PERSONALIZED', '受约束随机', '简单历史规则', '完整轻量策略', '决策详情', '验证工具']
        for node in tree().iter('node'):
            if node.get('package') == PACKAGE and any(word in node.get('text', '') for word in forbidden):
                raise RuntimeError('A method label was exposed in trial mode')

    filename = 'what-to-eat-validation-' + uuid.uuid4().hex + '.json'
    report = None
    try:
        # 这是唯一接受预置的测试变体；普通应用既不支持此预置，也不作为任何点击目标。
        result = shell('am', 'start', '-W', '-f', '0x10008000', '-n', PACKAGE+'/org.whattoeat.app.MainActivity', '--ez', 'benchmark_seed', 'true')
        if 'Error' in result:
            raise RuntimeError('Fixture launch failed')
        text('吃什么？')
        # 预置意图只用于准备，后续活动重建和系统选择器往返不得再次清空合成会话。
        shell('am', 'start', '-W', '-f', '0x10008000', '-n', PACKAGE+'/org.whattoeat.app.MainActivity', '--ez', 'benchmark_seed', 'false')
        text('吃什么？')
        click('设置'); click('本地试用 · 未参加', scroll=True)
        click('我愿意参加本地试用', scroll=True); click('加入试用', scroll=True)
        text('本地试用 · 已参加'); assert_blind()
        click('‹ 吃什么')
        recommend(1); click('为什么推荐')
        text('候选符合当前原料排除和用餐场景设置。'); assert_blind()
        click('收起依据', scroll=True)
        recommend(2); click('就它了'); text('吃什么？')
        recommend(3); click('结束本次', scroll=True); text('吃什么？')
        recommend(4); click('就它了'); text('吃什么？')
        print('Three synthetic sessions completed; exporting through system document picker.', flush=True)
        click('设置')
        # 面板展开状态由页面保存；查找导出时滚动，不再次点击标题以免把面板收起。
        click('导出试用数据', scroll=True)
        edit = find(lambda n: n.get('package') in picker_packages and n.get('class') == 'android.widget.EditText')
        tap(edit); shell('input', 'keyevent', '123', *['67']*80)
        shell('input', 'text', filename)
        tap(find(lambda n: n.get('package') in picker_packages and (n.get('resource-id', '').endswith('/button_save') or n.get('text') in ['保存', 'SAVE'])))
        time.sleep(1)
        content = adb('exec-out', 'cat', '/sdcard/Download/'+filename, binary=True)
        data = json.loads(content)
        analysis = analyze([data])
        sessions = data['sessions']
        if len(sessions) != 3 or Counter(s['strategy'] for s in sessions) != Counter({'RANDOM':1,'SIMPLE':1,'PERSONALIZED':1}):
            raise RuntimeError('Unexpected allocation sequence')
        if [s['endReason'] for s in sessions] != ['ADOPTED','MANUAL','ADOPTED'] or [len(s['presentations']) for s in sessions] != [2,1,1]:
            raise RuntimeError('Unexpected session or presentation outcomes')
        if [s['selectedIndex'] for s in sessions] != [1,None,0] or any(s['confirmedEaten'] is not None for s in sessions):
            raise RuntimeError('Choice or eating feedback was inferred incorrectly')
        (args.output/'export.json').write_bytes(content)
        (args.output/'analysis.json').write_text(json.dumps(analysis,ensure_ascii=False,indent=2)+'\n')
        report = {'result':'passed','package':PACKAGE,'app_version':data['appVersion'],'synthetic_sessions':3,
                  'allocation_counts':dict(Counter(s['strategy'] for s in sessions)), 'presentations_per_session':[2,1,1],
                  'end_reasons':['ADOPTED','MANUAL','ADOPTED'],'confirmed_eaten_inferred':False,
                  'export_via_system_document_picker':True,'real_human_trial':False,'ordinary_app_modified':False}
    except Exception:
        try:
            (args.output/'failure-window-private.xml').write_text(ET.tostring(tree(),encoding='unicode'))
        except Exception:
            pass
        raise
    finally:
        shell('rm','-f',WINDOW_XML)
        shell('rm','-f','/sdcard/Download/'+filename)
        shell('am','force-stop',PACKAGE)
    if report is not None:
        (args.output/'summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        print('Isolated phone trial-flow validation passed; no real participant enrolled.',flush=True)


if __name__ == '__main__':
    main()
