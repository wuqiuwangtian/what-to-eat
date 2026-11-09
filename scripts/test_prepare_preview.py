import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from prepare_preview import verify_bundle


class PreviewIntegrityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.bundle = Path(self.temporary.name) / 'bundle'
        self.bundle.mkdir()
        self.files = {}
        for name in ['what-to-eat.apk', '参与者说明.txt', '组织者说明.txt', 'PRIVACY.md', 'LICENSE']:
            target = self.bundle / name
            target.write_bytes(name.encode())
            self.files[name] = hashlib.sha256(target.read_bytes()).hexdigest()
        self.anchor = self.write_manifest(self.files)

    def write_manifest(self, files):
        target = self.bundle / 'manifest.json'
        target.write_text(json.dumps({'format_version': 1, 'kind': 'development-preview', 'files': files}))
        return hashlib.sha256(target.read_bytes()).hexdigest()

    def test_complete_unchanged_bundle_is_accepted(self):
        self.assertEqual(self.files, verify_bundle(self.bundle, self.anchor)['files'])

    def test_changed_apk_is_rejected(self):
        (self.bundle / 'what-to-eat.apk').write_bytes(b'changed')
        with self.assertRaises(ValueError):
            verify_bundle(self.bundle, self.anchor)

    def test_changed_manifest_cannot_replace_recorded_anchor(self):
        self.write_manifest({**self.files, 'other.apk': '0' * 64})
        with self.assertRaisesRegex(ValueError, 'Manifest hash'):
            verify_bundle(self.bundle, self.anchor)

    def test_parent_escape_is_rejected_even_with_matching_anchor(self):
        anchor = self.write_manifest({**self.files, '../outside': '0' * 64})
        with self.assertRaisesRegex(ValueError, 'Unsafe'):
            verify_bundle(self.bundle, anchor)

    def test_symlink_payload_is_rejected(self):
        (self.bundle / 'LICENSE').unlink()
        (self.bundle / 'LICENSE').symlink_to(self.bundle / 'PRIVACY.md')
        with self.assertRaisesRegex(ValueError, 'Symlinks'):
            verify_bundle(self.bundle, self.anchor)

    def test_unlisted_file_is_rejected(self):
        (self.bundle / 'unexpected-private-data.json').write_text('{}')
        with self.assertRaisesRegex(ValueError, 'Unexpected'):
            verify_bundle(self.bundle, self.anchor)

    def test_missing_required_instructions_is_rejected(self):
        anchor = self.write_manifest({k: v for k, v in self.files.items() if k != '参与者说明.txt'})
        with self.assertRaisesRegex(ValueError, 'Incomplete'):
            verify_bundle(self.bundle, anchor)
