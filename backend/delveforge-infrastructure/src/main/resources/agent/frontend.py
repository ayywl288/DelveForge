"""按目标执行项目声明的 npm/pnpm 脚本；隔离负责安全，报告适配负责证据。"""
import base64
import binascii
import json
import os
import pathlib
import re
import shutil
import hashlib
import xml.etree.ElementTree as ET

NODE_VERSION = 'v22.16.0'
PNPM_VERSION = '12.9.1'
NPM_VERSION = '10.9.2'
PREPARATION = pathlib.Path('/workspace/.df-frontend-dependencies')
DEPENDENCIES = pathlib.Path('/dependencies/.df-frontend-dependencies')
GENERATED = {'node_modules', 'dist', 'coverage', '.vite', '.df-check'}
SCRIPT_NAMES = {'typecheck': ('typecheck', 'type-check'), 'build': ('build', 'build-only'), 'test': ('test', 'test:unit')}
REPORT_HOOK = pathlib.Path('/opt/delveforge/frontend-report.cjs')


class Unsupported(ValueError):
    pass


class InvalidLock(Unsupported):
    pass


class CheckUnavailable(Unsupported):
    pass


def registry_integrity(value):
    # 旧锁的 SHA-1 仍须完整校验；算法不作为包可信或允许执行安装脚本的依据。
    match = re.fullmatch(r'(sha1|sha256|sha384|sha512)-([A-Za-z0-9+/]+={0,2})', str(value))
    if not match:
        return False
    try:
        digest = base64.b64decode(match[2], validate=True)
    except (ValueError, binascii.Error):
        return False
    return len(digest) == {'sha1': 20, 'sha256': 32, 'sha384': 48, 'sha512': 64}[match[1]] \
        and base64.b64encode(digest).decode() == match[2]


def root_only_configuration(path):
    import yaml
    if not path.is_file() or path.is_symlink() or path.stat().st_size > 65536:
        raise Unsupported('Expected a bounded standalone pnpm configuration')
    try:
        raw = path.read_text(encoding='utf-8')
        if any(isinstance(token, (yaml.tokens.AliasToken, yaml.tokens.AnchorToken, yaml.tokens.TagToken))
               for token in yaml.scan(raw)):
            raise Unsupported('YAML aliases, anchors and tags are unsupported')
        data = yaml.safe_load(raw)
    except yaml.YAMLError:
        raise Unsupported('Invalid standalone pnpm configuration') from None
    if not isinstance(data, dict) or set(data) - {
            'packages', 'allowBuilds', 'onlyBuiltDependencies', 'ignoredBuiltDependencies'} \
            or data.get('packages', ['.']) not in ([], ['.']):
        raise Unsupported('Only standalone pnpm configuration is supported; workspace packages/settings are unsupported')
    builds = data.get('allowBuilds', {})
    if not isinstance(builds, dict) or any(not isinstance(name, str)
            or not re.fullmatch(r'(?:@[a-z0-9_.-]+/)?[a-z0-9_.-]+', name) or type(value) is not bool
            for name, value in builds.items()):
        raise Unsupported('Invalid dependency build declarations')
    for field in ('onlyBuiltDependencies', 'ignoredBuiltDependencies'):
        values = data.get(field, [])
        if not isinstance(values, list) or any(not isinstance(name, str)
                or not re.fullmatch(r'(?:@[a-z0-9_.-]+/)?[a-z0-9_.-]+', name) for name in values):
            raise Unsupported('Invalid dependency build declarations')
    # 这些许可不进入准备目录；DelveForge 的 ignore-scripts 始终优先，不执行被许可的安装构建。


def project_directories(sandbox):
    projects = []
    for path in sandbox.all_files():
        if path != 'package.json' and not path.endswith('/package.json'):
            continue
        try:
            manifest = json.loads(sandbox.text(sandbox.path_for(path)))
            deps = {**manifest.get('dependencies', {}), **manifest.get('devDependencies', {})}
            if 'vue' in deps and 'vite' in deps and re.match(r'^[~^]?3(?:\.|$)', str(deps['vue'])):
                projects.append(pathlib.PurePosixPath(path).parent.as_posix())
        except (ValueError, OSError, TypeError, AttributeError, sandbox.PolicyError):
            continue
    return sorted(projects)


