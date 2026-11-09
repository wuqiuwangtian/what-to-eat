#!/usr/bin/env python3
"""仅在专用模拟器清空本应用，用临时密钥验证非debug发行APK初装、计划持久化及同证书覆盖。"""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
from prepare_preview import apk_metadata, sdk_tool, sha256

PACKAGE = 'org.whattoeat.app'
XML = '/data/local/tmp/what-to-eat-release-check.xml'



def planned_cards(root):
    parents = {child: parent for parent in root.iter() for child in parent}
    return [[n.get('text') for n in parents[node].iter('node') if n.get('text')]
            for node in root.iter('node')
            if node.get('package') == PACKAGE and node.get('text', '').startswith('打算吃 · ')]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', required=True, type=Path, help='Unsigned release APK')
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--allow-reset', action='store_true', help='Allow resetting this app in a dedicated emulator')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if not args.allow_reset or not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('A dedicated emulator and explicit --allow-reset are required')
    prefix = [args.adb, '-s', args.serial]

    def adb(*parts):
        result = subprocess.run(prefix + list(parts), capture_output=True, text=True, timeout=60)
        if result.returncode:
            raise RuntimeError('ADB operation failed; no successful release check recorded')
        return result.stdout.strip()

    if adb('shell', 'getprop', 'ro.kernel.qemu') != '1':
        parser.error('Physical devices are not allowed')
    metadata = subprocess.check_output([sdk_tool('aapt2'), 'dump', 'badging', str(args.apk)], text=True)
    if not re.search(r"package: name='org.whattoeat.app' ", metadata) or 'application-debuggable' in metadata:
        parser.error('An ordinary non-debug unsigned release APK is required')
    args.output.mkdir(parents=True, exist_ok=False)
    output = args.output.resolve()

    def window():
        adb('shell', 'uiautomator', 'dump', XML)
        return ET.fromstring(adb('shell', 'cat', XML))

    def find(label, prefix_match=False):
        for _ in range(15):
            for node in window().iter('node'):
                matches = node.get('text', '').startswith(label) if prefix_match else node.get('text') == label
                if node.get('package') == PACKAGE and matches and node.get('enabled') == 'true':
                    return node
            time.sleep(.2)
        raise RuntimeError('Expected release UI unavailable')

    def click(label):
        node = find(label)
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds', '')))
        adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

    try:
        with tempfile.TemporaryDirectory(prefix='what-to-eat-test-key-') as temporary:
            store = Path(temporary) / 'test.jks'
            env = dict(os.environ, WHAT_TO_EAT_TEST_PASSWORD=secrets.token_hex(24))
            keytool = str(Path(os.environ['JAVA_HOME']) / 'bin/keytool')
            subprocess.run([keytool, '-genkeypair', '-keystore', str(store), '-storepass:env', 'WHAT_TO_EAT_TEST_PASSWORD',
                            '-keypass:env', 'WHAT_TO_EAT_TEST_PASSWORD', '-alias', 'test-only', '-keyalg', 'RSA',
                            '-keysize', '2048', '-validity', '2', '-dname', 'CN=Disposable emulator test only'],
                           env=env, check=True, capture_output=True)
            signed = output / 'test-key-release.apk'
            subprocess.run([sdk_tool('apksigner'), 'sign', '--ks', str(store), '--ks-key-alias', 'test-only',
                            '--ks-pass', 'env:WHAT_TO_EAT_TEST_PASSWORD', '--key-pass', 'env:WHAT_TO_EAT_TEST_PASSWORD',
                            '--out', str(signed), str(args.apk)], env=env, check=True, capture_output=True)
            actual = apk_metadata(signed)
            if actual['debuggable']:
                raise RuntimeError('Release is unexpectedly debuggable')
            installed = subprocess.run(prefix + ['shell', 'pm', 'path', PACKAGE], capture_output=True, text=True)
            if installed.stdout.startswith('package:') and 'Success' not in adb('uninstall', PACKAGE):
                raise RuntimeError('Cannot reset dedicated emulator installation')
            if 'Success' not in adb('install', str(signed)):
                raise RuntimeError('Release install failed')
            adb('shell', 'am', 'start', '-W', '-n', PACKAGE+'/.MainActivity')
            guide_skipped = False
            for _ in range(10):
                nodes = list(window().iter('node'))
                if any(n.get('package') == PACKAGE and n.get('resource-id') == 'gettingStarted' for n in nodes):
                    click('跳过'); guide_skipped = True; break
                if any(n.get('package') == PACKAGE and n.get('text') == '吃什么？' for n in nodes):
                    break
                time.sleep(.2)
            click('吃什么？'); click('就它了'); click('记录')
            find('打算吃 · ', prefix_match=True)
            # 非debug包没有run-as：通过真实记录界面检查覆盖后仍存在计划。
            before = planned_cards(window())
            if len(before) != 1:
                raise RuntimeError('Expected exactly one synthetic planned meal')
            (output / 'before-private.json').write_text(json.dumps(before, ensure_ascii=False))
            adb('shell', 'am', 'force-stop', PACKAGE)
            if 'Success' not in adb('install', '-r', str(signed)):
                raise RuntimeError('Same-certificate update failed')
            adb('shell', 'am', 'start', '-W', '-n', PACKAGE+'/.MainActivity')
            click('记录'); find('打算吃 · ', prefix_match=True)
            after = planned_cards(window())
            (output / 'after-private.json').write_text(json.dumps(after, ensure_ascii=False))
            if before != after:
                raise RuntimeError('History content changed after reinstall')
            report = {'result': 'passed', 'sdk': adb('shell', 'getprop', 'ro.build.version.sdk'), 'apk': actual,
                      'unsigned_apk_sha256': sha256(args.apk), 'test_signed_apk_sha256': sha256(signed),
                      'fresh_install': True, 'first_guide_skipped_when_present': guide_skipped, 'same_certificate_reinstall': True, 'planned_history_unchanged': True,
                      'temporary_key_deleted': True, 'maintainer_key_tested': False,
                      'different_version_upgrade_tested': False, 'physical_device_modified': False}
    finally:
        adb('shell', 'rm', '-f', XML)
        # 保留合成测试应用；临时私钥由TemporaryDirectory销毁，不供分发。
    (output / 'summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print('Non-debug release install and same-certificate update passed on dedicated emulator.')


if __name__ == '__main__':
    main()
