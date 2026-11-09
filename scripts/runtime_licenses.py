#!/usr/bin/env python3
"""从解析的运行时组件生成POM声明清单；无许可/NOTICE结论自动批准。"""
import argparse
import hashlib
from pathlib import Path
import xml.etree.ElementTree as ET


def generate(source, cache):
    lines = ['coordinate\tlicense_from_pom\tupstream_url\tartifact_sha256']
    for row in source.read_text().splitlines():
        coordinate, filename = row.split('\t', 1)
        if coordinate.startswith('what-to-eat:'):
            continue
        group, name, version = coordinate.split(':', 2)
        poms = sorted((cache / group / name / version).glob('*/*.pom'))
        licenses, url = [], ''
        if poms:
            doc = ET.parse(poms[0]).getroot()
            ns = '{http://maven.apache.org/POM/4.0.0}' if doc.tag.startswith('{') else ''
            licenses = [el.text or '' for el in doc.findall(f'{ns}licenses/{ns}license/{ns}name')]
            url = doc.findtext(f'{ns}url', default='')
        digest = hashlib.sha256(Path(filename).read_bytes()).hexdigest()
        lines.append('\t'.join([coordinate, '; '.join(licenses) or 'NOT_DECLARED_IN_THIS_POM', url, digest]))
    return '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--variant', choices=['debug', 'release'], default='debug')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    source = root / f'app/build/reports/{args.variant}-runtime-artifacts.tsv'
    if not source.exists():
        parser.error(f'Run ./gradlew :app:{args.variant}RuntimeInventory first')
    cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
    out = args.output or root / ('legal/runtime-dependencies.tsv' if args.variant == 'debug' else 'legal/release-runtime-dependencies.tsv')
    content = f'# Generated from resolved {args.variant} runtime; declarations require human review.\n' + generate(source, cache)
    out.write_text(content)
    print(f'Wrote {len(content.splitlines())-2} artifact declarations; no license approval implied.')


if __name__ == '__main__':
    main()
