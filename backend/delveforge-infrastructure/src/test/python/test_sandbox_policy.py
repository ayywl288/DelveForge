"""直接测试工具协议；临时目录与标准库替身，不启动 Docker、模型或 Agent Run。"""
import importlib.util
import json
import pathlib
import re
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SOURCE = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/agent/sandbox.py'
spec = importlib.util.spec_from_file_location('sandbox', SOURCE)
sandbox = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sandbox)


class SandboxPolicyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        self.root_patch = patch.object(sandbox, 'ROOT', self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)

    def test_hunk_content_is_not_a_file_header(self):
        target = self.root / 'Notes.txt'
        target.write_text('-- marker\n', encoding='utf-8', newline='\n')
        diff = '--- a/Notes.txt\n+++ b/Notes.txt\n@@ -1 +1 @@\n--- marker\n+++ marker\n'
        checked = subprocess.run(['git', 'apply', '--no-index', '--check', '-'], input=diff.encode(),
                                 cwd=self.root, capture_output=True, check=False)
        self.assertEqual(0, checked.returncode)
        self.assertEqual(['Notes.txt'], sandbox.patch_paths(diff))

    def test_rule_catalog_is_allowlisted_by_java(self):
        # 从 Maven module 的兄弟目录定位，不依赖宿主仓库绝对路径。
        java = SOURCE.parents[4].parent / 'delveforge-application/src/main/java/com/ayywl/delveforge/application/agent/AgentPolicyRejection.java'
        catalog = java.read_text(encoding='utf-8').split('public enum Rule {', 1)[1].split('}', 1)[0]
        rules = set(re.findall(r"raise PolicyError\('([A-Z_]+)'", SOURCE.read_text(encoding='utf-8')))
        self.assertTrue(rules.issubset(set(re.findall(r'[A-Z][A-Z_]+', catalog))))

    def test_cli_policy_diagnostics_do_not_echo_patch_or_protected_path(self):
        request = {'name': 'apply_patch', 'arguments': {'patch': '--- a/.env\n+++ b/.env\nprivate-patch-body'}}
        result = subprocess.run([sys.executable, str(SOURCE)], input=json.dumps(request).encode(),
                                capture_output=True, cwd=self.root, check=False)
        self.assertEqual(0, result.returncode)
        data = json.loads(result.stdout)
        self.assertEqual('POLICY_REJECTED', data['classification'])
        self.assertEqual('PROTECTED_CREDENTIAL_PATH', data['policyRule'])
        self.assertNotIn('.env', result.stdout.decode())
        self.assertNotIn('private-patch-body', result.stdout.decode())
        self.assertNotIn('reason', data)

    def assert_policy(self, rule, operation):
        with self.assertRaises(sandbox.PolicyError) as rejected:
            operation()
        self.assertEqual(rule, rejected.exception.rule)
        return rejected.exception

    def test_path_restrictions(self):
        for value, rule in [(None, 'PATH_FORMAT'), ('x\\y', 'PATH_FORMAT'), ('x'*513, 'PATH_FORMAT'),
                            ('.env', 'PROTECTED_CREDENTIAL_PATH'), ('keys/key.pem', 'PROTECTED_CREDENTIAL_PATH'),
                            ('keys.pem/child', 'PROTECTED_CREDENTIAL_PATH'), ('../host', 'FORBIDDEN_PATH'),
                            ('/host', 'FORBIDDEN_PATH'), ('.git/config', 'FORBIDDEN_PATH'), ('CON.txt', 'FORBIDDEN_PATH')]:
            with self.subTest(rule=rule):
                self.assert_policy(rule, lambda: sandbox.path_for(value))
        with patch.object(pathlib.Path, 'is_symlink', return_value=True):
            self.assert_policy('SYMLINK_PATH', lambda: sandbox.path_for('safe.txt'))

    def test_forbidden_patch_metadata_is_rejected_before_execution(self):
        cases = [('GIT binary patch\n', 'PATCH_TYPE'), ('rename from old\n', 'PATCH_TYPE'),
                 ('copy to next\n', 'PATCH_TYPE'), ('new file mode 120000\n', 'PATCH_TYPE'),
                 ('diff --git wrong wrong\n', 'PATCH_DIFF_PATHS'), ('new mode 100600\n', 'PATCH_FILE_MODE'),
                 ('--- plain.txt\n', 'PATCH_PATH_FORMAT'), ('--- a/.env\n', 'PROTECTED_CREDENTIAL_PATH'),
                 ('--- a/../host\n', 'FORBIDDEN_PATH')]
        (self.root / 'safe.txt').write_text('unchanged', encoding='utf-8')
        with patch.object(sandbox, 'run_bounded') as execute:
            for diff, rule in cases:
                with self.subTest(rule=rule):
                    self.assert_policy(rule, lambda: sandbox.dispatch('apply_patch', {'patch': diff}))
            execute.assert_not_called()
        self.assertEqual('unchanged', (self.root / 'safe.txt').read_text(encoding='utf-8'))

    def test_patch_size_and_generated_paths(self):
        with patch.dict(sandbox.os.environ, {'DF_PATCH_BYTES': '2'}):
            self.assert_policy('PATCH_SIZE', lambda: sandbox.patch_paths('large'))
        (self.root / 'pom.xml').write_text('<project/>', encoding='utf-8')
        self.assert_policy('GENERATED_PATH', lambda: sandbox.patch_paths('--- a/target/File.java\n'))
        (self.root / 'target').mkdir()
        (self.root / 'target/pom.xml').write_text('<project/>', encoding='utf-8')
        self.assert_policy('AMBIGUOUS_GENERATED_PATH', lambda: sandbox.patch_paths('--- a/target/File.java\n'))

    def test_malformed_patch_is_non_mutating_tool_error(self):
        with patch.object(sandbox, 'run_bounded') as execute:
            for diff in ['invalid patch', '--- a/a.txt\n+++ b/a.txt\n@@ bad @@\n',
                         '--- a/a.txt\n+++ b/a.txt\n@@ -9,100 +9,100 @@\n--- a/.env\n+++ b/.env\n']:
                with self.subTest():
                    with self.assertRaises(ValueError):
                        sandbox.dispatch('apply_patch', {'patch': diff})
            execute.assert_not_called()

    def test_multiple_files_and_no_newline_marker_preserve_actual_targets(self):
        diff = ('--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n--- content\n+++ content\n'
                '\\ No newline at end of file\n--- a/b.txt\n+++ b/b.txt\n@@ -0,0 +1 @@\n+value\n')
        self.assertEqual(['a.txt', 'b.txt'], sandbox.patch_paths(diff))
        with self.assertRaises(sandbox.PolicyError) as rejected:
            sandbox.patch_paths(diff + '--- a/.git/config\n')
        self.assertEqual('FORBIDDEN_PATH', rejected.exception.rule)

    def test_valid_hunk_header_text_applies_with_real_git_only_to_fixture(self):
        target = self.root / 'Notes.txt'
        target.write_text('-- marker\n', encoding='utf-8', newline='\n')
        diff = '--- a/Notes.txt\n+++ b/Notes.txt\n@@ -1 +1 @@\n--- marker\n+++ marker\n'
        def git(command, input_bytes, *_args):
            result = subprocess.run(command, input=input_bytes, cwd=self.root, capture_output=True, check=False)
            return result.returncode, result.stdout.decode('utf-8')
        with patch.object(sandbox, 'run_bounded', side_effect=git):
            result = sandbox.dispatch('apply_patch', {'patch': diff})
        self.assertEqual('PATCH_APPLIED', result['classification'])
        self.assertEqual(['Notes.txt'], result['paths'])
        self.assertEqual('++ marker\n', target.read_text(encoding='utf-8'))

    def test_capture_rejection_after_apply_is_classified_without_claiming_no_mutation(self):
        failure = sandbox.PolicyError('SOURCE_CAPTURE_LIMIT', 'fixed internal diagnostic')
        diff = '--- /dev/null\n+++ b/a.txt\n@@ -0,0 +1 @@\n+value\n'
        with patch.object(sandbox, 'run_bounded', return_value=(0, '')), patch.object(sandbox, 'snapshot', side_effect=failure):
            caught = self.assert_policy('SOURCE_CAPTURE_LIMIT', lambda: sandbox.dispatch('apply_patch', {'patch': diff}))
        self.assertIs(failure, caught)
        self.assertEqual('SOURCE_CAPTURE', caught.stage)

    def test_inspect_patch_only_reads_original_source_and_never_executes_or_mutates(self):
        original = 'password="synthetic-private-value"\nordinary=1\n'
        target = self.root / 'Config.txt'
        target.write_text(original, encoding='utf-8', newline='\n')
        diff = '--- a/Config.txt\n+++ b/Config.txt\n@@ -2 +2 @@\n-ordinary=1\n+ordinary=2\n'
        with patch.object(sandbox, 'run_bounded') as execute:
            inspected = sandbox.dispatch('inspect_patch', {'patch': diff})
            execute.assert_not_called()
        self.assertEqual(original, inspected['Config.txt'])
        self.assertEqual(original, target.read_text(encoding='utf-8'))

    def test_source_entry_type_and_protected_entries(self):
        (self.root / 'safe.txt').write_text('content', encoding='utf-8')
        with patch.object(pathlib.Path, 'is_symlink', return_value=True):
            self.assert_policy('SOURCE_ENTRY_TYPE', sandbox.all_files)
        (self.root / '.git').mkdir()
        self.assert_policy('SOURCE_PROTECTED_ENTRY', sandbox.all_files)

    def test_source_limits(self):
        for name in ('a.txt', 'b.txt'):
            (self.root / name).write_text('content', encoding='utf-8')
        with patch.object(sandbox, 'FILES_LIMIT', 1):
            self.assert_policy('SOURCE_FILE_LIMIT', sandbox.all_files)
            for name in ('c.txt', 'd.txt', 'e.txt'):
                (self.root / name).write_text('content', encoding='utf-8')
            self.assert_policy('SOURCE_ENTRY_LIMIT', sandbox.all_files)
        with patch.object(sandbox, 'FILE_LIMIT', 1):
            self.assert_policy('SOURCE_CAPTURE_LIMIT', sandbox.snapshot)

    def test_case_conflicts(self):
        with patch.object(sandbox.os, 'walk', return_value=[(str(self.root), [], ['a.txt', 'A.txt'])]), \
                patch.object(pathlib.Path, 'is_file', return_value=True):
            self.assert_policy('SOURCE_CASE_CONFLICT', sandbox.all_files)

    def test_command_output_limit_remains_enforced(self):
        # Windows 夹具替换 Linux 进程组 API；真实短命子进程仍由 run_bounded 等待退出。
        with patch.object(sandbox.os, 'killpg', create=True), patch.object(sandbox.signal, 'SIGKILL', 9, create=True):
            self.assert_policy('COMMAND_BUDGET', lambda: sandbox.run_bounded(
                [sys.executable, '-c', 'print("x"*10000)'], maximum=10, cwd=self.root))


if __name__ == '__main__':
    unittest.main()
