"""固定 npm/pnpm/Vue 检查。仅在一次性副本内准备依赖和执行；不解释模型命令。"""
import json
import os
import pathlib
import re
import shutil

NODE_VERSION = 'v22.16.0'
PNPM_VERSION = '12.9.1'
NPM_VERSION = '10.9.2'
PREPARATION = pathlib.Path('/workspace/.df-frontend-dependencies')
DEPENDENCIES = pathlib.Path('/dependencies/.df-frontend-dependencies')
GENERATED = {'node_modules', 'dist', 'coverage', '.vite', '.df-check'}
SCRIPTS = {
    'typecheck': {'vue-tsc': [['vue-tsc']], 'vue-tsc -b': [['vue-tsc', '-b']],
                  'vue-tsc --noEmit': [['vue-tsc', '--noEmit']],
                  'vue-tsc --noEmit --skipLibCheck': [['vue-tsc', '--noEmit', '--skipLibCheck']]},
    'build': {'vite build': [['vite', 'build']],
              'vue-tsc -b && vite build': [['vue-tsc', '-b'], ['vite', 'build']],
              'vue-tsc && vite build': [['vue-tsc'], ['vite', 'build']],
              'vue-tsc --noEmit && vite build': [['vue-tsc', '--noEmit'], ['vite', 'build']]},
    'test': {'vitest run': [['vitest', 'run']], 'vitest': [['vitest', 'run']]},
}
SCRIPT_NAMES = {'typecheck': ('typecheck', 'type-check'), 'build': ('build',), 'test': ('test', 'test:unit')}


class Unsupported(ValueError):
    pass


class InvalidLock(Unsupported):
    pass


class CheckUnavailable(Unsupported):
    pass


