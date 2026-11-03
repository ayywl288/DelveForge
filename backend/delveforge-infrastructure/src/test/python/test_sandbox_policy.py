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
    def test_frontend_generated_names_do_not_hide_or_forbid_maven_source(self):
        path = self.root / 'src/main/java/dist/Counter.java'
        path.parent.mkdir(parents=True)
        path.write_text('class Counter {}\n')
        diff = '--- a/src/main/java/dist/Counter.java\n+++ b/src/main/java/dist/Counter.java\n@@ -1 +1 @@\n-class Counter {}\n+class Counter { int n; }\n'
        self.assertEqual(['src/main/java/dist/Counter.java'], sandbox.patch_paths(diff))
        (self.root / 'package.json').write_text('{}')
        (self.root / 'dist').mkdir()
        (self.root / 'dist/output.js').write_text('generated')
        self.assert_policy('GENERATED_PATH', sandbox.snapshot)

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

    def test_declared_target_module_is_source_and_its_own_target_is_generated(self):
        (self.root / 'pom.xml').write_text('<project><modules><module>target</module></modules></project>')
        (self.root / 'target').mkdir()
        (self.root / 'target/pom.xml').write_text('<project/>')
        (self.root / 'target/Source.java').write_text('class Source {}')
        self.assertFalse(sandbox.build_output('target/Source.java'))
        self.assertTrue(sandbox.build_output('target/target/classes/Source.class'))

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
            'protocol': 'delveforge-sandbox', 'version': 8}},
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
        with patch.object(sandbox, 'BASELINE_FILE_LIMIT', 1):
            self.assert_policy('SOURCE_CAPTURE_LIMIT', sandbox.snapshot)

    def test_explicit_new_maven_check_excludes_only_outputs_and_retains_baseline_assets(self):
        (self.root / 'pom.xml').write_text('<project/>')
        child = self.root / 'independent'
        (child / 'target').mkdir(parents=True)
        (child / 'pom.xml').write_text('<project/>')
        asset = child / 'target/business.json'
        asset.write_text('retained baseline resource')
        baseline = {entry['path']: entry['sha256'] + ':' + entry['mode'] for entry in sandbox.snapshot()['files']}

        def build(*args, **kwargs):
            (child / 'target/classes').mkdir(exist_ok=True)
            (child / 'target/classes/Generated.class').write_bytes(b'\x00\xff')
            return 0, 'build completed'

        with patch.object(sandbox, 'BASELINE_PATHS', set(baseline)), patch.object(sandbox, 'run_bounded', build):
            result = sandbox.checks({'directory': 'independent'})
            self.assertEqual('BUILD_PASSED_NOT_TESTED', result['classification'])
            final = sandbox.snapshot(True, baseline, sandbox.maven_module_roots({'independent'}))
            self.assertIn('independent/target/business.json', {entry['path'] for entry in final['files']})
            self.assertNotIn('independent/target/classes/Generated.class', {entry['path'] for entry in final['files']})

            def mutate(*args, **kwargs):
                asset.write_text('unexpected source mutation')
                return 0, 'build completed'

            with patch.object(sandbox, 'run_bounded', mutate):
                self.assert_policy('CHECK_MUTATED_SOURCE', lambda: sandbox.checks({'directory': 'independent'}))

    def test_recognized_database_runtime_change_is_explicit_and_bound_to_initial_snapshot(self):
        (self.root / 'pom.xml').write_text('<project/>')
        database = self.root / 'fixture.mv.db'
        header = b'H:2,block:2,blockSize:1000,chunk:1,format:3,fletcher:1234\n\x00'
        database.write_bytes(header + b'before')
        before = sandbox.snapshot()

        def build(*args, **kwargs):
            database.write_bytes(header + b'after')
            return 0, 'build completed'

        with patch.dict(sandbox.os.environ, {'DF_CHECK_COPY': 'true'}), \
                patch.object(sandbox, 'run_bounded', build):
            result = sandbox.checks({'goal': 'test'})
        self.assertEqual(before['snapshot'], result['snapshot'])
        self.assertEqual('BUILD_PASSED_NOT_TESTED', result['classification'])
        change = result['runtimeChanges'][0]
        self.assertEqual('fixture.mv.db', change['path'])
        self.assertEqual('H2_MVSTORE_CONTENT', change['category'])
        self.assertNotEqual(change['before']['sha256'], change['after']['sha256'])
        self.assertNotIn('content', change['before'])

    def h2_trace_fixture(self):
        (self.root / 'pom.xml').write_text('<project/>')
        (self.root / 'fixture.mv.db').write_bytes(b'H:2,block:2,blockSize:1000,chunk:1,format:3,fletcher:1234\n\x00')
        trace = self.root / 'fixture.trace.db'
        trace.write_bytes(b'2026-10-27 00:00:00.123456+08:00 jdbc[3]: exception\norg.h2.jdbc.FixtureException\n')
        return trace

    def test_h2_trace_header_accepts_actual_timezone_formats(self):
        for timestamp in ['2026-10-27 00:00:00', '2026-10-27 00:00:00.123456+08:00',
                          '2026-10-27 00:00:00.123456789-05:30', '2026-10-27 00:00:00Z']:
            with self.subTest(timestamp=timestamp):
                self.assertIsNotNone(sandbox.H2_TRACE_HEADER.match((timestamp + ' jdbc[3]: exception').encode()))
        self.assertIsNone(sandbox.H2_TRACE_HEADER.match(b'2026-10-27 00:00:00+wrong jdbc[3]: exception'))

    def test_existing_h2_trace_append_is_recorded_without_exporting_log_contents(self):
        trace = self.h2_trace_fixture()
        before = sandbox.snapshot()
        def build(*args, **kwargs):
            with trace.open('ab') as output:
                output.write(b'2026-10-27 00:00:01.123456789+00:00 jdbc[3]: exception\nprivate-sql-and-user-data\n')
            return 0, 'build completed'
        with patch.dict(sandbox.os.environ, {'DF_CHECK_COPY': 'true'}), patch.object(sandbox, 'run_bounded', build):
            result = sandbox.checks({})
        self.assertEqual(before['snapshot'], result['snapshot'])
        self.assertEqual('H2_TRACE_APPEND', result['runtimeChanges'][0]['category'])
        self.assertNotIn('private-sql', json.dumps(result))
        self.assertNotIn('content', result['runtimeChanges'][0]['after'])

    def test_trace_allowance_requires_recognized_pair_copy_mode_and_append_only(self):
        trace = self.h2_trace_fixture()
        original = trace.read_bytes()
        mutations = [lambda: trace.write_bytes(b'2026-10-27 00:00:01 jdbc[3]: overwritten\n'),
                     lambda: trace.write_bytes(original + b'arbitrary-source-rewrite\n'),
                     trace.unlink]
        for mutation in mutations:
            trace.write_bytes(original)
            def build(*args, **kwargs):
                mutation()
                return 0, 'build completed'
            with patch.dict(sandbox.os.environ, {'DF_CHECK_COPY': 'true'}), patch.object(sandbox, 'run_bounded', build):
                self.assert_policy('CHECK_MUTATED_SOURCE', lambda: sandbox.checks({}))
        for copy, companion, header in [('false', True, original), ('true', False, original),
                                        ('true', True, b'ordinary-source-file\n')]:
            trace.write_bytes(header)
            (self.root / 'fixture.mv.db').write_bytes(b'H:2,block:2,blockSize:1000,chunk:1,format:3,fletcher:1234\n\x00' if companion else b'not-h2')
            def append(*args, **kwargs):
                with trace.open('ab') as output: output.write(b'2026-10-27 00:00:02 jdbc[3]: append\n')
                return 0, 'build completed'
            with patch.dict(sandbox.os.environ, {'DF_CHECK_COPY': copy}), patch.object(sandbox, 'run_bounded', append):
                self.assert_policy('CHECK_MUTATED_SOURCE', lambda: sandbox.checks({}))

    def test_trace_allowance_preserves_new_file_mode_and_source_change_rejection(self):
        trace = self.h2_trace_fixture()
        before = sandbox.snapshot()
        traces = sandbox.h2_runtime_traces(before, {'fixture.mv.db'})
        with trace.open('ab') as output: output.write(b'2026-10-27 00:00:02 jdbc[3]: append\n')
        after = sandbox.snapshot()
        changed_mode = {'files': [dict(item, mode='100755') if item['path'] == 'fixture.trace.db' else item for item in after['files']]}
        self.assert_policy('CHECK_MUTATED_SOURCE', lambda: sandbox.check_runtime_changes(before, changed_mode, {'fixture.mv.db'}, traces))
        (self.root / 'new.trace.db').write_bytes(trace.read_bytes())
        self.assert_policy('CHECK_MUTATED_SOURCE', lambda: sandbox.check_runtime_changes(before, sandbox.snapshot(), {'fixture.mv.db'}, traces))
        (self.root / 'new.trace.db').unlink()
        (self.root / 'Source.java').write_text('class Unexpected {}')
        self.assert_policy('CHECK_MUTATED_SOURCE', lambda: sandbox.check_runtime_changes(before, sandbox.snapshot(), {'fixture.mv.db'}, traces))

    def test_database_exception_never_accepts_other_integrity_changes(self):
        (self.root / 'pom.xml').write_text('<project/>')
        header = b'H:2,block:2,blockSize:1000,chunk:1,format:3,fletcher:1234\n\x00'
        for name, before, after in [('Counter.java', b'class A {}', b'class B {}'),
                                    ('application.properties', b'a=1', b'a=2'),
                                    ('library.jar', b'\x00old', b'\x00new'),
                                    ('fake.mv.db', b'arbitrary bytes', b'changed')]:
            with self.subTest(name=name):
                path = self.root / name
                path.write_bytes(before)
                def mutate(*args, **kwargs):
                    path.write_bytes(after)
                    return 0, 'build completed'
                with patch.dict(sandbox.os.environ, {'DF_CHECK_COPY': 'true'}), \
                        patch.object(sandbox, 'run_bounded', mutate):
                    self.assert_policy('CHECK_MUTATED_SOURCE', lambda: sandbox.checks({}))
                path.unlink()
        database = self.root / 'fixture.mv.db'
        database.write_bytes(header + b'original')
        def delete(*args, **kwargs):
            database.unlink()
            return 0, 'build completed'
        with patch.dict(sandbox.os.environ, {'DF_CHECK_COPY': 'true'}), \
                patch.object(sandbox, 'run_bounded', delete):
            self.assert_policy('CHECK_MUTATED_SOURCE', lambda: sandbox.checks({}))

    def test_database_allowance_requires_disposable_copy_and_preserves_mode_and_path(self):
        (self.root / 'pom.xml').write_text('<project/>')
        database = self.root / 'fixture.mv.db'
        header = b'H:2,block:2,blockSize:1000,chunk:1,format:3,fletcher:1234\n\x00'
        database.write_bytes(header + b'original')
        def mutate(*args, **kwargs):
            database.write_bytes(header + b'changed')
            return 0, 'build completed'
        with patch.dict(sandbox.os.environ, {'DF_CHECK_COPY': 'false'}), \
                patch.object(sandbox, 'run_bounded', mutate):
            self.assert_policy('CHECK_MUTATED_SOURCE', lambda: sandbox.checks({}))
        before = sandbox.snapshot()
        entry = next(item for item in before['files'] if item['path'] == 'fixture.mv.db')
        after = {'files': [dict(item, mode='100755') if item == entry else item for item in before['files']]}
        self.assert_policy('CHECK_MUTATED_SOURCE', lambda: sandbox.check_runtime_changes(before, after, {'fixture.mv.db'}))
        after = {'files': before['files'] + [dict(entry, path='new.mv.db')]}
        self.assert_policy('CHECK_MUTATED_SOURCE', lambda: sandbox.check_runtime_changes(before, after, {'fixture.mv.db'}))

    def test_source_rejection_records_paths_types_and_hashes_without_source_contents(self):
        (self.root / 'pom.xml').write_text('<project/>')
        for name in ['Counter.java', 'application.properties', 'data.sql']:
            (self.root / name).write_text('synthetic-private-before')
        before = sandbox.snapshot()
        def mutate(*args, **kwargs):
            for name in ['Counter.java', 'application.properties', 'data.sql']:
                (self.root / name).write_text('synthetic-private-after')
            return 0, 'build completed'
        with patch.object(sandbox, 'run_bounded', mutate):
            with self.assertRaises(sandbox.PolicyError) as captured:
                sandbox.checks({})
        self.assertEqual('CHECK_MUTATED_SOURCE', captured.exception.rule)
        diagnostic = captured.exception.source_changes
        self.assertEqual(before['snapshot'], diagnostic['beforeSnapshot'])
        self.assertEqual(3, diagnostic['totalChanges'])
        self.assertEqual(3, diagnostic['unexpectedChanges'])
        self.assertEqual(['Counter.java', 'application.properties', 'data.sql'],
                         [entry['path'] for entry in diagnostic['files']])
        self.assertTrue(all(entry['changeType'] == 'CONTENT' for entry in diagnostic['files']))
        self.assertNotIn('synthetic-private', json.dumps(diagnostic))
        self.assertNotIn('content', diagnostic['files'][0]['before'])
        self.assertRegex(diagnostic['files'][0]['after']['sha256'], r'^[0-9a-f]{64}$')

    def test_source_change_diagnostics_are_bounded_and_classify_add_delete_and_mode(self):
        (self.root / 'pom.xml').write_text('<project/>')
        for index in range(12):
            (self.root / ('File%02d.java' % index)).write_text('original')
        def mutate(*args, **kwargs):
            for index in range(12):
                (self.root / ('File%02d.java' % index)).write_text('changed')
            return 0, 'build completed'
        with patch.object(sandbox, 'run_bounded', mutate):
            with self.assertRaises(sandbox.PolicyError) as captured:
                sandbox.checks({})
        self.assertEqual(12, captured.exception.source_changes['totalChanges'])
        self.assertEqual(8, len(captured.exception.source_changes['files']))
        self.assertTrue(captured.exception.source_changes['truncated'])
        state = {'mode': '100644', 'sha256': 'a' * 64}
        before = {'snapshot': 'a' * 64, 'files': [dict(state, path='Deleted.java'), dict(state, path='Mode.java')]}
        after = {'snapshot': 'b' * 64, 'files': [dict(state, path='Added.java'), dict(state, path='Mode.java', mode='100755')]}
        with self.assertRaises(sandbox.PolicyError) as captured:
            sandbox.check_runtime_changes(before, after, set())
        self.assertEqual(['ADDED', 'DELETED', 'MODE'],
                         [entry['changeType'] for entry in captured.exception.source_changes['files']])

    def test_static_report_fixture_is_preserved_and_never_counted_as_execution(self):
        (self.root / 'pom.xml').write_text('<project/>')
        fixture = self.root / 'src/test/resources/template/target/surefire-reports/TEST-fixture.xml'
        fixture.parent.mkdir(parents=True)
        original = '<testsuite tests="9" failures="0" errors="0" skipped="0"/>'
        fixture.write_text(original)
        paths = {entry['path'] for entry in sandbox.snapshot()['files']}
        with patch.object(sandbox, 'BASELINE_PATHS', paths), patch.object(sandbox, 'run_bounded', return_value=(0, 'BUILD SUCCESS')):
            result = sandbox.checks({'goal': 'test'})
        self.assertEqual('BUILD_PASSED_NOT_TESTED', result['classification'])
        self.assertEqual(0, result['executedTests'])
        self.assertEqual(original, fixture.read_text())
        self.assertEqual(0, sandbox.report()['reports'])

    def test_case_conflicts(self):
        with patch.object(sandbox.os, 'walk', return_value=[(str(self.root), [], ['a.txt', 'A.txt'])]), \
                patch.object(pathlib.Path, 'is_file', return_value=True), \
                patch.object(sandbox, 'maven_module_roots', return_value=set()):
            self.assert_policy('SOURCE_CASE_CONFLICT', sandbox.all_files)

    def test_command_output_limit_remains_enforced(self):
        # Windows 夹具替换 Linux 进程组 API；真实短命子进程仍由 run_bounded 等待退出。
        with patch.object(sandbox.os, 'killpg', create=True), patch.object(sandbox.signal, 'SIGKILL', 9, create=True):
            self.assert_policy('COMMAND_BUDGET', lambda: sandbox.run_bounded(
                [sys.executable, '-c', 'print("x"*10000)'], maximum=10, cwd=self.root))


if __name__ == '__main__':
    unittest.main()
