#!/usr/bin/env python3
"""检查本仓库的文档入口、原型数据和不可公开文件边界；不替代人工审阅。"""
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[1]
errors = []
required = ['LICENSE', 'PRIVACY.md', 'CONTRIBUTING.md', 'SECURITY.md', 'docs/总体计划.md',
            'docs/验证与发布.md', 'legal/THIRD_PARTY_NOTICES.md', 'gradle/wrapper/gradle-wrapper.jar']
for name in required:
    if not (root / name).is_file():
        errors.append(f'Missing required file: {name}')
for path in root.rglob('*.md'):
    if any(part in {'.git', '.gradle', 'build'} for part in path.parts):
        continue
    for link in re.findall(r'\]\(([^)]+)\)', path.read_text()):
        if link.startswith(('http:', 'https:', 'mailto:', '#')):
            continue
        target = link.split('#', 1)[0]
        if target and not (path.parent / target).exists():
            errors.append(f'Broken local link: {path.relative_to(root)} -> {target}')
rows = []
for line in (root / 'core/src/main/resources/foods.tsv').read_text().splitlines():
    if line.startswith('#') or not line.strip():
        continue
    cells = line.split('\t')
    if len(cells) != 6 or not all(cells[:5]):
        errors.append(f'Invalid food record: {line[:80]}')
    rows.append(cells)
if len(rows) < 100 or len({r[0] for r in rows}) != len(rows):
    errors.append('Catalog needs 100+ stable unique IDs')
tracked = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode().split('\0')
for name in filter(None, tracked):
    path = Path(name)
    if path.name == 'local.properties' or path.suffix in {'.jks', '.keystore', '.hprof'} or name.startswith('private/'):
        errors.append(f'Private file tracked: {name}')
    full = root / path
    if full.is_file() and full.stat().st_size < 2_000_000 and path.suffix not in {'.jar', '.png', '.jpg'}:
        text = full.read_text(errors='replace')
        if re.search(r'gh[pousr]_[A-Za-z0-9]{30,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----', text):
            errors.append(f'Possible credential in: {name}')
if errors:
    raise SystemExit('\n'.join(errors))
print(f'Repository checks passed; {len(rows)} catalog records. No runtime or effectiveness claim.')
