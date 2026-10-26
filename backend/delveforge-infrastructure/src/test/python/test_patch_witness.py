"""真实Git及临时源码：验证预演不写入、全目标前像、并发变化与应用后未知。"""
import pathlib
import tempfile
import unittest
from unittest.mock import patch
from test_sandbox_policy import sandbox, fixture_git, witnessed


class PatchWitnessTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = pathlib.Path(self.directory.name)
        self.target = self.root / 'a.txt'
        self.target.write_bytes(b'original\n')
        root_patch = patch.object(sandbox, 'ROOT', self.root)
        root_patch.start()
        self.addCleanup(root_patch.stop)
        self.diff = '--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-original\n+updated\n'

    def apply(self, args):
        with patch.object(sandbox, 'run_bounded', side_effect=fixture_git):
            return sandbox.dispatch('apply_patch', args)

    def test_missing_witness_never_executes_or_mutates(self):
        with patch.object(sandbox, 'run_bounded') as execute:
            with self.assertRaises(sandbox.PolicyError) as caught:
                sandbox.dispatch('apply_patch', {'patch': self.diff})
        self.assertEqual('PATCH_PREIMAGE', caught.exception.rule)
        execute.assert_not_called()
        self.assertEqual(b'original\n', self.target.read_bytes())

    def test_all_preimages_checked_before_any_multifile_mutation(self):
        other = self.root / 'b.txt'
        other.write_bytes(b'original\n')
        args = witnessed(self.diff + self.diff.replace('a/a.txt', 'a/b.txt').replace('b/a.txt', 'b/b.txt'))
        other.write_bytes(b'user-change\n')
        result = self.apply(args)
        self.assertEqual('PATCH_CONFLICT', result['classification'])
        self.assertTrue(result['mutationNotStarted'])
        self.assertEqual(b'original\n', self.target.read_bytes())
        self.assertEqual(b'user-change\n', other.read_bytes())

    def test_preimage_is_checked_again_after_git_check(self):
        args = witnessed(self.diff)
        def change_after_check(command, *values, **options):
            result = fixture_git(command, *values, **options)
            if '--check' in command:
                self.target.write_bytes(b'user-change\n')
            return result
        with patch.object(sandbox, 'run_bounded', side_effect=change_after_check):
            result = sandbox.dispatch('apply_patch', args)
        self.assertEqual('PATCH_CONFLICT', result['classification'])
        self.assertTrue(result['mutationNotStarted'])
        self.assertEqual(b'user-change\n', self.target.read_bytes())

    def test_unexpected_postimage_is_uncertain_and_cannot_claim_no_mutation(self):
        args = witnessed(self.diff)
        args['postimages']['a.txt'] = '0' * 64 + ':100644'
        with self.assertRaises(sandbox.PatchOutcomeUnknown):
            self.apply(args)
        self.assertEqual(b'updated\n', self.target.read_bytes())

    def test_new_path_appearing_after_preview_is_preserved(self):
        diff = '--- /dev/null\n+++ b/new.txt\n@@ -0,0 +1 @@\n+new\n'
        args = witnessed(diff)
        target = self.root / 'new.txt'
        target.write_bytes(b'user-owned\n')
        result = self.apply(args)
        self.assertEqual('PATCH_CONFLICT', result['classification'])
        self.assertEqual(b'user-owned\n', target.read_bytes())


if __name__ == '__main__':
    unittest.main()
