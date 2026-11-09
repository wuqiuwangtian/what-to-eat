#!/usr/bin/env python3
"""从已提交源码的干净检出构建开发预览及人工审查材料；不发布、不生成长期密钥。"""
import argparse
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sdk_tool(name):
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        raise ValueError('Set ANDROID_HOME to the Android SDK')
    tool = Path(sdk) / 'build-tools/35.0.0' / name
    if not tool.is_file():
        raise ValueError(f'Missing SDK Build Tools 35.0.0: {name}')
    return str(tool)


def parse_badging(metadata):
    identity = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", metadata)
    minimum = re.search(r"(?:minSdkVersion|sdkVersion):'(\d+)'", metadata)
    if not identity or not minimum:
        raise ValueError('Cannot read APK package metadata')
    return dict(zip(['package', 'version_code', 'version_name'], identity.groups()),
                minimum_sdk=int(minimum[1]), debuggable='application-debuggable' in metadata)


def apk_metadata(apk):
    metadata = subprocess.check_output([sdk_tool('aapt2'), 'dump', 'badging', str(apk)], text=True)
    result = parse_badging(metadata)
    signatures = subprocess.check_output([sdk_tool('apksigner'), 'verify', '--print-certs', str(apk)], text=True)
    cert = re.search(r'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})', signatures)
    if not cert:
        raise ValueError('Cannot verify APK signature')
    return dict(result, signing_certificate_sha256=cert[1].lower())


def verify_bundle(bundle, expected_manifest_sha256):
    if bundle.is_symlink() or (bundle / 'manifest.json').is_symlink():
        raise ValueError('Symlinks are not supported')
    if sha256(bundle / 'manifest.json') != expected_manifest_sha256:
        raise ValueError('Manifest hash differs from the recorded value')
    manifest = json.loads((bundle / 'manifest.json').read_text())
    if manifest.get('format_version') != 1 or manifest.get('kind') != 'development-preview':
        raise ValueError('Unknown bundle format')
    files = manifest['files']
    if not {'what-to-eat.apk', '参与者说明.txt', '组织者说明.txt', 'PRIVACY.md', 'LICENSE'} <= set(files):
        raise ValueError('Incomplete bundle')
    for name, digest in files.items():
        path = Path(name)
        if path.is_absolute() or '..' in path.parts or '\\' in name or not path.parts:
            raise ValueError('Unsafe bundle filename')
        target = bundle / path
        if target.is_symlink() or any(p.is_symlink() for p in target.parents if p != bundle.parent):
            raise ValueError('Symlinks are not supported')
        if not target.is_file() or sha256(target) != digest:
            raise ValueError(f'Missing or modified bundle file: {name}')
    actual = {str(p.relative_to(bundle)) for p in bundle.rglob('*') if p.is_file() or p.is_symlink()}
    if actual != set(files) | {'manifest.json'}:
        raise ValueError('Unexpected files in bundle')
    return manifest


