"""能力由可信主机提供；容器不得推断秘密安全或披露未授权资源。"""
import hashlib
import json
import importlib.util
import pathlib
import sys
import tempfile
import unittest
from unittest.mock import patch

SOURCE = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/agent'
sys.path.insert(0, str(SOURCE))
spec = importlib.util.spec_from_file_location('admission_sandbox', SOURCE / 'sandbox.py')
sandbox = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = sandbox
spec.loader.exec_module(sandbox)


class ResourceCapabilityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        self.public = b'API_URL=https://example.com\n'
        self.secret = b'password=never-materialized-secret\n'
        (self.root / '.env.example').write_bytes(self.public)
        (self.root / '.env.example').chmod(0o644)
        (self.root / 'Main.java').write_text('class Main {}\n')
        self.capabilities = {
            '.env.example': self.identity('PUBLIC_RESOURCE', self.public),
            'private.properties': self.identity('PRESERVED_ONLY', self.secret)}
        for name, value in (('ROOT', self.root), ('RESOURCE_CAPABILITIES', self.capabilities)):
            active = patch.object(sandbox, name, value)
            active.start()
            self.addCleanup(active.stop)

    def identity(self, kind, content):
        return {'kind': kind, 'sha256': hashlib.sha256(content).hexdigest(),
                'size': len(content), 'mode': '100644', 'settings': {}}

    def test_snapshot_explicitly_represents_projection_without_capturing_resource_content(self):
        snap = sandbox.snapshot(include_content=True)
        self.assertEqual('PROJECTED', snap['sourceView']['kind'])
        entries = {entry['path']: entry for entry in snap['files']}
        self.assertEqual(hashlib.sha256(self.secret).hexdigest(), entries['private.properties']['sha256'])
        self.assertTrue(entries['private.properties']['preservedOnly'])
        self.assertNotIn('content', entries['private.properties'])
        self.assertNotIn('content', entries['.env.example'])
        self.assertFalse((self.root / 'private.properties').exists())
        self.assertTrue(sandbox.approved_resource('.env.example'))

    def test_model_list_search_reads_and_mutation_cannot_bypass_visibility(self):
        self.assertEqual(['.env.example', 'Main.java'], sandbox.dispatch('list_files', {})['files'])
        self.assertEqual('.env.example', sandbox.dispatch('search_code', {'query': 'example.com'})['matches'][0]['path'])
        for path in self.capabilities:
            if path == '.env.example':
                self.assertIn('example.com', sandbox.dispatch('read_file', {'path': path})['_source'])
            else:
                with self.assertRaises(sandbox.PolicyError):
                    sandbox.dispatch('read_file', {'path': path, 'resource': True})
            patch_text = '--- a/' + path + '\n+++ b/' + path + '\n@@ -1 +1 @@\n-x\n+y\n'
            with self.assertRaises(sandbox.PolicyError):
                sandbox.dispatch('inspect_patch', {'patch': patch_text})

    def test_hash_binding_and_check_mutation_detection_cover_read_only_resources(self):
        before = sandbox.snapshot()
        (self.root / '.env.example').write_text('API_URL=https://changed.example\n')
        self.assertFalse(sandbox.approved_resource('.env.example'))
        after = sandbox.snapshot()
        self.assertNotEqual(before['snapshot'], after['snapshot'])
        self.assertNotIn('changed.example', json.dumps(after))

    def test_materializing_withheld_content_or_replacing_a_resource_with_a_link_is_rejected(self):
        (self.root / 'private.properties').write_bytes(self.secret)
        with self.assertRaises(sandbox.PolicyError) as rejected:
            sandbox.snapshot(include_content=True)
        self.assertEqual('PRESERVED_RESOURCE_MATERIALIZED', rejected.exception.rule)
        self.assertNotIn('never-materialized-secret', str(rejected.exception))
        (self.root / 'private.properties').unlink()
        (self.root / '.env.example').unlink()
        (self.root / '.env.example').symlink_to(self.root / 'Main.java')
        with self.assertRaises(sandbox.PolicyError):
            sandbox.snapshot()

    def test_withholding_executable_test_sources_cannot_turn_remaining_checks_into_passes(self):
        self.capabilities['SensitiveTest.java'] = {**self.identity('PRESERVED_ONLY', b'private test source'),
                                                 'blocksChecks': True}
        snapshot = sandbox.snapshot(include_content=True)
        self.assertEqual(1, snapshot['sourceView']['withheldSourceCount'])
        self.assertNotIn('private test source', json.dumps(snapshot))
        self.assertFalse((self.root / 'SensitiveTest.java').exists())



if __name__ == '__main__':
    unittest.main()
