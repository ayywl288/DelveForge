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


def fixture_git(command, input_bytes=None, *_args, **kwargs):
    result = subprocess.run(command, input=input_bytes, cwd=kwargs.get('cwd', sandbox.ROOT),
                            capture_output=True, check=False, timeout=10)
    return result.returncode, (result.stdout + result.stderr).decode('utf-8', errors='replace')


def witnessed(patch_text):
    with patch.object(sandbox, 'run_bounded', side_effect=fixture_git):
        preview = sandbox.dispatch('inspect_patch', {'patch': patch_text})
    return {'patch': patch_text,
            'preimages': {key: data['preimage'] for key, data in preview['files'].items()},
            'postimages': {key: data['postimage'] for key, data in preview['files'].items()}}


class SandboxPolicyTest(unittest.TestCase):
    def test_invalid_read_range_never_reads_content_and_can_be_corrected(self):
        target = self.root / 'source.txt'
        target.write_bytes(b'one\ntwo\n')
        with patch.object(sandbox, 'text', side_effect=AssertionError('must not read')):
            for values in ({'start_line': 0}, {'line_count': 301}, {'start_line': True}):
                with self.subTest(values=values):
                    result = sandbox.dispatch('read_file', {'path': 'source.txt', **values})
                    self.assert_read_error(result, 'INVALID_RANGE')
        self.assertEqual('one\ntwo\n', sandbox.dispatch('read_file', {'path': 'source.txt'})['_source'])

    def test_missing_sources_are_recoverable_without_read_or_enumeration(self):
        with patch.object(sandbox, 'text', side_effect=AssertionError('must not read')), \
                patch.object(sandbox, 'all_files', side_effect=AssertionError('must not enumerate')):
            for tool in ('read_file', 'list_files', 'search_code'):
                with self.subTest(tool=tool):
                    result = sandbox.dispatch(tool, {'path': 'missing.txt', 'query': 'one'})
                    self.assert_read_error(result, 'PATH_NOT_FOUND')

    def test_invalid_pagination_and_query_do_not_read_sources(self):
        with patch.object(sandbox, 'all_files', side_effect=AssertionError('must not enumerate')):
            for values in ({'offset': -1}, {'limit': 0}, {'limit': 201}, {'offset': False}):
                with self.subTest(values=values):
                    self.assert_read_error(sandbox.dispatch('list_files', values), 'INVALID_PAGINATION')
            self.assert_read_error(sandbox.dispatch('search_code', {'query': 'x' * 201}), 'INVALID_QUERY')

    def test_sensitive_targets_stay_blocked_even_with_invalid_range(self):
        (self.root / 'link').symlink_to(self.root.parent, target_is_directory=True)
        with patch.object(sandbox, 'text', side_effect=AssertionError('must not read')):
            for path in ('../outside', '/etc/passwd', '.env', '.ssh/config', 'link/outside'):
                with self.subTest(path=path):
                    with self.assertRaises(sandbox.PolicyError):
                        sandbox.dispatch('read_file', {'path': path, 'start_line': 0})

    def assert_read_error(self, result, reason):
        self.assertEqual('INVALID_TOOL_ARGUMENT', result['classification'])
        self.assertEqual(reason, result['reason'])
        self.assertTrue(result['readNotStarted'])
        self.assertTrue(result['mutationNotStarted'])
        self.assertTrue(result['instruction'])
        self.assertNotIn('_source', result)
        self.assertNotIn('path', result)

    def test_frontend_generated_names_do_not_hide_or_forbid_maven_source(self):
        path = self.root / 'src/main/java/dist/Counter.java'
        path.parent.mkdir(parents=True)
        path.write_text('class Counter {}\n')
        diff = '--- a/src/main/java/dist/Counter.java\n+++ b/src/main/java/dist/Counter.java\n@@ -1 +1 @@\n-class Counter {}\n+class Counter { int n; }\n'
        self.assertEqual(['src/main/java/dist/Counter.java'], sandbox.patch_paths(diff))
        (self.root / 'package.json').write_text('{}')
        (self.root / 'dist').mkdir()
        (self.root / 'dist/output.js').write_text('generated')
        (self.root / '.gitignore').write_text('dist/\n')
        self.assertNotIn('dist/output.js', sandbox.all_files())

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        self.root_patch = patch.object(sandbox, 'ROOT', self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)

    def test_opaque_unchanged_snapshot_is_streamed_without_capture_content(self):
        target = self.root / 'opaque.bin'
        target.write_bytes(b'\x00\xff' * 200000)
        initial = sandbox.snapshot()
        item = initial['files'][0]
        baseline = {item['path']: item['sha256'] + ':' + item['mode']}
        with patch.object(sandbox, 'FILE_LIMIT', 1):
            final = sandbox.snapshot(True, baseline)
            self.assertEqual(initial['snapshot'], final['snapshot'])
            self.assertNotIn('content', final['files'][0])
            target.write_bytes(b'changed')
            self.assert_policy('SOURCE_CAPTURE_LIMIT', lambda: sandbox.snapshot(True, baseline))


    def test_test_fixture_pom_does_not_hide_its_target_resource(self):
        (self.root / 'pom.xml').write_text('<project/>')
        fixture = self.root / 'src/test/resources/template'
        fixture.mkdir(parents=True)
        (fixture / 'pom.xml').write_text('<project/>')
        (fixture / 'target').mkdir()
        (fixture / 'target/business.json').write_text('original fixture')
        self.assertIn('src/test/resources/template/target/business.json', sandbox.all_files())

    def test_protocol_cli_is_read_only_and_explicit(self):
        request = {'name': 'protocol', 'arguments': {}}
        before = list(self.root.iterdir())
        result = subprocess.run([sys.executable, str(SOURCE)], input=json.dumps(request).encode(),
                                capture_output=True, cwd=self.root, check=False)
        self.assertEqual(0, result.returncode)
        self.assertEqual({'ok': True, 'tool': 'protocol', 'result': {
            'protocol': 'delveforge-sandbox', 'version': 9}},
            {k: v for k, v in json.loads(result.stdout).items() if k != 'durationMs'})
        self.assertEqual(before, list(self.root.iterdir()))
        self.assertEqual(b'', result.stderr)

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

    def test_patch_size_and_runtime_dependency_paths(self):
        with patch.dict(sandbox.os.environ, {'DF_PATCH_BYTES': '2'}):
            self.assert_policy('PATCH_SIZE', lambda: sandbox.patch_paths('large'))
        self.assert_policy('GENERATED_PATH', lambda: sandbox.patch_paths('--- a/node_modules/file.js\n'))

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
        args = witnessed(diff)
        with patch.object(sandbox, 'run_bounded', side_effect=fixture_git):
            result = sandbox.dispatch('apply_patch', args)
        self.assertEqual('PATCH_APPLIED', result['classification'])
        self.assertEqual(['Notes.txt'], result['paths'])
        self.assertEqual('++ marker\n', target.read_text(encoding='utf-8'))

    def test_capture_rejection_after_apply_is_classified_without_claiming_no_mutation(self):
        failure = sandbox.PolicyError('SOURCE_CAPTURE_LIMIT', 'fixed internal diagnostic')
        diff = '--- /dev/null\n+++ b/a.txt\n@@ -0,0 +1 @@\n+value\n'
        args = witnessed(diff)
        with patch.object(sandbox, 'run_bounded', side_effect=fixture_git), patch.object(sandbox, 'snapshot', side_effect=failure):
            caught = self.assert_policy('SOURCE_CAPTURE_LIMIT', lambda: sandbox.dispatch('apply_patch', args))
        self.assertIs(failure, caught)
        self.assertEqual('SOURCE_CAPTURE', caught.stage)

    def test_patch_preview_only_mutates_private_copies_and_preserves_original_bytes(self):
        original = 'password="synthetic-private-value"\nordinary=1\n'
        target = self.root / 'Config.txt'
        target.write_bytes(original.encode())
        diff = '--- a/Config.txt\n+++ b/Config.txt\n@@ -2 +2 @@\n-ordinary=1\n+ordinary=2\n'
        with patch.object(sandbox, 'run_bounded', side_effect=fixture_git):
            result = sandbox.dispatch('inspect_patch', {'patch': diff})
        self.assertEqual(original.encode(), target.read_bytes())
        self.assertEqual(original, result['files']['Config.txt']['before'])
        self.assertEqual(original.replace('ordinary=1', 'ordinary=2'), result['files']['Config.txt']['after'])
        self.assertEqual([[2, 1, 2, 1]], result['files']['Config.txt']['changes'])

    def test_source_entry_type_and_protected_entries(self):
        (self.root / 'safe.txt').write_text('content', encoding='utf-8')
        with patch.object(pathlib.Path, 'is_symlink', return_value=True):
            self.assert_policy('SYMLINK_PATH', sandbox.all_files)
        (self.root / '.git').mkdir()
        self.assert_policy('SOURCE_PROTECTED_ENTRY', sandbox.all_files)

    def test_source_limits(self):
        for name in ('a.txt', 'b.txt'):
            (self.root / name).write_text('content', encoding='utf-8')
        with patch.object(sandbox, 'FILES_LIMIT', 1):
            self.assert_policy('SOURCE_FILE_LIMIT', sandbox.all_files)
            for name in ('c.txt', 'd.txt', 'e.txt'):
                (self.root / name).write_text('content', encoding='utf-8')
            self.assert_policy('SOURCE_FILE_LIMIT', sandbox.all_files)
        with patch.object(sandbox, 'BASELINE_FILE_LIMIT', 1):
            self.assert_policy('SOURCE_CAPTURE_LIMIT', sandbox.snapshot)



    def test_command_output_limit_remains_enforced(self):
        # Windows 夹具替换 Linux 进程组 API；真实短命子进程仍由 run_bounded 等待退出。
        with patch.object(sandbox.os, 'killpg', create=True), patch.object(sandbox.signal, 'SIGKILL', 9, create=True):
            self.assert_policy('COMMAND_BUDGET', lambda: sandbox.run_bounded(
                [sys.executable, '-c', 'print("x"*10000)'], maximum=10, cwd=self.root))

    def test_git_ignore_never_hides_retained_source_and_deletions_are_real(self):
        (self.root / 'counter.cpp').write_text('old')
        (self.root / 'new.go').write_text('new')
        retained = set(sandbox.all_files())
        (self.root / '.gitignore').write_text('*.cpp\n*.go\nbuild/\n')
        (self.root / 'counter.cpp').write_text('changed')
        (self.root / 'build').mkdir()
        (self.root / 'build/generated').write_text('output')
        (self.root / 'node_modules').mkdir()
        (self.root / 'node_modules/package.json').write_text('{}')
        with patch.object(sandbox, 'BASELINE_PATHS', retained):
            paths = sandbox.all_files()
            self.assertIn('counter.cpp', paths)
            self.assertIn('new.go', paths)
            self.assertNotIn('build/generated', paths)
            self.assertNotIn('node_modules/package.json', paths)
            (self.root / 'counter.cpp').unlink()
            self.assertNotIn('counter.cpp', sandbox.all_files())

    def test_new_source_has_no_language_extension_gate(self):
        for name in ('main.cpp', 'main.go', 'build.zig', 'Makefile', 'script.sh'):
            (self.root / name).write_text('source')
        self.assertEqual(5, len(sandbox.snapshot()['files']))


if __name__ == '__main__':
    unittest.main()
