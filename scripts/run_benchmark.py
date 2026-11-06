#!/usr/bin/env python3
"""在显式指定的真机运行隔离性能包；只清空该性能包的合成记录，不操作普通应用。"""
import argparse
from pathlib import Path
import shutil
import subprocess
import sys

TARGET_APK = Path('app/build/outputs/apk/benchmark/app-benchmark.apk')
RUNNER_APK = Path('benchmark/build/outputs/apk/benchmark/benchmark-benchmark.apk')
REMOTE = '/sdcard/Android/media/org.whattoeat.benchmark/org.whattoeat.benchmark-benchmarkData.json'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--iterations', type=int, default=20)
    parser.add_argument('--output', type=Path, default=Path('artifacts/macrobenchmark'))
    args = parser.parse_args()
    if not 20 <= args.iterations <= 50:
        parser.error('Use 20–50 iterations for acceptance measurements')
    if not TARGET_APK.is_file() or not RUNNER_APK.is_file():
        parser.error('Build with ./gradlew -Pbenchmarks :app:assembleBenchmark :benchmark:assembleBenchmark first')
    args.output.mkdir(parents=True, exist_ok=True)
    if any((args.output / name).exists() for name in ['run.txt', 'raw.json', 'target.apk', 'runner.apk']):
        parser.error('Choose a fresh output directory to preserve earlier evidence')

    def host_path(path):
        value = str(path.resolve())
        if sys.platform == 'linux' and args.adb.lower().endswith('.exe'):
            return subprocess.check_output(['wslpath', '-w', value], text=True).strip()
        return value

    def adb(*parts, timeout=30):
        result = subprocess.run([args.adb, '-s', args.serial, *parts], capture_output=True,
                                text=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(result.stderr.strip() or result.stdout.strip() or 'ADB command failed')
        return result.stdout.strip()

    if adb('get-state') != 'device':
        raise SystemExit('Selected device is not ready')
    # 保存实际安装的二进制；后续重建不会改变这轮结果对应的摘要。
    for source, name in [(TARGET_APK, 'target.apk'), (RUNNER_APK, 'runner.apk')]:
        saved = args.output / name
        shutil.copyfile(source, saved)
        result = adb('install', '-r', host_path(saved), timeout=120)
        if 'Success' not in result:
            raise SystemExit('Installation failed; no benchmark started')
    adb('shell', 'rm', '-f', REMOTE)
    print('Running three scenarios; keep the phone unlocked and avoid manual interaction.', flush=True)
    with (args.output / 'run.txt').open('w') as log:
        result = subprocess.run([args.adb, '-s', args.serial, 'shell', 'am', 'instrument', '-w', '-r',
                                 '-e', 'class', 'org.whattoeat.benchmark.MealBenchmark',
                                 '-e', 'iterations', str(args.iterations),
                                 'org.whattoeat.benchmark/androidx.test.runner.AndroidJUnitRunner'],
                                stdout=log, stderr=subprocess.STDOUT, text=True, timeout=2400)
    output = (args.output / 'run.txt').read_text(errors='replace')
    if result.returncode or 'FAILURES!!!' in output or 'INSTRUMENTATION_FAILED' in output or 'OK (3 tests)' not in output:
        raise SystemExit('Benchmark failed; inspect the private run.txt; no summary generated')
    adb('pull', REMOTE, host_path(args.output / 'raw.json'), timeout=120)
    subprocess.run([sys.executable, 'scripts/summarize_benchmark.py', str(args.output / 'raw.json'),
                    '--target-apk', str(args.output / 'target.apk'), '--runner-apk', str(args.output / 'runner.apk'),
                    '--output', str(args.output / 'summary.json')], check=True)
    print(f'Validated summary: {args.output / "summary.json"}; raw data and APKs should remain private.')


if __name__ == '__main__':
    main()