def configuration_snapshot(sandbox):
    names = {'package.json', 'package-lock.json', 'pnpm-lock.yaml', 'pnpm-workspace.yaml', '.npmrc'}
    files = [file for file in sandbox.snapshot()['files'] if pathlib.PurePosixPath(file['path']).name in names]
    return hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()


def describe(sandbox):
    projects = project_directories(sandbox)
    frontend = []
    for directory in projects[:20]:
        goals, details = {}, {}
        project = sandbox.ROOT / directory
        locks = [manager for manager, lock in (('npm', 'package-lock.json'), ('pnpm', 'pnpm-lock.yaml'))
                 if (project / lock).is_file()]
        manager = locks[0] if len(locks) == 1 else None
        scripts = []
        try:
            manifest = json.loads((project / 'package.json').read_text(encoding='utf-8'))
            scripts = [name for name in manifest.get('scripts', {})
                       if isinstance(name, str) and re.fullmatch(r'[A-Za-z0-9:_-]{1,64}', name)][:30]
        except (ValueError, OSError, TypeError, AttributeError):
            pass
        for goal in SCRIPT_NAMES:
            try:
                _, _, _, script, _, manager, _ = metadata(sandbox, {'directory': directory, 'goal': goal},
                                                         validate_lock=False)
                goals[goal] = 'DECLARED_SUPPORTED'
                details[goal] = {'script': script, 'reason': 'Declared script; dependencies and execution not verified'}
            except CheckUnavailable as error:
                goals[goal] = 'ABSENT' if not sandbox.source_view()['withheldSourceCount'] else 'UNAVAILABLE'
                details[goal] = {'reason': str(error)[:300]}
            except (Unsupported, sandbox.CheckArgumentError) as error:
                goals[goal] = 'UNSUPPORTED'
                details[goal] = {'reason': str(error)[:300]}
            except (OSError, ValueError, TypeError, sandbox.PolicyError):
                goals[goal] = 'UNAVAILABLE'
                details[goal] = {'reason': 'Configuration cannot be safely inspected'}
        frontend.append({'directory': directory, 'packageManager': manager, 'goals': goals, 'goalDetails': details,
                         'scripts': scripts})
    maven = sorted(directory for directory in sandbox.maven_module_roots()
                   if (sandbox.ROOT / directory / 'pom.xml').is_file())
    return {'frontendProjects': frontend, 'frontendProjectCount': len(projects),
            'configurationSnapshot': configuration_snapshot(sandbox),
            'dependencyVisibility': 'CHECK_COPY_ONLY: node_modules and generated reports are not Agent source; use check tools',
            'testReporters': ['node-junit', 'vitest-json'],
            'testGuidance': 'Use existing scripts. Node --test and Vitest CLI are instrumented through NODE_OPTIONS; '
                            'wrappers must preserve it. Other scripts execute but cannot claim tests without reports.',
            'mavenDirectories': maven[:50], 'mavenProjectCount': len(maven), 'sourceView': sandbox.source_view()}


