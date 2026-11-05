#!/usr/bin/env python3
"""采集 am start -W 的显示耗时代理值；会停止指定测试设备上的应用，不删除数据。"""
import argparse
import json
import math
from pathlib import Path
import re
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='adb')
parser.add_argument('--serial', required=True, help='必须显式指定专用测试设备')
parser.add_argument('--samples', type=int, default=20)
parser.add_argument('--output', type=Path, default=Path('artifacts/startup.json'))
args = parser.parse_args()
if not 5 <= args.samples <= 100:
    parser.error('--samples must be between 5 and 100')

def adb(*parts):
    return subprocess.check_output([args.adb, '-s', args.serial, *parts], text=True, timeout=30).strip()

package = 'org.whattoeat.app'
if not adb('shell', 'pm', 'path', package).startswith('package:'):
    raise SystemExit('Install the development APK on the selected test device first.')
values = []
rejected = []
launch_states = []
for attempt in range(args.samples * 3):
    adb('shell', 'am', 'force-stop', package)
    raw = adb('shell', 'am', 'start', '-W', '-n', package + '/.MainActivity')
    match = re.search(r'^TotalTime:\s*(\d+)', raw, re.M)
    state = re.search(r'^LaunchState:\s*(\w+)', raw, re.M)
    activity = re.search(r'^Activity:\s*(\S+)', raw, re.M)
    accepted = (match is not None and state is not None and state[1] == 'COLD'
                and activity is not None and activity[1] == package + '/.MainActivity'
                and 'Status: ok' in raw)
    if accepted:
        values.append(int(match[1]))
        launch_states.append(state[1])
        print(f'{len(values)}/{args.samples}: {values[-1]} ms', flush=True)
    else:
        rejected.append({'attempt': attempt + 1, 'state': state[1] if state else None,
                         'activity': activity[1] if activity else None})
        print('Rejected a non-target or non-cold launch measurement', flush=True)
    # Android 10 有时先返回桌面的显示耗时。先等目标真正恢复，再开始下一次冷启动。
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        activities = adb('shell', 'dumpsys', 'activity', 'activities')
        if re.search(r'(?:mResumedActivity:|topResumedActivity=).*org\.whattoeat\.app/\.MainActivity', activities):
            break
        time.sleep(.1)
    else:
        raise SystemExit('Target Activity did not resume; no success report written.')
    if len(values) == args.samples:
        break
if len(values) != args.samples:
    raise SystemExit('Not enough valid cold launches; no success report written.')

def percentile(p):
    return sorted(values)[math.ceil(len(values) * p) - 1]

result = {
    'measured_at_utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
    'method': 'am force-stop then am start -W; TotalTime; nearest-rank percentiles',
    'measurement': 'Android-reported LaunchState=COLD Activity TotalTime; OS caches retained',
    'limitation': 'Activity display proxy only; not time-to-interactive or recommendation/switch latency acceptance.',
    'build': 'development APK; debug instrumentation not attached',
    'device': {'serial': args.serial, 'model': adb('shell', 'getprop', 'ro.product.model'),
               'sdk': adb('shell', 'getprop', 'ro.build.version.sdk'),
               'fingerprint': adb('shell', 'getprop', 'ro.build.fingerprint'),
               'font_scale': adb('shell', 'settings', 'get', 'system', 'font_scale')},
    'samples_ms': values, 'launch_states': launch_states, 'rejected_attempts': rejected, 'p50_ms': percentile(.5), 'p95_ms': percentile(.95),
}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
print(f'Wrote {args.output}')
