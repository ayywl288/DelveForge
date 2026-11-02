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

    def test_supported_script_families_are_parsed_without_shell_or_arbitrary_delegation(self):
        scripts = {'type-check': 'vue-tsc --build', 'build-only': 'vite build --mode production',
                   'test:unit': 'vitest --environment jsdom'}
        self.assertEqual([['vue-tsc', '--build'], ['vite', 'build', '--mode', 'production']],
            frontend.supported_commands('run-p type-check "build-only {@}" --', scripts, 'build'))
        self.assertEqual([['vitest', 'run', '--environment', 'jsdom']],
            frontend.supported_commands('npm run test:unit', scripts, 'test'))
        self.assertEqual([['vue-tsc', '--noEmit', '-p', 'tsconfig.vitest.json', '--composite', 'false']],
            frontend.supported_commands('vue-tsc --noEmit -p tsconfig.vitest.json --composite false', {}, 'typecheck'))
        commands = frontend.supported_commands(
            'vue-tsc --noEmit && NODE_OPTIONS=--max_old_space_size=2048 vite build', {}, 'build')
        self.assertEqual(['node', '--max-old-space-size=2048', 'node_modules/vite/bin/vite.js', 'build'], commands[1])
        for script in ('vue-tsc --noEmit && NODE_OPTIONS=--require=evil vite build',
                       'vue-tsc --noEmit && NODE_OPTIONS=--max_old_space_size=8192 vite build',
                       'node evil.js', 'vite build; echo owned', 'run-p arbitrary build-only'):
            with self.assertRaises(frontend.Unsupported):
                frontend.supported_commands(script, scripts, 'build')
        for script in ('vue-tsc -p ../outside.json', 'vue-tsc --outDir src', 'vue-tsc --project /tmp/evil'):
            with self.assertRaises(frontend.Unsupported):
                frontend.supported_commands(script, {}, 'typecheck')

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
        self.assertFalse((self.prepare_dir / 'pnpm-workspace.yaml').exists())
        for raw in ('packages: ["packages/*"]', 'registry: https://untrusted.invalid',
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
            self.assertEqual([['vite', 'build']], frontend.metadata(sandbox, {'goal': 'build'})[3])
            for goal in ('test', 'typecheck'):
                with patch.object(sandbox, 'run_bounded', side_effect=self.fake_run) as run:
                    result = frontend.dispatch(sandbox, {'goal': goal})
                self.assertEqual('CHECK_NOT_AVAILABLE', result['classification'])
                self.assertEqual(0, result['executedTests'])
                self.assertEqual(3, run.call_count)

    def test_common_script_names_and_fixed_commands_do_not_execute_shells(self):
        self.project_metadata()
        manifest_path = self.project / 'package.json'
        manifest = json.loads(manifest_path.read_text())
        for name, script, goal, commands in (
                ('build', 'vue-tsc && vite build', 'build', [['vue-tsc'], ['vite', 'build']]),
                ('build', 'vue-tsc --noEmit && vite build', 'build', [['vue-tsc', '--noEmit'], ['vite', 'build']]),
                ('type-check', 'vue-tsc --noEmit', 'typecheck', [['vue-tsc', '--noEmit']]),
                ('test:unit', 'vitest', 'test', [['vitest', 'run']])):
            manifest['scripts'] = {name: script}
            manifest_path.write_text(json.dumps(manifest))
            self.assertEqual(commands, frontend.metadata(sandbox, {'goal': goal})[3])
        for script in (None, 17, 'vite build; touch owned', 'vite build && node evil.js', 'vitest --watch', 'npm run arbitrary'):
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
        with self.assertRaises(frontend.Unsupported):
            frontend.metadata(sandbox, {'goal': 'build'})
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
        with self.assertRaises(frontend.Unsupported):
            frontend.metadata(sandbox, {'goal': 'build'})

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
            if mutation:
                (self.project / 'source.ts').write_text('mutated')
            if report is not None:
                (self.project / '.df-check/vitest.json').write_text(json.dumps(report))
            return code, 'actual diagnostic'
        with patch.object(sandbox, 'run_bounded', side_effect=run):
            return frontend.check(sandbox, {'goal': goal})

    def test_shared_typecheck_build_and_test_results_never_invent_tests(self):
        scenarios = [('typecheck', 0, None, 'BUILD_PASSED_NOT_TESTED'),
                     ('typecheck', 1, None, 'TYPECHECK_FAILED'), ('build', 0, None, 'BUILD_PASSED_NOT_TESTED'),
                     ('build', 1, None, 'FRONTEND_BUILD_FAILED'),
                     ('test', 0, {'numTotalTests': 1, 'numPassedTests': 1}, 'TESTS_PASSED'),
                     ('test', 1, {'numTotalTests': 1, 'numFailedTests': 1}, 'TESTS_FAILED'),
                     ('test', 0, {'numTotalTests': 0}, 'BUILD_PASSED_NOT_TESTED'),
                     ('test', 1, None, 'CHECK_RESULT_UNAVAILABLE')]
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
            if command[0] in ('node', 'npm', 'pnpm'):
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


if __name__ == '__main__':
    unittest.main()