def metadata(sandbox, args, validate_lock=True):
    if sandbox.source_view()['withheldSourceCount']:
        raise CheckUnavailable('Executable source or build configuration is withheld; checks cannot attest this view')
    goal = args.get('goal')
    if goal not in SCRIPT_NAMES or set(args) - {'goal', 'directory'}:
        raise sandbox.CheckArgumentError('Choose typecheck, build or test without command arguments')
    directory = args.get('directory')
    if directory is None:
        projects = project_directories(sandbox)
        if len(projects) != 1:
            raise CheckUnavailable('Select one frontend directory; discovered: ' + ', '.join(projects[:20]))
        directory = projects[0]
    project = sandbox.path_for(directory, True)
    relative = project.relative_to(sandbox.ROOT).as_posix()
    if not (project / 'package.json').is_file():
        raise CheckUnavailable('No frontend package.json in selected directory; choose availableChecks.frontendProjects')
    # 独立单工程是当前明确支持范围；不加载 workspace、pnpmfile 或项目级 npm 配置。
    for parent in [project, *project.parents]:
        if not parent.is_relative_to(sandbox.ROOT):
            break
        configuration = parent / 'pnpm-workspace.yaml'
        if configuration.exists():
            root_only_configuration(configuration)
        registry = parent / '.npmrc'
        registry_path = registry.relative_to(sandbox.ROOT).as_posix()
        if registry.exists() and not sandbox.approved_resource(registry_path) \
                or registry_path in sandbox.RESOURCE_CAPABILITIES \
                    and sandbox.RESOURCE_CAPABILITIES[registry_path]['kind'] == 'PRESERVED_ONLY':
            raise Unsupported('Project npm configuration is withheld or is not a validated public resource')
        if any((parent / name).exists() for name in (
                '.pnpmfile.cjs', 'pnpmfile.cjs',
                'yarn.lock', 'npm-shrinkwrap.json')):
            raise Unsupported('Workspace, hooks, custom registry configuration or mixed lockfiles are unsupported')
    try:
        manifest = json.loads(sandbox.text(sandbox.path_for(
            ('package.json' if relative == '.' else relative + '/package.json'))))
    except ValueError:
        raise Unsupported('A readable JSON package.json is required') from None
    if not isinstance(manifest, dict):
        raise Unsupported('Expected package.json object')
    for field in ('dependencies', 'devDependencies', 'optionalDependencies', 'peerDependencies', 'scripts'):
        if field in manifest and not isinstance(manifest[field], dict):
            raise Unsupported('Expected package.json object field: ' + field)
    lockfiles = [name for name in ('pnpm-lock.yaml', 'package-lock.json')
                 if (project / name).exists()]
    if len(lockfiles) != 1:
        raise Unsupported('Exactly one pnpm-lock.yaml or package-lock.json is required')
    manager = 'pnpm' if lockfiles[0] == 'pnpm-lock.yaml' else 'npm'
    declared_manager = manifest.get('packageManager')
    if 'packageManager' in manifest:
        match = re.fullmatch(r'(npm|pnpm)@(\d+)\.\d+\.\d+(?:\+sha(?:224|512)\.[a-f0-9]+)?',
                             str(declared_manager))
        supported_majors = range(9, 13) if manager == 'pnpm' else range(9, 12)
        if not match or match[1] != manager or int(match[2]) not in supported_majors:
            raise Unsupported('packageManager conflicts with the lockfile or supported lockfile toolchains')
    if any(name in manifest for name in ('pnpm', 'workspaces', 'devEngines')):
        raise Unsupported('Custom pnpm configuration or automatic runtime provisioning is unsupported')
    deps = {**manifest.get('dependencies', {}), **manifest.get('devDependencies', {})}
    if not all(name in deps for name in ('vue', 'vite')):
        raise Unsupported('Expected Vue 3/Vite dependencies; TypeScript checking is optional')
    if not re.match(r'^[~^]?3(?:\.|$)', str(deps['vue'])):
        raise Unsupported('Only Vue 3 is supported')
    for name, version in {**deps, **manifest.get('optionalDependencies', {})}.items():
        if not re.fullmatch(r'(?:@[a-z0-9_.-]+/)?[a-z0-9_.-]+', name) or not isinstance(version, str) \
                or re.search(r'[:/\\]|[\r\n]', version):
            raise Unsupported('Only public registry dependencies are supported')
    scripts = manifest.get('scripts', {})
    script_name = next((name for name in SCRIPT_NAMES[goal] if name in scripts), None)
    script = scripts.get(script_name)
    lock = project / lockfiles[0]
    if not lock.is_file() or lock.is_symlink() or lock.stat().st_size > 2097152:
        raise Unsupported('A bounded, regular package-manager lockfile is required')
    if validate_lock:
        if manager == 'pnpm':
            pnpm_lock(lock)
        else:
            npm_lock(lock, manifest)
    # 没有可用检查不等于执行失败；已知失败仍由冻结规则要求同目标成功重验。
    if script_name is None:
        raise CheckUnavailable('No declared ' + goal + ' script is available; project configuration was not changed')
    if not isinstance(script, str) or not script.strip() or len(script) > 8192 or '\x00' in script:
        raise Unsupported('Expected a bounded nonempty declared script')
    # 还原阶段只传经过检查的依赖元数据，原 scripts 不进入联网准备目录。
    prepared_manifest = {key: manifest[key] for key in (
        'name', 'version', 'private', 'type', 'dependencies', 'devDependencies',
        'optionalDependencies', 'peerDependencies', 'peerDependenciesMeta', 'engines') if key in manifest}
    return project, relative, goal, script_name, prepared_manifest, manager, lockfiles[0]


