"""真实 Git 只操作临时 Java/Maven 夹具，不运行 Docker 或模型。"""
import difflib
import pathlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from test_sandbox_policy import sandbox, fixture_git


class PatchFeedbackTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = pathlib.Path(self.directory.name)
        self.target = self.root / 'Counter.java'
        self.original = 'class Counter {\n    int first = 1;\n' + '    // context\n' * 8 + '    int last = 1;\n}\n'
        self.target.write_bytes(self.original.encode())
        (self.root / 'pom.xml').write_text('<project/>', encoding='utf-8')
        root_patch = patch.object(sandbox, 'ROOT', self.root)
        root_patch.start()
        self.addCleanup(root_patch.stop)

    def diff(self, old, new):
        return ''.join(difflib.unified_diff(old.splitlines(keepends=True), new.splitlines(keepends=True),
                                          fromfile='a/Counter.java', tofile='b/Counter.java', n=2))

    def witness(self, value):
        with patch.object(sandbox, 'run_bounded', side_effect=fixture_git):
            preview = sandbox.dispatch('inspect_patch', {'patch': value})
        if 'classification' in preview:
            return preview
        return {'patch': value,
                'preimages': {key: data['preimage'] for key, data in preview['files'].items()},
                'postimages': {key: data['postimage'] for key, data in preview['files'].items()}}

    def apply(self, value):
        args = self.witness(value)
        if 'classification' in args:
            return args
        with patch.object(sandbox, 'run_bounded', side_effect=fixture_git):
            return sandbox.dispatch('apply_patch', args)

    def test_stale_context_is_confirmed_non_mutating_and_has_safe_actionable_feedback(self):
        changed = self.original.replace('first = 1', 'first = 2')
        self.assertEqual('PATCH_APPLIED', self.apply(self.diff(self.original, changed))['classification'])
        conflicting = self.diff(self.original, self.original.replace('first = 1', 'first = 3'))
        result = self.apply(conflicting)
        self.assertEqual('PATCH_CONFLICT', result['classification'])
        self.assertTrue(result['mutationNotStarted'])
        self.assertEqual('CONTEXT_MISMATCH', result['conflictReason'])
        self.assertNotIn('output', result)
        self.assertIn('重新读取', result['instruction'])
        self.assertEqual(changed.encode(), self.target.read_bytes())

    def test_multiple_hunks_and_crlf_preserve_existing_line_endings(self):
        for ending in ('\n', '\r\n'):
            old = self.original.replace('\n', ending)
            new = old.replace('first = 1', 'first = 2').replace('last = 1', 'last = 2')
            self.target.write_bytes(old.encode())
            result = self.apply(self.diff(old, new))
            self.assertEqual('PATCH_APPLIED', result['classification'])
            self.assertEqual(new.encode(), self.target.read_bytes())

    def test_already_present_change_is_not_reported_as_new_application(self):
        change = self.diff(self.original, self.original.replace('first = 1', 'first = 2'))
        self.assertEqual('PATCH_APPLIED', self.apply(change)['classification'])
        before = self.target.read_bytes()
        result = self.apply(change)
        self.assertEqual('PATCH_CONFLICT', result['classification'])
        self.assertEqual('CHANGE_ALREADY_PRESENT', result['conflictReason'])
        self.assertTrue(result['mutationNotStarted'])
        self.assertEqual(before, self.target.read_bytes())

    def test_lf_patch_for_crlf_source_is_diagnosed_without_rewriting_source(self):
        self.target.write_bytes(self.original.replace('\n', '\r\n').encode())
        before = self.target.read_bytes()
        result = self.apply(self.diff(self.original, self.original.replace('first = 1', 'first = 2')))
        self.assertEqual('PATCH_CONFLICT', result['classification'])
        self.assertEqual(['CRLF'], result['sourceLineEndings'])
        self.assertEqual(before, self.target.read_bytes())

    def test_apply_failure_after_successful_check_is_uncertain_and_never_recoverable(self):
        change = self.diff(self.original, self.original.replace('first = 1', 'first = 2'))
        args = self.witness(change)
        with patch.object(sandbox, 'run_bounded', side_effect=[(0, ''), (1, 'private diagnostic')]):
            with self.assertRaises(sandbox.PatchOutcomeUnknown):
                sandbox.dispatch('apply_patch', args)

    def test_capture_io_failure_after_apply_is_not_returned_as_recoverable_tool_error(self):
        change = self.diff(self.original, self.original.replace('first = 1', 'first = 2'))
        args = self.witness(change)
        with patch.object(sandbox, 'run_bounded', side_effect=fixture_git), patch.object(sandbox, 'snapshot', side_effect=OSError('private')):
            with self.assertRaises(sandbox.PatchOutcomeUnknown):
                sandbox.dispatch('apply_patch', args)