def metadata(sandbox, args):
    goal = args.get('goal')
    if goal not in SCRIPTS or set(args) - {'goal', 'directory'}:
        raise sandbox.CheckArgumentError('Choose typecheck, build or test without command arguments')
    project = sandbox.path_for(args.get('directory', 'frontend'), True)
    relative = project.relative_to(sandbox.ROOT).as_posix()
    # 独立单工程是当前明确支持范围；不加载 workspace、pnpmfile 或项目级 npm 配置。
    for parent in [project, *project.parents]:
        if not parent.is_relative_to(sandbox.ROOT):
            break
        if any((parent / name).exists() for name in (
                'pnpm-workspace.yaml', '.pnpmfile.cjs', 'pnpmfile.cjs', '.npmrc',
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
        supported_majors = range(9, 13) if manager == 'pnpm' else range(9, 11)
        if not match or match[1] != manager or int(match[2]) not in supported_majors:
            raise Unsupported('packageManager conflicts with the lockfile or supported lockfile toolchains')
    if any(name in manifest for name in ('pnpm', 'workspaces', 'devEngines')):
        raise Unsupported('Custom pnpm configuration or automatic runtime provisioning is unsupported')
    deps = {**manifest.get('dependencies', {}), **manifest.get('devDependencies', {})}
    if not all(name in deps for name in ('vue', 'vite', 'typescript')):
        raise Unsupported('Expected Vue/Vite/TypeScript dependencies')
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
    if manager == 'pnpm':
        pnpm_lock(lock)
    else:
        npm_lock(lock, manifest)
    # 没有可用检查不等于执行失败；已知失败仍由冻结规则要求同目标成功重验。
    if script_name is None:
        raise CheckUnavailable('No declared ' + goal + ' script is available; project configuration was not changed')
    if not isinstance(script, str) or script not in SCRIPTS[goal]:
        raise Unsupported('Unsupported declared ' + goal + ' script; configuration was not changed')
    if (goal == 'typecheck' and 'vue-tsc' not in deps) or (goal == 'test' and 'vitest' not in deps):
        raise CheckUnavailable('The optional tool for ' + goal + ' is not declared')
    commands = SCRIPTS[goal][script]
    if any(command[0] not in deps for command in commands):
        raise CheckUnavailable('A declared check requires an unavailable optional tool')
    # 还原阶段只传经过检查的依赖元数据，原 scripts 不进入联网准备目录。
    prepared_manifest = {key: manifest[key] for key in (
        'name', 'version', 'private', 'type', 'dependencies', 'devDependencies',
        'optionalDependencies', 'peerDependencies', 'peerDependenciesMeta', 'engines') if key in manifest}
    return project, relative, goal, commands, prepared_manifest, manager, lockfiles[0]


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
            if set(resolution) != {'integrity'} or not re.fullmatch(
                    r'sha512-[A-Za-z0-9+/]{86}==', str(resolution.get('integrity', ''))):
                raise Unsupported('Every package requires registry SHA-512 integrity; local/git/URL packages are unsupported')
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
                or not re.fullmatch(r'sha512-[A-Za-z0-9+/]{86}==', str(entry.get('integrity', ''))):
            raise Unsupported('npm packages require public registry tarballs and SHA-512 integrity; links are unsupported')


def result(identity, classification, output='', code=None, **counts):
    return {**identity, 'classification': classification, 'exitCode': code,
            'tests': 0, 'executedTests': 0, 'skipped': 0, 'failures': 0, 'errors': 0,
            'reports': 0, **counts, 'output': output[-18000:]}


def prepare(sandbox, args):
    project, relative, goal, _, manifest, manager, lockfile = metadata(sandbox, args)
    identity = {'checkType': 'FRONTEND', 'directory': relative, 'goal': goal,
                'snapshot': sandbox.snapshot()['snapshot'], 'module': None, 'selectedTest': None, 'packageManager': manager}
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
    code, output = sandbox.run_bounded(command, timeout=int(os.environ.get('DF_CHECK_SECONDS', '180')) * 3,
                                       cwd=preparation)
    if code:
        classification = ('LOCKFILE_INVALID' if re.search(r'LOCKFILE|INTEGRITY|TARBALL_INTEGRITY|EUSAGE|lock file', output)
                          else 'DEPENDENCY_MISSING')
        return result(identity, classification, output, code)
    return result(identity, 'DEPENDENCIES_PREPARED', output, code)


def check(sandbox, args):
    project, relative, goal, commands, _, manager, _ = metadata(sandbox, args)
    before = sandbox.snapshot()
    identity = {'checkType': 'FRONTEND', 'directory': relative, 'goal': goal,
                'snapshot': before['snapshot'], 'module': None, 'selectedTest': None, 'packageManager': manager}
    preparation = DEPENDENCIES
    modules = preparation / 'node_modules'
    if not modules.is_dir():
        return result(identity, 'DEPENDENCY_MISSING', 'Frozen dependencies are unavailable')
    # 唯一忽略范围仅属于此一次性检查副本；权威源码的快照规则始终不变。
    sandbox.FRONTEND_OUTPUT_ROOT = project
    shutil.copytree(modules, project / 'node_modules', symlinks=True)
    report = project / '.df-check' / 'vitest.json'
    report.parent.mkdir()
    code = 0
    output = ''
    for command in commands:
        binary = project / 'node_modules' / '.bin' / command[0]
        if not binary.is_file():
            return result(identity, 'DEPENDENCY_MISSING', 'Required local tool is unavailable')
        argv = [str(binary), *command[1:]]
        if goal == 'test':
            argv += ['--reporter=json', '--outputFile=' + str(report)]
        code, part = sandbox.run_bounded(argv, timeout=int(os.environ.get('DF_CHECK_SECONDS', '180')), cwd=project)
        output += part
        if code:
            break
    after = sandbox.snapshot()
    sandbox.check_runtime_changes(before, after, set())
    if goal != 'test':
        return result(identity, 'BUILD_PASSED_NOT_TESTED' if code == 0 else (
            'TYPECHECK_FAILED' if goal == 'typecheck' else 'FRONTEND_BUILD_FAILED'), output, code)
    if not report.is_file() or report.is_symlink() or report.stat().st_size > 2097152:
        return result(identity, 'CHECK_RESULT_UNAVAILABLE', output, code)
    data = json.loads(report.read_text(encoding='utf-8'))
    keys = ['numTotalTests', 'numPassedTests', 'numFailedTests', 'numPendingTests', 'numTodoTests']
    counts = [data.get(key, 0) for key in keys]
    if any(type(count) is not int or count < 0 for count in counts):
        return result(identity, 'CHECK_RESULT_UNAVAILABLE', output, code)
    total, passed, failed, pending, todo = counts
    if passed + failed + pending + todo != total:
        return result(identity, 'CHECK_RESULT_UNAVAILABLE', output, code)
    classification = ('TESTS_FAILED' if failed else 'TESTS_PASSED' if code == 0 and passed > 0
                      else 'BUILD_PASSED_NOT_TESTED' if code == 0 else 'CHECK_RESULT_UNAVAILABLE')
    return result(identity, classification, output, code, reports=1, tests=total,
                  executedTests=passed + failed, skipped=pending + todo, failures=failed)


def dispatch(sandbox, args, preparing=False):
    # 前端项目脚本只能在 Java 创建的一次性容器中启动。
    if os.environ.get('DF_FRONTEND_COPY') != 'true':
        raise sandbox.PolicyError('FRONTEND_ISOLATION', 'Frontend checks require a disposable copy')
    identity = {'checkType': 'FRONTEND', 'directory': args.get('directory', 'frontend'),
                'goal': args.get('goal', ''), 'snapshot': sandbox.snapshot()['snapshot'],
                'module': None, 'selectedTest': None}
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
        return result(identity, 'CHECK_NOT_AVAILABLE', str(error))
    except Unsupported as error:
        return result(identity, 'UNSUPPORTED_FRONTEND_CONFIGURATION', str(error))
    except sandbox.PolicyError as error:
        if error.rule == 'COMMAND_BUDGET':
            return result(identity, 'CHECK_TIMEOUT', 'Frontend command exceeded time or output budget')
        raise
    except (ValueError, OSError, KeyError, TypeError):
        return result(identity, 'CHECK_RESULT_UNAVAILABLE', 'Frontend metadata, dependencies or report could not be read')