def pnpm_lock(lock):
    import yaml
    # 拒绝别名/标签，避免锁文件解析膨胀；SafeLoader 不执行 YAML 对象构造。
    raw = lock.read_text(encoding='utf-8')
    try:
        if any(isinstance(token, (yaml.tokens.AliasToken, yaml.tokens.AnchorToken, yaml.tokens.TagToken))
               for token in yaml.scan(raw)):
            raise Unsupported('YAML aliases, anchors and tags are unsupported')
        documents = list(yaml.safe_load_all(raw))
    except yaml.YAMLError:
        raise InvalidLock('Invalid pnpm lockfile YAML') from None
    if not 1 <= len(documents) <= 2:
        raise Unsupported('Expected a standalone lockfile and optional pinned pnpm toolchain document')
    for data in documents:
        if not isinstance(data, dict) or str(data.get('lockfileVersion')) != '9.0' \
                or not isinstance(data.get('importers'), dict) or set(data['importers']) != {'.'} \
                or not isinstance(data['importers']['.'], dict) or not isinstance(data.get('packages'), dict) \
                or not isinstance(data.get('settings', {}), dict):
            raise Unsupported('Only pnpm v9 standalone lockfiles are supported')
        if set(data) - {'lockfileVersion', 'settings', 'importers', 'packages', 'snapshots'} \
                or set(data.get('settings', {})) - {'autoInstallPeers', 'excludeLinksFromLockfile'} \
                or data['importers']['.'].get('configDependencies'):
            raise Unsupported('Custom lockfile settings and configuration dependencies are unsupported')
        for package, entry in data['packages'].items():
            if not re.fullmatch(r'(?:@[a-z0-9_.-]+/)?[a-z0-9_.-]+@[0-9][A-Za-z0-9.+\-]*', str(package)):
                raise Unsupported('Only registry package names and fixed versions are supported')
            resolution = entry.get('resolution', {}) if isinstance(entry, dict) else {}
            if set(resolution) != {'integrity'} or not registry_integrity(resolution.get('integrity')):
                raise Unsupported('Every package requires valid registry integrity; local/git/URL packages are unsupported')
    if len(documents) == 2:
        toolchain = documents[0]['importers']['.']
        if toolchain != {'configDependencies': {}, 'packageManagerDependencies': {
                'pnpm': {'specifier': PNPM_VERSION, 'version': PNPM_VERSION}}}:
            raise Unsupported('Only the pinned pnpm toolchain document is supported; config dependencies are forbidden')


def npm_lock(lock, manifest):
    try:
        data = json.loads(lock.read_text(encoding='utf-8'))
    except ValueError:
        raise InvalidLock('Invalid npm lockfile JSON') from None
    if not isinstance(data, dict) or data.get('lockfileVersion') != 3 or not isinstance(data.get('packages'), dict):
        raise Unsupported('Only npm lockfileVersion 3 standalone projects are supported')
    root = data['packages'].get('')
    if not isinstance(root, dict):
        raise Unsupported('npm lockfile must contain the root package')
    for field in ('dependencies', 'devDependencies', 'optionalDependencies'):
        if root.get(field, {}) != manifest.get(field, {}):
            raise InvalidLock('npm lockfile dependencies do not match package.json')
    for path, entry in data['packages'].items():
        if path == '':
            continue
        if not re.fullmatch(r'node_modules/(?:[a-zA-Z0-9_@.\-/]+)', path) or '..' in path.split('/') \
                or not isinstance(entry, dict) or entry.get('link') \
                or not re.fullmatch(r'https://registry\.npmjs\.org/(?:@[a-z0-9_.-]+/)?[a-z0-9_.-]+/-/[A-Za-z0-9_.+\-]+\.tgz',
                                    str(entry.get('resolved', ''))) \
                or not registry_integrity(entry.get('integrity')):
            raise Unsupported('npm packages require public registry tarballs and valid integrity; links are unsupported')


