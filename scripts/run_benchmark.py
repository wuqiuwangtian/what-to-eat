#!/usr/bin/env python3
"""在显式指定的真机运行隔离性能包；只清空该性能包的合成记录，不操作普通应用。"""
import argparse
import hashlib
import re
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
    parser.add_argument('--adb-port', type=int, help='独立ADB服务端口；省略时使用ADB默认端口')
    parser.add_argument('--iterations', type=int, default=20)
    parser.add_argument('--smoke', action='store_true', help='只跑三轮链路预检，不生成性能验收摘要')
    parser.add_argument('--output', type=Path, default=Path('artifacts/macrobenchmark'))
    args = parser.parse_args()
    if args.adb_port is not None and not 1 <= args.adb_port <= 65535:
        parser.error('--adb-port must be between 1 and 65535')
    adb_prefix = [args.adb] + (['-P', str(args.adb_port)] if args.adb_port else []) + ['-s', args.serial]
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

    def adb(*parts, timeout=30, allow_missing=False):
        result = subprocess.run([*adb_prefix, *parts], capture_output=True,
                                text=True, timeout=timeout)
        if result.returncode and not (allow_missing and not result.stdout.strip() and not result.stderr.strip()):
            raise RuntimeError(result.stderr.strip() or result.stdout.strip() or 'ADB command failed')
        return result.stdout.strip()

    if adb('get-state') != 'device':
        raise SystemExit('Selected device is not ready')
    # 保存实际安装的二进制；后续重建不会改变这轮结果对应的摘要。
    for source, name, package in [(TARGET_APK, 'target.apk', 'org.whattoeat.app.benchmark'),
                                  (RUNNER_APK, 'runner.apk', 'org.whattoeat.benchmark')]:
        saved = args.output / name
        shutil.copyfile(source, saved)
        installed_hash = None
        paths = adb('shell', 'pm', 'path', package, allow_missing=True).splitlines()
        if len(paths) == 1 and paths[0].startswith('package:'):
            remote = paths[0].removeprefix('package:')
            if re.fullmatch(r'/[A-Za-z0-9/._~+=-]+', remote):
                try:
                    digest = adb('shell', 'sha256sum', remote).split()[0]
                    if re.fullmatch(r'[0-9a-f]{64}', digest):
                        installed_hash = digest
                except (RuntimeError, IndexError):
                    pass  # 无法校验时正常安装，不假设已装的是当前版本。
        if installed_hash == hashlib.sha256(saved.read_bytes()).hexdigest():
            print(f'Already installed exact APK: {package}', flush=True)
            continue
        print(f'Installing {package}; handle any device installation prompt.', flush=True)
        result = adb('install', '-r', host_path(saved), timeout=120)
        if 'Success' not in result:
            raise SystemExit('Installation failed; no benchmark started')
    adb('shell', 'rm', '-f', REMOTE)
    iterations = 3 if args.smoke else args.iterations
    print(f'Running three scenarios, {iterations} iterations each; keep the phone unlocked and avoid manual interaction.', flush=True)
    with (args.output / 'run.txt').open('w') as log:
        result = subprocess.run([*adb_prefix, 'shell', 'am', 'instrument', '-w', '-r',
                                 '-e', 'class', 'org.whattoeat.benchmark.MealBenchmark',
                                 '-e', 'iterations', str(iterations),
                                 'org.whattoeat.benchmark/androidx.test.runner.AndroidJUnitRunner'],
                                stdout=log, stderr=subprocess.STDOUT, text=True, timeout=2400)
    output = (args.output / 'run.txt').read_text(errors='replace')
    if result.returncode or 'FAILURES!!!' in output or 'INSTRUMENTATION_FAILED' in output or 'OK (3 tests)' not in output:
        raise SystemExit('Benchmark failed; inspect the private run.txt; no summary generated')
    adb('pull', REMOTE, host_path(args.output / 'raw.json'), timeout=120)
    if args.smoke:
        print('Three-iteration smoke check passed; no acceptance summary generated.', flush=True)
        return
    subprocess.run([sys.executable, 'scripts/summarize_benchmark.py', str(args.output / 'raw.json'),
                    '--target-apk', str(args.output / 'target.apk'), '--runner-apk', str(args.output / 'runner.apk'),
                    '--output', str(args.output / 'summary.json')], check=True)
    print(f'Validated summary: {args.output / "summary.json"}; raw data and APKs should remain private.')


if __name__ == '__main__':
    main()
