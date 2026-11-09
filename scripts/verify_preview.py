#!/usr/bin/env python3
"""用另行记录的清单哈希、文件哈希及APK实际签名验证冻结预览。"""
import argparse
from pathlib import Path
from prepare_preview import apk_metadata, verify_bundle


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('--manifest-sha256', required=True)
    args = parser.parse_args()
    manifest = verify_bundle(args.bundle.absolute(), args.manifest_sha256)
    if apk_metadata(args.bundle / 'what-to-eat.apk') != manifest['apk']:
        raise ValueError('APK metadata or signing certificate differs')
    print('Preview files, APK signature and metadata verified; human review is not implied.')


if __name__ == '__main__':
    main()