def result(identity, classification, output='', code=None, **counts):
    return {**identity, 'classification': classification, 'exitCode': code,
            'tests': 0, 'executedTests': 0, 'skipped': 0, 'failures': 0, 'errors': 0,
            'reports': 0, **counts, 'output': output[-18000:]}


def prepare(sandbox, args):
    project, relative, goal, _, manifest, manager, lockfile = metadata(sandbox, args)
    identity = {'checkType': 'FRONTEND', 'directory': relative, 'goal': goal,
                'snapshot': sandbox.snapshot()['snapshot'], 'module': None, 'selectedTest': None, 'packageManager': manager,
                'sourceView': sandbox.source_view(), 'executionStatus': 'NOT_STARTED',
                'configurationSnapshot': configuration_snapshot(sandbox)}
    # 无源码、无项目 hooks、无生命周期脚本的临时目录；只有固定 registry 代理可达。
    preparation = PREPARATION
    if preparation.exists():
        raise Unsupported('Dependency preparation directory already exists')
    preparation.mkdir()
    (preparation / 'package.json').write_text(json.dumps(manifest), encoding='utf-8')
    shutil.copyfile(project / lockfile, preparation / lockfile)
    command = ['pnpm', 'install', '--frozen-lockfile', '--ignore-scripts', '--ignore-pnpmfile',
               '--network-concurrency=2',
               '--no-runtime', '--config.registry=http://df-artifacts:8080/npm/',
               '--config.verify-store-integrity=true', '--config.side-effects-cache=false',
               '--config.package-import-method=copy', '--store-dir=/workspace/.df-frontend-store',
               '--config.node-linker=isolated', '--config.strict-dep-builds=false',
               '--config.update-notifier=false']
    if manager == 'npm':
        command = ['npm', 'ci', '--ignore-scripts', '--registry=http://df-artifacts:8080/npm/',
                   '--maxsockets=2',
                   '--cache=/workspace/.df-frontend-npm-cache', '--no-audit', '--no-fund', '--update-notifier=false']
    else:
        for parent in [project, *project.parents]:
            if not parent.is_relative_to(sandbox.ROOT):
                break
            registry = (parent / '.npmrc').relative_to(sandbox.ROOT).as_posix()
            resource = sandbox.RESOURCE_CAPABILITIES.get(registry, {})
            for name, value in resource.get('settings', {}).items():
                if name in ('shamefully-hoist', 'auto-install-peers', 'strict-peer-dependencies'):
                    command.append('--config.' + name + '=' + value)
    code, output = sandbox.run_bounded(command, timeout=int(os.environ.get('DF_CHECK_SECONDS', '180')) * 3,
                                       cwd=preparation)
    if code:
        classification = ('LOCKFILE_INVALID' if re.search(r'LOCKFILE|INTEGRITY|TARBALL_INTEGRITY|EUSAGE|lock file', output)
                          else 'DEPENDENCY_MISSING')
        return result(identity, classification, output, code)
    return result(identity, 'DEPENDENCIES_PREPARED', output, code)


def read_report(path):
    if not path.is_file() or path.is_symlink() or path.stat().st_size > 2097152:
        raise ValueError('Missing or unbounded test report')
    return path.read_text(encoding='utf-8')


