"""前端检查协议、冻结元数据和副本完整性；真实工具执行另由 FullStackAgentSmokeTest 覆盖。"""
import importlib.util
import base64
import json
import os
import pathlib
import sys
import tempfile
import unittest
from unittest.mock import patch

SOURCE = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/agent'
sys.path.insert(0, str(SOURCE))
import frontend
spec = importlib.util.spec_from_file_location('frontend_sandbox', SOURCE / 'sandbox.py')
sandbox = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = sandbox
spec.loader.exec_module(sandbox)


class FrontendPolicyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name) / 'source'
        self.root.mkdir()
        self.project = self.root / 'frontend'
        self.project.mkdir()
        (self.project / 'source.ts').write_text('export const value = 1;')
        self.deps = pathlib.Path(self.temporary.name) / 'dependencies'
        binaries = self.deps / 'node_modules/.bin'
        binaries.mkdir(parents=True)
        for tool in ('vue-tsc', 'vite', 'vitest'):
            (binaries / tool).write_text('fixture executable')
        self.prepare_dir = pathlib.Path(self.temporary.name) / 'preparation'
        for target, attribute, value in ((sandbox, 'ROOT', self.root),
                (sandbox, 'FRONTEND_OUTPUT_ROOT', None), (frontend, 'DEPENDENCIES', self.deps),
                (frontend, 'PREPARATION', self.prepare_dir)):
            active = patch.object(target, attribute, value)
            active.start()
            self.addCleanup(active.stop)
        env = patch.dict(os.environ, {'DF_FRONTEND_COPY': 'true'})
        env.start()
        self.addCleanup(env.stop)

    def project_metadata(self, manager='pnpm'):
        manifest = {'name': 'fixture', 'version': '1.0.0', 'packageManager':
                    manager + '@' + (frontend.PNPM_VERSION if manager == 'pnpm' else frontend.NPM_VERSION),
                    'dependencies': {'vue': '3.5.42'},
                    'devDependencies': {name: '1.0.0' for name in ('vite', 'vitest', 'typescript', 'vue-tsc')},
                    'scripts': {'typecheck': 'vue-tsc --noEmit', 'build': 'vite build', 'test': 'vitest run',
                                'postinstall': 'echo MUST_NOT_RUN'}}
        (self.project / 'package.json').write_text(json.dumps(manifest))
        integrity = 'sha512-' + 'A' * 86 + '=='
        if manager == 'pnpm':
            (self.project / 'pnpm-lock.yaml').write_text('lockfileVersion: "9.0"\nimporters: {".": {}}\n'
                + 'packages: {"vue@3.5.42": {resolution: {integrity: "' + integrity + '"}}}\n')
        else:
            (self.project / 'package-lock.json').write_text(json.dumps({'lockfileVersion': 3, 'packages': {
                '': {field: manifest[field] for field in ('dependencies', 'devDependencies')},
                'node_modules/vue': {'resolved': 'https://registry.npmjs.org/vue/-/vue-3.5.42.tgz',
                                     'integrity': integrity}}}))
        return manifest

    def fake_run(self, command, **kwargs):
        if command == ['node', '--version']:
            return 0, frontend.NODE_VERSION
        if command == ['pnpm', '--version']:
            return 0, frontend.PNPM_VERSION
        if command == ['npm', '--version']:
            return 0, frontend.NPM_VERSION
        return 0, ''

    def test_both_managers_are_detected_and_metadata_must_match_unique_lock(self):
        for manager in ('npm', 'pnpm'):
            with self.subTest(manager=manager):
                for name in ('package-lock.json', 'pnpm-lock.yaml'):
                    (self.project / name).unlink(missing_ok=True)
                manifest = self.project_metadata(manager)
                self.assertEqual(manager, frontend.metadata(sandbox, {'goal': 'build'})[-2])
                del manifest['packageManager']
                (self.project / 'package.json').write_text(json.dumps(manifest))
                self.assertEqual(manager, frontend.metadata(sandbox, {'goal': 'build'})[-2])
                for declared in (None, 'yarn@1.0.0', 'pnpm@8.15.0', 'npm@12.0.0', manager + '@^10.0.0',
                                 'pnpm@10.0.0' if manager == 'npm' else 'npm@10.0.0'):
                    manifest['packageManager'] = declared
                    (self.project / 'package.json').write_text(json.dumps(manifest))
                    with self.assertRaises(frontend.Unsupported):
                        frontend.metadata(sandbox, {'goal': 'build'})
        self.project_metadata('pnpm')
        (self.project / 'package-lock.json').write_text('{}')
        with self.assertRaises(frontend.Unsupported):
            frontend.metadata(sandbox, {'goal': 'build'})

    def test_capabilities_discover_root_nested_and_maven_without_inventing_checks(self):
        self.assertEqual([], frontend.describe(sandbox)['frontendProjects'])
        fixture = self.root / 'src/test/resources/example/pom.xml'
        fixture.parent.mkdir(parents=True)
        fixture.write_text('<project/>')
        self.assertEqual([], frontend.describe(sandbox)['mavenDirectories'])
        (self.root / 'pom.xml').write_text('<project/>')
        self.assertEqual(['.'], frontend.describe(sandbox)['mavenDirectories'])
        self.project_metadata()
        described = frontend.describe(sandbox)
        self.assertEqual('frontend', described['frontendProjects'][0]['directory'])
        self.assertEqual('DECLARED_SUPPORTED', described['frontendProjects'][0]['goals']['build'])
        self.assertEqual('frontend', frontend.metadata(sandbox, {'goal': 'build'})[1])
        with self.assertRaises(frontend.CheckUnavailable):
            frontend.metadata(sandbox, {'directory': 'missing', 'goal': 'test'})
        for path in list(self.project.iterdir()):
            if path.is_file() and path.name != 'source.ts':
                path.rename(self.root / path.name)
        self.assertEqual('.', frontend.metadata(sandbox, {'goal': 'build'})[1])
        self.project_metadata()
        with self.assertRaises(frontend.CheckUnavailable):
            frontend.metadata(sandbox, {'goal': 'build'})
        self.assertEqual(2, frontend.describe(sandbox)['frontendProjectCount'])

    def test_scripts_are_project_code_not_a_security_syntax_whitelist(self):
        manifest = self.project_metadata()
        manifest['devDependencies'].pop('vitest')
        scripts = ('node --test tests/', 'npm run unit && node scripts/summary.mjs',
                   'pnpm run nested', 'NODE_OPTIONS=--max_old_space_size=2048 vite build',
                   'node scripts/verify.mjs; echo done', 'vitest --environment jsdom')
        for script in scripts:
            manifest['scripts']['test'] = script
            (self.project / 'package.json').write_text(json.dumps(manifest))
            self.assertEqual('test', frontend.metadata(sandbox, {'goal': 'test'})[3])
        with patch.object(sandbox, 'run_bounded', return_value=(0, '')) as run:
            result = frontend.check(sandbox, {'goal': 'test'})
        self.assertEqual(['pnpm', 'run', 'test'], run.call_args.args[0])
        self.assertEqual('TEST_EXECUTION_UNVERIFIED', result['classification'])
        self.assertIn('--require=', run.call_args.kwargs['env']['NODE_OPTIONS'])
        self.assertEqual('false', run.call_args.kwargs['env']['pnpm_config_verify_deps_before_run'])

    def test_integrity_requires_valid_complete_digest_for_each_supported_algorithm(self):
        for algorithm, size in (('sha1', 20), ('sha256', 32), ('sha384', 48), ('sha512', 64)):
            value = algorithm + '-' + base64.b64encode(bytes(range(size))).decode()
            self.assertTrue(frontend.registry_integrity(value))
            self.assertFalse(frontend.registry_integrity(value[:-3]))
        for value in ('', 'md5-' + 'A' * 24, 'sha1-' + 'A' * 86 + '==', 'sha512-not-a-digest',
                      'sha1-' + 'A' * 27 + '= sha512-' + 'A' * 86 + '=='):
            self.assertFalse(frontend.registry_integrity(value))

    def test_single_project_build_allowances_never_enable_installation_scripts_or_workspaces(self):
        manifest = self.project_metadata()
        manifest['devDependencies'].pop('typescript')
        (self.project / 'package.json').write_text(json.dumps(manifest))
        config = self.project / 'pnpm-workspace.yaml'
        config.write_text('packages: ["."]\nallowBuilds: {esbuild: true}\n')
        with patch.object(sandbox, 'run_bounded', side_effect=self.fake_run) as run:
            self.assertEqual('DEPENDENCIES_PREPARED', frontend.prepare(sandbox, {'goal': 'build'})['classification'])
        self.assertIn('--ignore-scripts', run.call_args.args[0])
        self.assertEqual({'packages': ['.']}, frontend.root_only_configuration(self.prepare_dir / 'pnpm-workspace.yaml'))
        config.write_text('packages: ["packages/*"]')
        self.assertEqual({'packages': ['packages/*']}, frontend.root_only_configuration(config))
        for raw in ('packages: ["../outside"]', 'registry: https://untrusted.invalid',
                    'allowBuilds: {esbuild: "echo bad"}', 'allowBuilds: &builds {esbuild: true}'):
            config.write_text(raw)
            with self.assertRaises(frontend.Unsupported):
                frontend.metadata(sandbox, {'goal': 'build'})

    def test_both_installers_strip_lifecycle_and_use_reproducible_installation(self):
        for manager in ('npm', 'pnpm'):
            with self.subTest(manager=manager):
                for name in ('package-lock.json', 'pnpm-lock.yaml'):
                    (self.project / name).unlink(missing_ok=True)
                self.project_metadata(manager)
                if self.prepare_dir.exists():
                    import shutil
                    shutil.rmtree(self.prepare_dir)
                before = sandbox.snapshot()['snapshot']
                with patch.object(sandbox, 'run_bounded', side_effect=self.fake_run) as run:
                    result = frontend.prepare(sandbox, {'goal': 'test'})
                self.assertEqual('DEPENDENCIES_PREPARED', result['classification'])
                argv = run.call_args.args[0]
                self.assertEqual(manager, argv[0])
                self.assertIn('--ignore-scripts', argv)
                self.assertIn('--frozen-lockfile' if manager == 'pnpm' else 'ci', argv)
                if manager == 'pnpm':
                    self.assertIn('--ignore-pnpmfile', argv)
                self.assertNotIn('scripts', json.loads((self.prepare_dir / 'package.json').read_text()))
                self.assertNotIn('packageManager', json.loads((self.prepare_dir / 'package.json').read_text()))
                self.assertEqual(before, sandbox.snapshot()['snapshot'])

    def test_pinned_toolchain_document_is_not_a_project_lock_or_runtime_download_request(self):
        import yaml
        self.project_metadata('pnpm')
        lock = self.project / 'pnpm-lock.yaml'
        project_document = lock.read_text()
        toolchain = {'lockfileVersion': '9.0', 'importers': {'.': {'configDependencies': {},
            'packageManagerDependencies': {'pnpm': {'specifier': frontend.PNPM_VERSION,
                                                   'version': frontend.PNPM_VERSION}}}}, 'packages': {}}
        lock.write_text(yaml.safe_dump(toolchain) + '---\n' + project_document)
        original = lock.read_bytes()
        with patch.object(sandbox, 'run_bounded', side_effect=self.fake_run) as run:
            self.assertEqual('DEPENDENCIES_PREPARED', frontend.prepare(sandbox, {'goal': 'build'})['classification'])
        self.assertIn('--config.manage-package-manager-versions=false', run.call_args.args[0])
        prepared = list(yaml.safe_load_all((self.prepare_dir / 'pnpm-lock.yaml').read_text()))
        self.assertEqual([yaml.safe_load(project_document)], prepared)
        self.assertEqual(original, lock.read_bytes())

    def test_existing_manager_versions_and_optional_checks_do_not_reject_buildable_projects(self):
        for manager, version in (('npm', '9.9.4'), ('npm', '11.6.2'), ('pnpm', '9.11.0'), ('pnpm', '10.13.1')):
            self.reset_outputs()
            manifest = self.project_metadata(manager)
            manifest['packageManager'] = manager + '@' + version
            del manifest['devDependencies']['vitest']
            del manifest['devDependencies']['vue-tsc']
            del manifest['scripts']['test']
            del manifest['scripts']['typecheck']
            (self.project / 'package.json').write_text(json.dumps(manifest))
            if manager == 'npm':
                lock = self.project / 'package-lock.json'
                data = json.loads(lock.read_text())
                data['packages']['']['devDependencies'] = manifest['devDependencies']
                lock.write_text(json.dumps(data))
            self.assertEqual('build', frontend.metadata(sandbox, {'goal': 'build'})[3])
            for goal in ('test', 'typecheck'):
                with patch.object(sandbox, 'run_bounded', side_effect=self.fake_run) as run:
                    result = frontend.dispatch(sandbox, {'goal': goal})
                self.assertEqual('CHECK_NOT_AVAILABLE', result['classification'])
                self.assertEqual(0, result['executedTests'])
                self.assertEqual(3, run.call_count)

    def test_script_aliases_resolve_to_declared_names_without_rewriting_their_body(self):
        self.project_metadata()
        manifest_path = self.project / 'package.json'
        manifest = json.loads(manifest_path.read_text())
        for goal, name in (('typecheck', 'type-check'), ('build', 'build-only'), ('test', 'test:unit')):
            manifest['scripts'] = {name: 'node scripts/custom-wrapper.cjs && echo finished'}
            manifest_path.write_text(json.dumps(manifest))
            self.assertEqual(name, frontend.metadata(sandbox, {'goal': goal})[3])
        for script in (None, 17, '', ' '):
            manifest['scripts'] = {'build': script}
            manifest_path.write_text(json.dumps(manifest))
            with self.assertRaises(frontend.Unsupported) as failure:
                frontend.metadata(sandbox, {'goal': 'build'})
            self.assertNotIsInstance(failure.exception, frontend.CheckUnavailable)

    def test_unsupported_lock_sources_scripts_hooks_and_paths_fail_before_execution(self):
        self.project_metadata()
        for args in ({'goal': 'exec'}, {'goal': 'build', 'arguments': '--evil'},
                     {'goal': 'test', 'directory': '../outside'}, {'goal': 'test', 'directory': '/tmp'}):
            with self.subTest(args=args), self.assertRaises((sandbox.PolicyError, sandbox.CheckArgumentError)):
                frontend.metadata(sandbox, args)
        lock = self.project / 'pnpm-lock.yaml'
        for invalid in ('bad: [', 'lockfileVersion: 9.0\nimporters: {.: {}}\npackages: {evil: {resolution: {tarball: file:/tmp}}}',
                        'lockfileVersion: 9.0\nimporters: {.: {}}\npackages: &anchor {}',
                        'lockfileVersion: 9.0\nimporters: {.: {}}\npackages: {"evil@file:../archive.tgz": '
                        + '{resolution: {integrity: "sha512-' + 'A' * 86 + '=="}}}'):
            lock.write_text(invalid)
            with self.assertRaises(frontend.Unsupported):
                frontend.metadata(sandbox, {'goal': 'build'})
        self.project_metadata()
        lock.unlink()
        self.assertIsNone(frontend.metadata(sandbox, {'goal': 'build'})[-1])
        self.project_metadata()
        (self.root / '.pnpmfile.cjs').write_text('throw new Error("never execute")')
        with self.assertRaises(frontend.Unsupported):
            frontend.metadata(sandbox, {'goal': 'build'})

    def test_npm_lock_mismatch_and_external_resolution_are_explicitly_unsupported(self):
        self.project_metadata('npm')
        lock = self.project / 'package-lock.json'
        data = json.loads(lock.read_text())
        data['packages']['']['dependencies']['vue'] = '2.0.0'
        lock.write_text(json.dumps(data))
        self.assertEqual('npm', frontend.metadata(sandbox, {'goal': 'build'})[-2])
        with patch.object(sandbox, 'run_bounded', return_value=(1, 'npm ERR! EUSAGE lock file inconsistent')) as native:
            self.assertEqual('LOCKFILE_INVALID', frontend.prepare(sandbox, {'goal': 'build'})['classification'])
        self.assertEqual(['npm', 'ci'], native.call_args.args[0][:2])

        self.project_metadata('npm')
        data = json.loads(lock.read_text())
        data['packages']['node_modules/vue']['resolved'] = 'https://untrusted.invalid/evil.tgz'
        lock.write_text(json.dumps(data))
        with self.assertRaises(frontend.Unsupported):
            frontend.metadata(sandbox, {'goal': 'build'})


    def test_invalid_manifest_is_configuration_failure_rather_than_infrastructure_success(self):
        self.project_metadata()
        for malformed in ('{', '[]', '{"scripts":[]}', '{"dependencies":[]}'):
            (self.project / 'package.json').write_text(malformed)
            with self.assertRaises(frontend.Unsupported):
                frontend.metadata(sandbox, {'goal': 'build'})

    def execute(self, manager, goal, code=0, report=None, mutation=False):
        self.project_metadata(manager)
        def run(command, **kwargs):
            nonlocal report
            if mutation:
                (self.project / 'source.ts').write_text('mutated')
            if report is not None:
                report = dict(report)
                report.setdefault('testResults', [{'assertionResults': (
                    [{'status': 'passed'}] * max(0, report.get('numPassedTests', 0))
                    + [{'status': 'failed'}] * max(0, report.get('numFailedTests', 0)))}])
                (self.project / '.df-check/vitest.json').write_text(json.dumps(report))
                (self.project / '.df-check/test.receipt').write_text(json.dumps(
                    {'runner': 'vitest-json', 'report': 'vitest.json', 'exitCode': code}))
            return code, 'actual diagnostic'
        with patch.object(sandbox, 'run_bounded', side_effect=run):
            return frontend.check(sandbox, {'goal': goal})

    def test_shared_typecheck_build_and_test_results_never_invent_tests(self):
        scenarios = [('typecheck', 0, None, 'BUILD_PASSED_NOT_TESTED'),
                     ('typecheck', 1, None, 'TYPECHECK_FAILED'), ('build', 0, None, 'BUILD_PASSED_NOT_TESTED'),
                     ('build', 1, None, 'FRONTEND_BUILD_FAILED'),
                     ('test', 0, {'numTotalTests': 1, 'numPassedTests': 1, 'success': True}, 'TESTS_PASSED'),
                     ('test', 1, {'numTotalTests': 1, 'numFailedTests': 1, 'success': False}, 'TESTS_FAILED'),
                     ('test', 0, {'numTotalTests': 0, 'success': True}, 'TEST_EXECUTION_UNVERIFIED'),
                     ('test', 1, None, 'CHECK_PROCESS_FAILED')]
        for manager in ('npm', 'pnpm'):
            for goal, code, report, expected in scenarios:
                with self.subTest(manager=manager, goal=goal, expected=expected):
                    self.reset_outputs()
                    result = self.execute(manager, goal, code, report)
                    self.assertEqual(expected, result['classification'])
                    self.assertEqual(manager, result['packageManager'])
                    if goal != 'test' or report and report.get('numTotalTests') == 0:
                        self.assertEqual(0, result['executedTests'])

    def reset_outputs(self):
        import shutil
        sandbox.FRONTEND_OUTPUT_ROOT = None
        (self.project / 'source.ts').write_text('export const value = 1;')
        for name in frontend.GENERATED:
            if (self.project / name).exists():
                shutil.rmtree(self.project / name)
        for name in ('package-lock.json', 'pnpm-lock.yaml'):
            (self.project / name).unlink(missing_ok=True)

    def test_both_managers_source_mutation_and_missing_dependencies_are_not_success(self):
        for manager in ('npm', 'pnpm'):
            with self.subTest(manager=manager):
                self.reset_outputs()
                with self.assertRaises(sandbox.PolicyError) as rejected:
                    self.execute(manager, 'build', mutation=True)
                self.assertEqual('CHECK_MUTATED_SOURCE', rejected.exception.rule)
                self.reset_outputs()
                self.project_metadata(manager)
                with patch.object(frontend, 'DEPENDENCIES', self.root / 'missing'):
                    self.assertEqual('DEPENDENCY_MISSING', frontend.check(sandbox, {'goal': 'build'})['classification'])

    def test_timeout_and_isolation_rejection_are_shared(self):
        self.project_metadata()
        with patch.dict(os.environ, {'DF_FRONTEND_COPY': 'false'}), self.assertRaises(sandbox.PolicyError):
            frontend.dispatch(sandbox, {'goal': 'build'})
        def run(command, **kwargs):
            if command[1:] == ['--version']:
                return self.fake_run(command, **kwargs)
            raise sandbox.PolicyError('COMMAND_BUDGET', 'bounded')
        with patch.object(sandbox, 'run_bounded', side_effect=run):
            self.assertEqual('CHECK_TIMEOUT', frontend.dispatch(sandbox, {'goal': 'build'})['classification'])

    def test_version_probes_never_initialize_inside_repository_configuration(self):
        self.project_metadata('npm')
        with patch.object(sandbox, 'run_bounded', side_effect=self.fake_run) as run:
            frontend.dispatch(sandbox, {'goal': 'build'})
        for invocation in run.call_args_list[:3]:
            self.assertEqual(pathlib.Path('/tmp'), invocation.kwargs['cwd'])

    def test_committed_smoke_lockfiles_remain_supported_by_metadata_validation(self):
        resources = pathlib.Path(__file__).resolve().parents[1] / 'resources/agent'
        for manager, relative in (('npm', 'fullstack-npm'), ('pnpm', 'fullstack/frontend')):
            with self.subTest(manager=manager), patch.object(sandbox, 'ROOT', resources / relative):
                self.assertEqual(manager, frontend.metadata(sandbox, {'goal': 'test', 'directory': '.'})[-2])

    def test_generated_symlinks_only_allowed_in_disposable_dependency_tree(self):
        self.project_metadata()
        (self.project / 'node_modules').mkdir()
        with self.assertRaises(sandbox.PolicyError):
            sandbox.snapshot()
        sandbox.FRONTEND_OUTPUT_ROOT = self.project
        # 依赖树内部的链接由隔离容器承载；只有整个生成目录可被跳过。
        (self.project / 'node_modules/link').symlink_to(self.project / 'source.ts')
        sandbox.snapshot()
        (self.project / 'outside-link').symlink_to(self.project / 'source.ts')
        with self.assertRaises(sandbox.PolicyError):
            sandbox.snapshot()

    def test_real_process_timeout_kills_child_process_group(self):
        with self.assertRaises(sandbox.PolicyError) as rejected:
            sandbox.run_bounded([sys.executable, '-c', 'import time; time.sleep(30)'], timeout=0.1, cwd=self.root)
        self.assertEqual('COMMAND_BUDGET', rejected.exception.rule)

    def test_real_untrusted_process_source_write_is_rejected_for_both_managers(self):
        for manager in ('npm', 'pnpm'):
            with self.subTest(manager=manager):
                self.reset_outputs()
                self.project_metadata(manager)
                binary = self.deps / 'node_modules/.bin/vite'
                binary.write_text('#!' + sys.executable + '\nfrom pathlib import Path\n'
                                  + 'Path("source.ts").write_text("unexpected source mutation")\n')
                binary.chmod(0o755)
                before = sandbox.snapshot()['snapshot']
                with self.assertRaises(sandbox.PolicyError) as rejected:
                    frontend.check(sandbox, {'goal': 'build'})
                self.assertEqual('CHECK_MUTATED_SOURCE', rejected.exception.rule)
                self.assertNotEqual(before, sandbox.snapshot()['snapshot'])

    def test_native_runner_wrappers_execute_fail_correct_and_report_real_counts(self):
        for manager in ('npm', 'pnpm'):
            with self.subTest(manager=manager):
                self.reset_outputs()
                manifest = self.project_metadata(manager)
                del manifest['devDependencies']['vitest']
                manifest['scripts']['test'] = manager + ' run native && node -e "process.exit(0)"'
                manifest['scripts']['native'] = 'node wrapper.cjs'
                (self.project / 'package.json').write_text(json.dumps(manifest))
                if manager == 'npm':
                    lock = self.project / 'package-lock.json'
                    data = json.loads(lock.read_text())
                    data['packages']['']['devDependencies'] = manifest['devDependencies']
                    lock.write_text(json.dumps(data))
                (self.project / 'wrapper.cjs').write_text(
                    "const {spawnSync}=require('node:child_process');"
                    "process.exit(spawnSync(process.execPath,['--test','tests/value.test.cjs'],"
                    "{stdio:'inherit',env:process.env}).status);\n")
                tests = self.project / 'tests'
                tests.mkdir(exist_ok=True)
                target = tests / 'value.test.cjs'
                target.write_text("const {test,describe}=require('node:test');"
                    "const assert=require('node:assert/strict');"
                    "describe('due dates',()=>{test('overdue',()=>assert.equal(1,2));"
                    "test('due today',()=>assert.equal(3,3));"
                    "test('pending',{skip:true},()=>{});});\n")
                failed = frontend.check(sandbox, {'goal': 'test'})
                self.assertEqual('TESTS_FAILED', failed['classification'], failed)
                self.assertEqual('node-junit', failed['runner'])
                self.assertEqual(2, failed['executedTests'])
                self.assertEqual(1, failed['failures'])
                self.assertEqual(1, failed['skipped'])
                target.write_text(target.read_text().replace('assert.equal(1,2)', 'assert.equal(2,2)'))
                import shutil
                shutil.rmtree(self.project / 'node_modules')
                shutil.rmtree(self.project / '.df-check')
                sandbox.FRONTEND_OUTPUT_ROOT = None
                passed = frontend.check(sandbox, {'goal': 'test'})
                self.assertEqual('TESTS_PASSED', passed['classification'], passed)
                self.assertEqual(2, passed['executedTests'])
                self.assertNotEqual(failed['snapshot'], passed['snapshot'])

    def test_missing_forged_or_malformed_report_never_becomes_pass(self):
        for report, expected in ((None, 'TEST_EXECUTION_UNVERIFIED'),
                ({'numTotalTests': 2, 'numPassedTests': 1, 'success': True}, 'CHECK_RESULT_UNAVAILABLE'),
                ({'numTotalTests': -1, 'success': True}, 'CHECK_RESULT_UNAVAILABLE'),
                ({'numTotalTests': 1, 'numPassedTests': 1, 'success': False}, 'CHECK_RESULT_UNAVAILABLE')):
            self.reset_outputs()
            self.assertEqual(expected, self.execute('npm', 'test', report=report)['classification'])

    def test_capabilities_change_with_scripts_and_explain_available_path(self):
        manifest = self.project_metadata()
        first = frontend.describe(sandbox)
        manifest['scripts']['test'] = 'node --test tests/'
        (self.project / 'package.json').write_text(json.dumps(manifest))
        second = frontend.describe(sandbox)
        self.assertNotEqual(first['configurationSnapshot'], second['configurationSnapshot'])
        project = second['frontendProjects'][0]
        self.assertEqual('pnpm', project['packageManager'])
        self.assertEqual('test', project['goalDetails']['test']['script'])
        self.assertIn('node-junit', second['testReporters'])
        self.assertIn('CHECK_COPY_ONLY', second['dependencyVisibility'])

    def test_real_offline_scripts_cannot_read_credentials_or_reach_network(self):
        self.project_metadata('npm')
        manifest = self.project / 'package.json'
        data = json.loads(manifest.read_text())
        data['scripts']['build'] = 'node security.cjs'
        manifest.write_text(json.dumps(data))
        (self.project / 'security.cjs').write_text("""
const assert = require('node:assert/strict');
const fs = require('node:fs');
const net = require('node:net');
assert.equal(process.env.DEEPSEEK_API_KEY, undefined);
assert.equal(fs.existsSync('/var/run/docker.sock'), false);
assert.equal(fs.existsSync('/home/agent/.ssh/id_rsa'), false);
assert.throws(() => fs.writeFileSync('/opt/delveforge/injected', 'bad'));
const socket = net.connect({host: '1.1.1.1', port: 443});
socket.on('connect', () => { throw new Error('Network access was available'); });
socket.on('error', () => { console.log('NETWORK_DENIED'); });
socket.setTimeout(1000, () => { socket.destroy(); console.log('NETWORK_UNAVAILABLE'); });
""")
        checked = frontend.check(sandbox, {'goal': 'build'})
        self.assertEqual('BUILD_PASSED_NOT_TESTED', checked['classification'], checked)
        self.assertTrue('NETWORK_DENIED' in checked['output'] or 'NETWORK_UNAVAILABLE' in checked['output'])

    def test_real_detached_process_is_killed_before_source_is_inspected(self):
        if os.getpid() != 1:
            self.skipTest('Requires actual disposable container PID namespace')
        self.project_metadata('npm')
        manifest = self.project / 'package.json'
        data = json.loads(manifest.read_text())
        data['scripts']['build'] = 'node background.cjs'
        manifest.write_text(json.dumps(data))
        marker = pathlib.Path(self.temporary.name) / 'escaped'
        payload = "setTimeout(()=>require('node:fs').writeFileSync(" + json.dumps(str(marker)) + ", 'bad'),500)"
        (self.project / 'background.cjs').write_text(
            "require('node:child_process').spawn(process.execPath,['-e'," + json.dumps(payload)
            + "],{detached:true,stdio:'ignore'}).unref();")
        checked = frontend.check(sandbox, {'goal': 'build'})
        self.assertEqual('BUILD_PASSED_NOT_TESTED', checked['classification'], checked)
        import time
        time.sleep(0.8)
        self.assertFalse(marker.exists())

    def test_real_script_timeout_and_heap_exhaustion_are_failures(self):
        self.project_metadata('npm')
        manifest = self.project / 'package.json'
        data = json.loads(manifest.read_text())
        data['scripts']['build'] = 'node limit.cjs'
        manifest.write_text(json.dumps(data))
        (self.project / 'limit.cjs').write_text('while(true) {}')
        with patch.dict(os.environ, {'DF_CHECK_SECONDS': '1'}):
            timed = frontend.dispatch(sandbox, {'goal': 'build'})
        self.assertEqual('CHECK_TIMEOUT', timed['classification'], timed)
        self.assertEqual('UNKNOWN', timed['executionStatus'])
        import shutil
        shutil.rmtree(self.project / 'node_modules')
        shutil.rmtree(self.project / '.df-check')
        sandbox.FRONTEND_OUTPUT_ROOT = None
        data['scripts']['build'] = 'node --max-old-space-size=16 limit.cjs'
        manifest.write_text(json.dumps(data))
        (self.project / 'limit.cjs').write_text('const memory=[];while(true) memory.push(new Array(100000).fill(1));')
        checked = frontend.check(sandbox, {'goal': 'build'})
        self.assertEqual('FRONTEND_BUILD_FAILED', checked['classification'], checked)
        self.assertNotEqual(0, checked['exitCode'])


if __name__ == '__main__':
    unittest.main()