def review_materials(checkout, bundle):
    output = io.StringIO(newline='')
    writer = csv.writer(output)
    writer.writerow(['id', 'name', 'typical_ingredients', 'groups', 'contexts', 'aliases',
                     'reviewer', 'review_status', 'source_or_basis', 'correction_notes'])
    for line in (checkout / 'core/src/main/resources/foods.tsv').read_text().splitlines():
        if line and not line.startswith('#'):
            writer.writerow(line.split('\t') + ['', 'NOT_REVIEWED', '', ''])
    (bundle / '菜品待审查.csv').write_text(output.getvalue(), encoding='utf-8-sig')
    for variant, name in [('debug', 'runtime-dependencies.tsv'), ('release', 'release-runtime-dependencies.tsv')]:
        rows = (checkout / 'legal' / name).read_text().splitlines()
        output = io.StringIO(newline=''); writer = csv.writer(output)
        writer.writerow(['coordinate', 'license_from_pom', 'upstream_url', 'artifact_sha256',
                         'reviewer', 'license_and_notice_status', 'notes'])
        for row in rows:
            if row and not row.startswith('#') and not row.startswith('coordinate\t'):
                writer.writerow(row.split('\t') + ['', 'NOT_REVIEWED', ''])
        (bundle / f'{variant}-许可待审查.csv').write_text(output.getvalue(), encoding='utf-8-sig')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if subprocess.check_output(['git', 'status', '--porcelain'], cwd=root):
        parser.error('Commit source changes before preparing a preview')
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    # 验证环境后才创建输出，失败目录保留构建日志，不留下成功清单。
    sdk_tool('aapt2'); sdk_tool('apksigner')
    args.output.mkdir(parents=True, exist_ok=False)
    output = args.output.resolve()
    with tempfile.TemporaryDirectory(prefix='what-to-eat-clean-') as temporary:
        checkout = Path(temporary) / 'source'
        subprocess.run(['git', 'clone', '--quiet', '--no-hardlinks', '--no-checkout', str(root), str(checkout)], check=True)
        subprocess.run(['git', 'checkout', '--quiet', '--detach', revision], cwd=checkout, check=True)
        if subprocess.check_output(['git', 'status', '--porcelain'], cwd=checkout):
            raise ValueError('Checkout is not clean')
        commands = [
            ['./gradlew', '-Pbenchmarks', ':core:test', ':experiments:run', ':app:testDebugUnitTest',
             ':app:lintDebug', ':app:assembleDebug', ':app:assembleDebugAndroidTest', ':app:assembleRelease',
             ':app:assembleBenchmark', ':benchmark:assembleBenchmark', ':app:debugRuntimeInventory',
             ':app:releaseRuntimeInventory', '--console=plain'],
            ['python3', '-m', 'unittest', 'discover', '-s', 'scripts', '-p', 'test_*.py', '-v'],
            ['python3', 'scripts/check_repository.py'],
        ]
        with (output / 'build.log').open('w') as log:
            for command in commands:
                subprocess.run(command, cwd=checkout, stdout=log, stderr=subprocess.STDOUT, check=True)
            # 解析结果必须与随包声明完全一致，避免冻结过期清单。
            for variant, name in [('debug', 'runtime-dependencies.tsv'), ('release', 'release-runtime-dependencies.tsv')]:
                generated = output / f'{variant}-inventory.tsv'
                subprocess.run(['python3', 'scripts/runtime_licenses.py', '--variant', variant, '--output', str(generated)],
                               cwd=checkout, stdout=log, stderr=subprocess.STDOUT, check=True)
                if generated.read_bytes() != (checkout / 'legal' / name).read_bytes():
                    raise ValueError('Runtime declarations changed; regenerate and review the source inventory')
        bundle = output / 'bundle'; bundle.mkdir()
        apk = bundle / 'what-to-eat.apk'
        shutil.copyfile(checkout / 'app/build/outputs/apk/debug/app-debug.apk', apk)
        metadata = apk_metadata(apk)
        if metadata['package'] != 'org.whattoeat.app' or not metadata['debuggable']:
            raise ValueError('Expected the ordinary development APK')
        shutil.copyfile(checkout / 'app/build/outputs/apk/release/app-release-unsigned.apk', output / 'release-unsigned.apk')
        shutil.copyfile(checkout / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', output / 'androidTest.apk')
        for name in ['LICENSE', 'PRIVACY.md']:
            shutil.copyfile(checkout / name, bundle / name)
        shutil.copytree(checkout / 'legal', bundle / 'legal')
        for source, name in [('docs/试用说明.txt', '参与者说明.txt'), ('docs/试用组织说明.txt', '组织者说明.txt')]:
            shutil.copyfile(checkout / source, bundle / name)
        review_materials(checkout, bundle)
        manifest = {'format_version': 1, 'kind': 'development-preview', 'source_revision': revision,
                    'apk': metadata, 'protocol': 'local-crossover-v1', 'pool_policy': 'personal-pool-v1', 'human_review_complete': False,
                    'catalog_sha256': sha256(checkout / 'core/src/main/resources/foods.tsv'),
                    'clean_checkout_build': True, 'build_commands': commands,
                    'files': {str(p.relative_to(bundle)): sha256(p) for p in sorted(bundle.rglob('*')) if p.is_file()}}
        (bundle / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
        digest = sha256(bundle / 'manifest.json')
        verify_bundle(bundle, digest)
        (output / 'manifest.sha256').write_text(digest + '\n')
        print(f'Prepared development preview at {bundle}\nManifest SHA-256: {digest}')
        print('Not a formal release. Review/signing/provider/participant gates remain explicit.')


if __name__ == '__main__':
    main()