def normalize_report(runner, path):
    raw = read_report(path)
    if runner == 'vitest-json':
        data = json.loads(raw)
        keys = ['numTotalTests', 'numPassedTests', 'numFailedTests', 'numPendingTests', 'numTodoTests']
        counts = [data.get(key, 0) for key in keys]
        if any(type(count) is not int or count < 0 for count in counts):
            raise ValueError('Invalid Vitest counts')
        total, passed, failed, pending, todo = counts
        if passed + failed + pending + todo != total or total > 100000:
            raise ValueError('Contradictory Vitest counts')
        suites = data.get('testResults')
        if not isinstance(suites, list) or len(suites) > 10000:
            raise ValueError('Missing Vitest test results')
        assertions = [test for suite in suites for test in suite['assertionResults']]
        statuses = [test['status'] for test in assertions]
        if len(statuses) != total or statuses.count('passed') != passed or statuses.count('failed') != failed \
                or sum(status in ('pending', 'skipped', 'todo', 'disabled') for status in statuses) != pending + todo:
            raise ValueError('Vitest summary differs from actual test cases')
        # 采集错误也可能没有失败 test case，不能仅凭汇总计数判定通过。
        if data.get('success') is not True and not failed:
            raise ValueError('Vitest did not attest successful execution')
        return dict(tests=total, executedTests=passed + failed, skipped=pending + todo, failures=failed, errors=0)
    if runner == 'node-junit':
        if '<!DOCTYPE' in raw.upper() or '<!ENTITY' in raw.upper():
            raise ValueError('XML declarations are unsupported')
        root = ET.fromstring(raw)
        if root.tag not in ('testsuite', 'testsuites'):
            raise ValueError('Expected JUnit suites')
        # Node 嵌套 suite 的计数包含子 suite；以实际 testcase 计数，避免重复累计。
        cases = list(root.iter('testcase'))
        if len(cases) > 100000:
            raise ValueError('Test count budget exceeded')
        failures = sum(case.find('failure') is not None for case in cases)
        errors = sum(case.find('error') is not None for case in cases)
        skipped = sum(case.find('skipped') is not None for case in cases)
        if failures + errors + skipped > len(cases):
            raise ValueError('Contradictory JUnit cases')
        return dict(tests=len(cases), executedTests=len(cases) - skipped, skipped=skipped,
                    failures=failures, errors=errors)
    raise ValueError('Unattested test runner')


def test_result(identity, directory, code, output):
    receipts = sorted(directory.glob('*.receipt'))
    if len(receipts) > 32:
        return result(identity, 'CHECK_RESULT_UNAVAILABLE', output + '\nTest report count budget exceeded', code)
    if not receipts:
        return result(identity, 'TEST_EXECUTION_UNVERIFIED' if code == 0 else 'CHECK_PROCESS_FAILED', output, code)
    counts = dict(tests=0, executedTests=0, skipped=0, failures=0, errors=0, reports=0)
    runners = set()
    seen = set()
    runner_failed = False
    for receipt in receipts:
        data = json.loads(read_report(receipt))
        runner, name = data['runner'], data['report']
        if runner not in ('node-junit', 'vitest-json') or not isinstance(name, str) \
                or pathlib.Path(name).name != name or name in seen or type(data['exitCode']) is not int:
            raise ValueError('Invalid runner receipt')
        seen.add(name)
        report = normalize_report(runner, directory / name)
        for key, count in report.items():
            counts[key] += count
        counts['reports'] += 1
        runners.add(runner)
        runner_failed |= data['exitCode'] != 0
    identity = {**identity, 'runner': '+'.join(sorted(runners))}
    failed = counts['failures'] + counts['errors'] > 0
    classification = ('TESTS_FAILED' if failed else 'CHECK_PROCESS_FAILED' if code != 0 or runner_failed
                      else 'TESTS_PASSED' if counts['executedTests'] > 0 else 'TEST_EXECUTION_UNVERIFIED')
    return result(identity, classification, output, code, **counts)


def stop_project_processes():
    # 此路径仅在一次性容器的 PID 1 中启用；包括脱离原进程组的后台进程。
    if os.getpid() == 1:
        import signal
        try:
            os.kill(-1, signal.SIGKILL)
        except ProcessLookupError:
            # 没有残留进程是正常结束，不是清理失败。
            return
        while True:
            try:
                os.waitpid(-1, 0)
            except ChildProcessError:
                break


