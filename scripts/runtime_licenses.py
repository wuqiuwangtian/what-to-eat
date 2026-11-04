#!/usr/bin/env python3
"""从实际解析的运行时组件生成归属清单；缺少POM许可时显式标记，不能据此自动批准发行。"""
from pathlib import Path
import hashlib
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
source = root / 'app/build/reports/runtime-artifacts.tsv'
if not source.exists():
    raise SystemExit('Run ./gradlew :app:runtimeInventory first')
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
lines = ['# Generated from resolved debug runtime; review licenses and notices before release.',
         'coordinate\tlicense_from_pom\tupstream_url\tartifact_sha256']
for row in source.read_text().splitlines():
    coordinate, filename = row.split('\t', 1)
    if coordinate.startswith('what-to-eat:'):
        continue
    group, name, version = coordinate.split(':', 2)
    poms = list((cache / group / name / version).glob('*/*.pom'))
    license_names, url = [], ''
    if poms:
        doc = ET.parse(poms[0]).getroot()
        ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
        license_names = [el.text or '' for el in doc.findall('m:licenses/m:license/m:name', ns)]
        url = doc.findtext('m:url', default='', namespaces=ns)
    digest = hashlib.sha256(Path(filename).read_bytes()).hexdigest()
    lines.append('\t'.join([coordinate, '; '.join(license_names) or 'NOT_DECLARED_IN_THIS_POM', url, digest]))
out = root / 'legal/runtime-dependencies.tsv'
out.write_text('\n'.join(lines) + '\n')
print(f'Wrote {out.relative_to(root)} ({len(lines)-2} artifacts); declarations need human review.')
