"""直接测试工具协议；临时目录与标准库替身，不启动 Docker、模型或 Agent Run。"""
import importlib.util
import io
import json
import pathlib
import re
import subprocess
import sys
import tempfile
import tracemalloc
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
    def test_listing_paginates_ordered_metadata_without_reading_files(self):
        (self.root / 'src').mkdir()
        for path, raw in [('src/c', b''), ('src/b', '中'.encode()), ('a', b'\x00\xff')]:
            (self.root / path).write_bytes(raw)
        with patch.object(pathlib.Path, 'open', side_effect=AssertionError('listing must only stat')):
            page = sandbox.dispatch('list_files', {'limit': 2})
            last = sandbox.dispatch('list_files', {'offset': page['nextOffset'], 'limit': 2})
        self.assertEqual({'files': [{'path': 'a', 'sizeBytes': 2}, {'path': 'src/b', 'sizeBytes': 3}],
                          'total': 3, 'directories': ['src'], 'nextOffset': 2}, page)
        self.assertEqual({'files': [{'path': 'src/c', 'sizeBytes': 0}],
                          'total': 3, 'directories': ['src'], 'nextOffset': None}, last)

    def test_ranges_preserve_line_endings_and_utf8_across_chunks(self):
        lines = ['中😀\r\n', '\n', 'a\r', '\r\n', 'last']
        path = self.root / 'mixed'
        path.write_bytes(''.join(lines).encode())
        with patch.object(sandbox, 'READ_CHUNK', 2):
            for start in range(1, len(lines) + 3):
                expected = lines[start - 1:start + 1]
                result = sandbox.dispatch('read_file', {'path': 'mixed', 'start_line': start, 'line_count': 2})
                more = start - 1 + len(expected) < len(lines)
                self.assertEqual({'path': 'mixed', 'startLine': start, 'lineCount': len(expected),
                                  'content': ''.join(expected), 'hasMore': more,
                                  'nextStartLine': start + len(expected) if more else None}, result)
        path.write_bytes(b'')
        result = sandbox.dispatch('read_file', {'path': 'mixed'})
        self.assertEqual((0, '', False, None), tuple(result[k] for k in ('lineCount', 'content', 'hasMore', 'nextStartLine')))

    def tracked_stream(self, raw):
        sizes = []

        class Stream(io.BytesIO):
            def read(self, size=-1):
                self_case.assertEqual(sandbox.READ_CHUNK, size)
                sizes.append(size)
                return super().read(size)

            def readline(self, *args):
                raise AssertionError('must not buffer a whole line')

        self_case = self
        return patch.object(pathlib.Path, 'open', return_value=Stream(raw)), sizes

    def test_large_skipped_and_lookahead_lines_use_bounded_reads(self):
        long = b'x' * (1024 * 1024)
        raw = long + b'\r\nwanted\n' + long
        path = self.root / 'large'
        path.write_bytes(raw)
        for start, expected, next_line, max_reads in [(1, None, None, 2), (2, 'wanted\n', 3, 130)]:
            opened, sizes = self.tracked_stream(raw)
            with opened:
                result = sandbox.dispatch('read_file', {'path': 'large', 'start_line': start, 'line_count': 1}, 1024)
            if expected is None:
                self.assertEqual('OUTPUT_LIMIT', result['classification'])
            else:
                self.assertEqual(expected, result['content'])
                self.assertEqual(next_line, result['nextStartLine'])
                self.assertTrue(result['hasMore'])
            self.assertTrue(0 < len(sizes) <= max_reads, sizes)
        path.write_bytes(b'ok\n' + long)
        self.assertEqual(2, sandbox.dispatch('read_file', {'path': 'large'}, 1024)['nextStartLine'])

    def test_exact_serialized_budget_and_repeated_continuations(self):
        line = '"\\\t中😀\x01' * 20 + '\r\n'
        path = self.root / 'quoted'
        path.write_bytes((line * 7).encode())
        expected = {'path': 'quoted', 'startLine': 1, 'lineCount': 3, 'content': line * 3,
                    'hasMore': True, 'nextStartLine': 4}
        wire = {'ok': True, 'tool': 'read_file', 'result': {**expected, 'publicResource': False}}
        budget = len(json.dumps(wire, ensure_ascii=False, separators=(',', ':')).encode())
        self.assertEqual(expected, sandbox.dispatch('read_file', {'path': 'quoted'}, budget))
        self.assertEqual(2, sandbox.dispatch('read_file', {'path': 'quoted'}, budget - 1)['lineCount'])
        start = 1
        content = ''
        while start is not None:
            result = sandbox.dispatch('read_file', {'path': 'quoted', 'start_line': start}, budget)
            content += result['content']
            start = result['nextStartLine']
        self.assertEqual(line * 7, content)

    def test_read_rejects_binary_invalid_and_incomplete_utf8(self):
        (self.root / 'directory').mkdir()
        with patch.object(pathlib.Path, 'open', side_effect=AssertionError('must only open regular files')):
            with self.assertRaises(ValueError):
                sandbox.dispatch('read_file', {'path': 'directory'})
        for raw in (b'one\x00', b'\xff', b'one\xe2\x82'):
            (self.root / 'bad').write_bytes(raw)
            with self.assertRaises(ValueError):
                sandbox.dispatch('read_file', {'path': 'bad'})

    def test_final_page_can_fit_when_a_large_continuation_number_would_overflow(self):
        start = 1000000
        content = 'y' * 1000 + '\na'
        path = self.root / 'a'
        path.write_bytes(b'\n' * (start - 1) + content.encode())
        expected = dict(path='a', startLine=start, lineCount=2, content=content, hasMore=False, nextStartLine=None)
        wire = {'ok': True, 'tool': 'read_file', 'result': dict(expected, publicResource=False)}
        budget = len(json.dumps(wire, separators=(',', ':')).encode())
        self.assertEqual(expected, sandbox.dispatch('read_file', {'path': 'a', 'start_line': start}, budget))
        self.assertEqual('OUTPUT_LIMIT', sandbox.dispatch(
            'read_file', {'path': 'a', 'start_line': start, 'line_count': 1}, budget)['classification'])

    def test_search_large_line_boundary_and_eof_matches(self):
        path = self.root / 'src'
        path.mkdir()
        prefix = b'short\r\n' + b'x' * (sandbox.READ_CHUNK * 64 - 8)
        raw = prefix + '中Needle'.encode() + b'\rend Needle'
        (path / 'large').write_bytes(raw)
        (self.root / 'excluded').write_bytes(b'Needle')
        opened, sizes = self.tracked_stream(raw)
        with opened:
            result = sandbox.dispatch('search_code', {'path': 'src', 'query': '中Needle'})
        self.assertGreater(len(sizes), 64)
        self.assertEqual({'matches': [{'path': 'src/large', 'line': 2, 'excerpt': 'x' * 400}],
                          'truncated': False}, result)
        self.assertEqual([2, 3], [m['line'] for m in sandbox.dispatch(
            'search_code', {'path': 'src', 'query': 'Needle'})['matches']])
        self.assertFalse(sandbox.dispatch('search_code', {'query': 'needle'})['matches'])
        (path / 'boundary').write_bytes(b'x' * (sandbox.READ_CHUNK - 2) + b'Needle')
        self.assertEqual(1, sandbox.dispatch('search_code', {'path': 'src/boundary', 'query': 'Needle'})['matches'][0]['line'])

    def test_search_validates_after_40_candidates_and_stops_after_valid_limit(self):
        for suffix in (b'\xff', b'\x00', b'\xe2\x82'):
            (self.root / 'a-invalid').write_bytes(b'match\n' * 40 + b'x' * 300000 + suffix)
            (self.root / 'b-valid').write_bytes(b'match\n' * 40)
            (self.root / 'c-unused').write_bytes(b'match')
            opened = []
            original = pathlib.Path.open

            def track(path, *args, **kwargs):
                opened.append(path.name)
                return original(path, *args, **kwargs)

            with patch.object(pathlib.Path, 'open', track):
                result = sandbox.dispatch('search_code', {'query': 'match'})
            self.assertEqual(['a-invalid', 'b-valid'], opened)
            self.assertTrue(result['truncated'])
            self.assertEqual([{'path': 'b-valid', 'line': n, 'excerpt': 'match'}
                              for n in range(1, 41)], result['matches'])

    def test_long_skipped_and_matched_lines_are_never_accumulated(self):
        path = self.root / 'long'
        with path.open('wb') as stream:
            stream.write(b'match')
            for _ in range(512):
                stream.write(b'x' * 8192)
            stream.write(b'\nnext')
        with patch.object(sandbox, 'all_files', return_value=['long']):
            tracemalloc.start()
            try:
                read = sandbox.dispatch('read_file', {'path': 'long', 'start_line': 2})
                search = sandbox.dispatch('search_code', {'query': 'match'})
                peak = tracemalloc.get_traced_memory()[1]
            finally:
                tracemalloc.stop()
        self.assertEqual('next', read['content'])
        self.assertEqual('match' + 'x' * 395, search['matches'][0]['excerpt'])
        self.assertLess(peak, 1024 * 1024, '4 MiB line accumulated by a streaming tool')

    def test_selected_config_and_key_names_are_listed_and_read_without_content_filtering(self):
        paths = ['.env', '.env.example', '.npmrc', 'fixture.key', 'id_rsa', 'application.properties']
        for path in paths:
            (self.root / path).write_bytes(b'token=synthetic-default\n')
        listed = sandbox.dispatch('list_files', {})['files']
        for path in paths:
            self.assertIn({'path': path, 'sizeBytes': 24}, listed)
            self.assertEqual('token=synthetic-default\n', sandbox.dispatch('read_file', {'path': path})['content'])

    def test_invalid_read_range_never_reads_content_and_can_be_corrected(self):
        target = self.root / 'source.txt'
        target.write_bytes(b'one\ntwo\n')
        with patch.object(pathlib.Path, 'open', side_effect=AssertionError('must not read')):
            for values in ({'start_line': 0}, {'line_count': 301}, {'start_line': True}):
                with self.subTest(values=values):
                    result = sandbox.dispatch('read_file', {'path': 'source.txt', **values})
                    self.assert_read_error(result, 'INVALID_RANGE')
        self.assertEqual('one\ntwo\n', sandbox.dispatch('read_file', {'path': 'source.txt'})['content'])

    def test_missing_sources_are_recoverable_without_read_or_enumeration(self):
        with patch.object(pathlib.Path, 'open', side_effect=AssertionError('must not read')), \
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
        with patch.object(pathlib.Path, 'open', side_effect=AssertionError('must not read')):
            for path in ('../outside', '/etc/passwd', '.ssh/config', 'link/outside'):
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
            'protocol': 'delveforge-sandbox', 'version': 11}},
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
        request = {'name': 'apply_patch', 'arguments': {'patch': '--- a/.ssh/config\n+++ b/.ssh/config\nprivate-patch-body'}}
        result = subprocess.run([sys.executable, str(SOURCE)], input=json.dumps(request).encode(),
                                capture_output=True, cwd=self.root, check=False)
        self.assertEqual(0, result.returncode)
        data = json.loads(result.stdout)
        self.assertEqual('POLICY_REJECTED', data['classification'])
        self.assertEqual('FORBIDDEN_PATH', data['policyRule'])
        self.assertNotIn('.ssh/config', result.stdout.decode())
        self.assertNotIn('private-patch-body', result.stdout.decode())
        self.assertNotIn('reason', data)

    def assert_policy(self, rule, operation):
        with self.assertRaises(sandbox.PolicyError) as rejected:
            operation()
        self.assertEqual(rule, rejected.exception.rule)
        return rejected.exception

    def test_path_restrictions(self):
        for value, rule in [(None, 'PATH_FORMAT'), ('x\\y', 'PATH_FORMAT'), ('x'*513, 'PATH_FORMAT'),
                            ('.ssh/config', 'FORBIDDEN_PATH'), ('../host', 'FORBIDDEN_PATH'),
                            ('/host', 'FORBIDDEN_PATH'), ('.git/config', 'FORBIDDEN_PATH'), ('CON.txt', 'FORBIDDEN_PATH')]:
            with self.subTest(rule=rule):
                self.assert_policy(rule, lambda: sandbox.path_for(value))
        with patch.object(pathlib.Path, 'is_symlink', return_value=True):
            self.assert_policy('SYMLINK_PATH', lambda: sandbox.path_for('safe.txt'))

    def test_forbidden_patch_metadata_is_rejected_before_execution(self):
        cases = [('GIT binary patch\n', 'PATCH_TYPE'), ('rename from old\n', 'PATCH_TYPE'),
                 ('copy to next\n', 'PATCH_TYPE'), ('new file mode 120000\n', 'PATCH_TYPE'),
                 ('diff --git wrong wrong\n', 'PATCH_DIFF_PATHS'), ('new mode 100600\n', 'PATCH_FILE_MODE'),
                 ('--- plain.txt\n', 'PATCH_PATH_FORMAT'), ('--- a/.ssh/config\n', 'FORBIDDEN_PATH'),
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
                         '--- a/a.txt\n+++ b/a.txt\n@@ -9,100 +9,100 @@\n--- a/.ssh/config\n+++ b/.ssh/config\n']:
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