def check(sandbox, args):
    project, relative, goal, script_name, _, manager, _ = metadata(sandbox, args)
    before = sandbox.snapshot()
    identity = {'checkType': 'FRONTEND', 'directory': relative, 'goal': goal,
                'snapshot': before['snapshot'], 'module': None, 'selectedTest': None, 'packageManager': manager,
                'sourceView': sandbox.source_view(), 'executionStatus': 'EXECUTED',
                'configurationSnapshot': configuration_snapshot(sandbox)}
    modules = DEPENDENCIES / 'node_modules'
    if not modules.is_dir():
        return result({**identity, 'executionStatus': 'NOT_STARTED'}, 'DEPENDENCY_MISSING',
                      'Frozen dependencies are unavailable')
    sandbox.FRONTEND_OUTPUT_ROOT = project
    shutil.copytree(modules, project / 'node_modules', symlinks=True)
    reports = project / '.df-check'
    reports.mkdir()
    environment = dict(os.environ, CI='true', npm_config_update_notifier='false',
                       npm_config_audit='false', npm_config_fund='false', npm_config_offline='true',
                       npm_config_pm_on_fail='ignore', pnpm_config_pm_on_fail='ignore',
                       pnpm_config_offline='true', pnpm_config_verify_deps_before_run='false')
    if goal == 'test':
        environment.update(DF_TEST_REPORTS=str(reports), NODE_OPTIONS='--require=' + str(REPORT_HOOK))
    try:
        code, output = sandbox.run_bounded([manager, 'run', script_name],
                timeout=int(os.environ.get('DF_CHECK_SECONDS', '180')), cwd=project, env=environment)
    finally:
        stop_project_processes()
    after = sandbox.snapshot()
    sandbox.check_runtime_changes(before, after, set())
    if goal != 'test':
        return result(identity, 'BUILD_PASSED_NOT_TESTED' if code == 0 else (
            'TYPECHECK_FAILED' if goal == 'typecheck' else 'FRONTEND_BUILD_FAILED'), output, code)
    try:
        checked = test_result(identity, reports, code, output)
        if checked['classification'] == 'TEST_EXECUTION_UNVERIFIED':
            checked['output'] += ('\nNo attested tests. Node --test and Vitest CLI receive structured reporters; '
                                  'preserve NODE_OPTIONS in wrappers. Build success is not test evidence.')
        return checked
    except (ValueError, OSError, KeyError, TypeError, ET.ParseError):
        return result(identity, 'CHECK_RESULT_UNAVAILABLE', output + '\nMissing, malformed or incomplete test report', code)


def dispatch(sandbox, args, preparing=False):
    # 前端项目脚本只能在 Java 创建的一次性容器中启动。
    if os.environ.get('DF_FRONTEND_COPY') != 'true':
        raise sandbox.PolicyError('FRONTEND_ISOLATION', 'Frontend checks require a disposable copy')
    identity = {'checkType': 'FRONTEND', 'directory': args.get('directory', '.'),
                'goal': args.get('goal', ''), 'snapshot': sandbox.snapshot()['snapshot'],
                'module': None, 'selectedTest': None, 'sourceView': sandbox.source_view(),
                'executionStatus': 'NOT_STARTED', 'configurationSnapshot': configuration_snapshot(sandbox)}
    try:
        # 工具版本探针不进入项目目录，不允许项目的包管理器配置参与探针初始化。
        code, node = sandbox.run_bounded(['node', '--version'], cwd=pathlib.Path('/tmp'))
        code2, pnpm = sandbox.run_bounded(['pnpm', '--version'], cwd=pathlib.Path('/tmp'))
        code3, npm = sandbox.run_bounded(['npm', '--version'], cwd=pathlib.Path('/tmp'))
        if code or code2 or code3 or node.strip() != NODE_VERSION or pnpm.strip() != PNPM_VERSION \
                or npm.strip() != NPM_VERSION:
            return result(identity, 'FRONTEND_ENVIRONMENT_MISMATCH')
        return prepare(sandbox, args) if preparing else check(sandbox, args)
    except sandbox.CheckArgumentError:
        raise
    except InvalidLock as error:
        return result(identity, 'LOCKFILE_INVALID', str(error))
    except CheckUnavailable as error:
        return {**result(identity, 'CHECK_NOT_AVAILABLE', str(error)), 'availableChecks': describe(sandbox)}
    except Unsupported as error:
        return {**result(identity, 'UNSUPPORTED_FRONTEND_CONFIGURATION', str(error)), 'availableChecks': describe(sandbox)}
    except sandbox.PolicyError as error:
        if error.rule == 'COMMAND_BUDGET':
            return result({**identity, 'executionStatus': 'UNKNOWN'}, 'CHECK_TIMEOUT', 'Frontend command exceeded time or output budget')
        raise
    except (ValueError, OSError, KeyError, TypeError):
        return result(identity, 'CHECK_RESULT_UNAVAILABLE', 'Frontend metadata, dependencies or report could not be read')
