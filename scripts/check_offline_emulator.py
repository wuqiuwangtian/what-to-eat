#!/usr/bin/env python3
"""仅在装有开发及测试APK的专用API29/API35模拟器验证离线交互和系统文档导出，最后恢复联网设置。"""
import argparse
from pathlib import Path
import subprocess, time, re, json, xml.etree.ElementTree as E
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='adb')
parser.add_argument('--serial', required=True)
parser.add_argument('--output', type=Path, default=Path('artifacts/offline-validation'))
args = parser.parse_args()
b = [args.adb, '-s', args.serial]
out = args.output
out.mkdir(parents=True, exist_ok=True)
if (out / 'summary.json').exists():
    parser.error('Choose a fresh output directory')

def call(*args, timeout=25, binary=False):
    r = subprocess.run(b + list(args), capture_output=True, text=not binary, timeout=timeout, check=True)
    return r.stdout if binary else r.stdout.strip()

def shell(*a):
    return call('shell', *a)

def tree():
    shell('uiautomator', 'dump', '/data/local/tmp/what-to-eat-offline.xml')
    return E.fromstring(shell('cat', '/data/local/tmp/what-to-eat-offline.xml'))

def find(pred):
    until = time.monotonic() + 15
    while time.monotonic() < until:
        n = next((n for n in tree().iter('node') if pred(n)), None)
        if n is not None:
            return n
        time.sleep(0.3)
    raise RuntimeError('Required UI control not found')

def tap(node):
    x1, y1, x2, y2 = map(int, re.findall('\\d+', node.get('bounds')))
    shell('input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
sdk = shell('getprop', 'ro.build.version.sdk')
if shell('getprop', 'ro.kernel.qemu') != '1' or sdk not in ['29', '35']:
    parser.error('Use a dedicated API29/API35 emulator; physical devices are rejected before network changes')
old = {k: shell('settings', 'get', 'global', k) for k in ['wifi_on', 'mobile_data']}
if set(old.values()) - {'0', '1'}:
    raise RuntimeError('Unexpected initial network settings; do not mutate')
report = {'sdk': int(sdk), 'original_network_settings': old, 'result': 'not completed'}
try:
    shell('svc', 'wifi', 'disable')
    shell('svc', 'data', 'disable')
    time.sleep(2)
    connectivity = shell('dumpsys', 'connectivity')
    (out / 'connectivity-private.txt').write_text(connectivity)
    network = re.search('^Active default network:\\s*(.*)$', connectivity, re.M)
    if not network or network[1].strip().lower() not in ['none', 'null']:
        raise RuntimeError('Cannot establish an offline environment')
    report['active_default_network_during_test'] = network[1].strip()
    selected = ','.join(['org.whattoeat.app.SmokeTest', 'org.whattoeat.app.InteractionTest#systemBackReturnsHomeAndKeepsTheMealDraft', 'org.whattoeat.app.InteractionTest#recreatingActivityKeepsTheUsersCorrectionToDetectedFoods', 'org.whattoeat.app.InteractionTest#comparisonDoesNotCreatePreferenceUntilTheUserChooses'])
    text = call('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', selected, 'org.whattoeat.app.test/androidx.test.runner.AndroidJUnitRunner', timeout=180)
    (out / 'instrumentation.txt').write_text(text)
    if 'OK (5 tests)' not in text or 'FAILURES!!!' in text:
        raise RuntimeError('Offline interaction tests failed')
    report['interaction_tests'] = 'OK (5 tests)'
    print('Offline local interactions passed.', flush=True)
    shell('am', 'force-stop', 'org.whattoeat.app')
    shell('am', 'start', '-W', '-n', 'org.whattoeat.app/.MainActivity')
    tap(find(lambda n: n.get('text') == '记录' and n.get('package') == 'org.whattoeat.app'))
    tap(find(lambda n: n.get('text') == '导出记录与设置'))
    edit = find(lambda n: n.get('class') == 'android.widget.EditText')
    tap(edit)
    shell('input', 'keyevent', '123', *['67'] * 80)
    filename = 'what-to-eat-offline-validation-' + str(int(time.time())) + '.json'
    shell('input', 'text', filename)
    tap(find(lambda n: n.get('resource-id', '').endswith('/button_save') or (n.get('text') in ['SAVE', '保存'] and n.get('clickable') == 'true')))
    time.sleep(1)
    raw = call('exec-out', 'cat', '/sdcard/Download/' + filename, binary=True)
    data = json.loads(raw)
    if data.get('formatVersion') not in {1, 2} or not data.get('events') or 'preferences' not in data or {'apiKey', 'model'} & data.keys():
        raise RuntimeError('Export content did not match contract')
    if not any((e.get('type') == 'PLANNED' for e in data['events'])):
        raise RuntimeError('Planned event missing')
    (out / 'export.json').write_bytes(raw)
    report['export'] = {'path': 'Android system document picker to Downloads; unique synthetic-data file', 'format_version': 1, 'event_count': len(data['events']), 'planned_event_present': True, 'model_configuration_present': False}
    shell('rm', '-f', '/sdcard/Download/' + filename)
    report['result'] = 'passed'
    print('Offline system document export passed.', flush=True)
finally:
    shell('svc', 'wifi', 'enable' if old['wifi_on'] == '1' else 'disable')
    shell('svc', 'data', 'enable' if old['mobile_data'] == '1' else 'disable')
    time.sleep(1)
    now = {k: shell('settings', 'get', 'global', k) for k in old}
    report['restored_network_settings'] = now
    report['network_settings_restored'] = now == old
    (out / 'summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    shell('rm', '-f', '/data/local/tmp/what-to-eat-offline.xml')
    if now != old:
        raise RuntimeError('Network settings not restored')
    print('Original emulator network settings restored.', flush=True)
