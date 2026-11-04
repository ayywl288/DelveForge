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


PNPM_LEGACY_VERSION = '8.15.9'


def yaml_documents(path):
    import yaml
    if not path.is_file() or path.is_symlink() or path.stat().st_size > 2097152:
        raise Unsupported('Expected bounded regular YAML metadata')
    try:
        raw = path.read_text(encoding='utf-8')
        if any(isinstance(token, (yaml.tokens.AliasToken, yaml.tokens.AnchorToken, yaml.tokens.TagToken))
               for token in yaml.scan(raw)):
            raise Unsupported('YAML aliases, anchors and tags are unsupported')
        return list(yaml.safe_load_all(raw))
    except yaml.YAMLError:
        raise InvalidLock('Invalid package-manager YAML') from None


def root_only_configuration(path):
    documents = yaml_documents(path)
    if len(documents) != 1 or not isinstance(documents[0], dict):
        raise Unsupported('Expected workspace configuration')
    data = documents[0]
    if set(data) - {'packages', 'allowBuilds', 'onlyBuiltDependencies', 'ignoredBuiltDependencies'}:
        raise Unsupported('Workspace hooks, catalogs and custom settings are unavailable')
    patterns = data.get('packages', ['.'])
    if not isinstance(patterns, list) or len(patterns) > 128 or any(not isinstance(value, str)
            or not re.fullmatch(r'!?[A-Za-z0-9_.*?/@-]{1,200}', value) or '..' in value.split('/')
            or value.startswith('/') for value in patterns):
        raise Unsupported('Workspace package patterns must stay inside the repository')
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
    # 构建许可保留为源数据，永不交给联网安装器；安装生命周期始终禁用。
    return {'packages': patterns}


def installation(sandbox, project):
    root = project
    workspace = None
    for parent in [project, *project.parents]:
        if not parent.is_relative_to(sandbox.ROOT):
            break
        config = parent / 'pnpm-workspace.yaml'
        manifest_path = parent / 'package.json'
        if config.is_file():
            workspace = root_only_configuration(config)
            root = parent
            break
        if manifest_path.is_file():
            data = json.loads(manifest_path.read_text(encoding='utf-8'))
            if data.get('workspaces'):
                patterns = data['workspaces']
                patterns = patterns.get('packages') if isinstance(patterns, dict) else patterns
                if not isinstance(patterns, list) or len(patterns) > 128 or any(not isinstance(value, str)
                        or not re.fullmatch(r'!?[A-Za-z0-9_.*?/@-]{1,200}', value)
                        or '..' in value.split('/') or value.startswith('/') for value in patterns):
                    raise Unsupported('Unsafe npm workspace patterns')
                workspace = {'npm': patterns}
                root = parent
                break
    members = {'.': root / 'package.json'}
    if workspace:
        for name in sandbox.all_files():
            path = sandbox.ROOT / name
            if path.name == 'package.json' and path.is_relative_to(root):
                members[path.parent.relative_to(root).as_posix()] = path
    if len(members) > 128:
        raise Unsupported('Workspace exceeds 128 bounded package manifests')
    return root, workspace, members


def package_manager(root, manifest):
    npm = next((name for name in ('npm-shrinkwrap.json', 'package-lock.json') if (root / name).is_file()), None)
    pnpm = 'pnpm-lock.yaml' if (root / 'pnpm-lock.yaml').is_file() else None
    if npm and pnpm:
        raise Unsupported('Conflicting npm and pnpm lockfiles; select the intended manager')
    declared = manifest.get('packageManager')
    match = re.fullmatch(r'(npm|pnpm)@(\d+)\.\d+\.\d+(?:\+sha(?:224|512)\.[a-f0-9]+)?', str(declared))
    if 'packageManager' in manifest and (not match or match[1] == 'npm' and int(match[2]) not in range(9, 12)
            or match[1] == 'pnpm' and int(match[2]) not in range(8, 13)):
        raise Unsupported('Required package-manager toolchain is unavailable')
    manager = 'pnpm' if pnpm else 'npm' if npm else match[1] if match else 'npm'
    if match and match[1] != manager:
        raise Unsupported('packageManager conflicts with the selected lockfile')
    if pnpm and match and int(match[2]) == 8 and str(yaml_documents(root / pnpm)[-1].get('lockfileVersion')) == '9.0':
        raise Unsupported('Declared pnpm 8 toolchain cannot consume a generation 9 lockfile')
    return manager, pnpm or npm


def pnpm_command(root, manifest, lockfile):
    legacy = False
    if lockfile == 'pnpm-lock.yaml':
        documents = yaml_documents(root / lockfile)
        legacy = str(documents[-1].get('lockfileVersion')) in ('6', '6.0')
    elif str(manifest.get('packageManager', '')).startswith('pnpm@8.'):
        legacy = True
    return ('/opt/pnpm8/bin/pnpm', PNPM_LEGACY_VERSION) if legacy else ('pnpm', PNPM_VERSION)


def public_tarball(value, name=None, version=None):
    from proxy import registry_tarball_url
    try:
        return registry_tarball_url(value, name, version)
    except (ValueError, AttributeError):
        raise Unsupported('Only identity-preserving known public registry tarballs are available') from None


def prepared_manifest(manifest, manager):
    if not isinstance(manifest, dict):
        raise Unsupported('Expected a package.json object')
    if any(name in manifest for name in ('pnpm', 'devEngines')):
        raise Unsupported('Custom pnpm hooks/settings or runtime provisioning are unavailable')
    for field in ('dependencies', 'devDependencies', 'optionalDependencies', 'peerDependencies'):
        values = manifest.get(field, {})
        if not isinstance(values, dict):
            raise Unsupported('Expected dependency object: ' + field)
        for name, version in values.items():
            workspace = manager == 'pnpm' and isinstance(version, str) and re.fullmatch(r'workspace:[~^*0-9.xX+<>=| -]+', version)
            if not re.fullmatch(r'(?:@[a-z0-9_.-]+/)?[a-z0-9_.-]+', name) or not isinstance(version, str) \
                    or not workspace and re.search(r'[:/\\]|[\r\n]', version):
                raise Unsupported('Only public registry and native workspace dependencies are available')
    return {key: manifest[key] for key in ('name', 'version', 'private', 'type', 'dependencies', 'devDependencies',
        'optionalDependencies', 'peerDependencies', 'peerDependenciesMeta', 'engines', 'workspaces') if key in manifest}


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
    names = {'package.json', 'package-lock.json', 'npm-shrinkwrap.json', 'pnpm-lock.yaml', 'pnpm-workspace.yaml', '.npmrc'}
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
    root, workspace, members = installation(sandbox, project)
    manifest = json.loads(sandbox.text(sandbox.path_for(
        'package.json' if relative == '.' else relative + '/package.json')))
    root_manifest = json.loads((root / 'package.json').read_text(encoding='utf-8'))
    manager, lockfile = package_manager(root, root_manifest)
    for parent in [project, *project.parents]:
        if not parent.is_relative_to(sandbox.ROOT):
            break
        registry = parent / '.npmrc'
        registry_path = registry.relative_to(sandbox.ROOT).as_posix()
        if registry.exists() and not sandbox.approved_resource(registry_path) or registry_path in sandbox.RESOURCE_CAPABILITIES \
                and sandbox.RESOURCE_CAPABILITIES[registry_path]['kind'] == 'PRESERVED_ONLY':
            raise Unsupported('Project npm configuration is withheld or not a validated public resource')
        if any((parent / name).exists() for name in ('.pnpmfile.cjs', 'pnpmfile.cjs', 'yarn.lock')):
            raise Unsupported('Package-manager hooks and mixed toolchains are unavailable')
    for path in members.values():
        prepared_manifest(json.loads(path.read_text(encoding='utf-8')), manager)
    deps = {**root_manifest.get('dependencies', {}), **root_manifest.get('devDependencies', {}),
            **manifest.get('dependencies', {}), **manifest.get('devDependencies', {})}
    if 'vue' not in deps or 'vite' not in deps or not re.match(r'^[~^]?3(?:\.|$)', str(deps['vue'])):
        raise Unsupported('Expected Vue 3/Vite project dependencies')
    scripts = manifest.get('scripts', {})
    script_name = next((name for name in SCRIPT_NAMES[goal] if name in scripts), None)
    script = scripts.get(script_name)
    if lockfile and validate_lock:
        if manager == 'pnpm':
            pnpm_lock(root / lockfile, members)
        else:
            npm_lock(root / lockfile, root_manifest, members)
    # 没有可用检查不等于执行失败；已知失败仍由冻结规则要求同目标成功重验。
    if script_name is None:
        raise CheckUnavailable('No declared ' + goal + ' script is available; project configuration was not changed')
    if not isinstance(script, str) or not script.strip() or len(script) > 8192 or '\x00' in script:
        raise Unsupported('Expected a bounded nonempty declared script')
    return project, relative, goal, script_name, prepared_manifest(manifest, manager), manager, lockfile


def pnpm_lock(lock, members=None):
    documents = yaml_documents(lock)
    if not 1 <= len(documents) <= 2:
        raise Unsupported('Expected a supported pnpm lockfile')
    members = members or {'.': lock.parent / 'package.json'}
    for data in documents:
        if not isinstance(data, dict) or str(data.get('lockfileVersion')) not in ('9.0', '6.0', '6') \
                or not isinstance(data.get('packages', {}), dict):
            raise Unsupported('Available pnpm toolchains support lockfile generations 6 and 9')
        legacy = str(data['lockfileVersion']) in ('6', '6.0')
        importers = data.get('importers', {'.': {field: data[field] for field in
            ('dependencies', 'devDependencies', 'optionalDependencies') if field in data}} if legacy else None)
        if not isinstance(importers, dict):
            raise Unsupported('Expected native pnpm importer metadata')
        allowed = {'lockfileVersion', 'settings', 'importers', 'packages', 'snapshots'}
        if legacy:
            allowed.update(('dependencies', 'devDependencies', 'optionalDependencies'))
        if set(data) - allowed \
                or set(data.get('settings', {})) - {'autoInstallPeers', 'excludeLinksFromLockfile'}:
            raise Unsupported('Custom lockfile settings and configuration dependencies are unavailable')
        for importer, values in importers.items():
            if importer not in members or not isinstance(values, dict) or values.get('configDependencies'):
                raise Unsupported('Lockfile importers must name repository workspace manifests')
            for field in ('dependencies', 'devDependencies', 'optionalDependencies'):
                for entry in values.get(field, {}).values():
                    version = entry.get('version', '') if isinstance(entry, dict) else entry
                    if isinstance(version, str) and ':' in version and not version.startswith('link:'):
                        raise Unsupported('Non-registry importer resolution is unavailable')
                    if isinstance(version, str) and version.startswith('link:'):
                        linked = (lock.parent / importer / version[5:]).resolve()
                        if not linked.is_relative_to(lock.parent.resolve()) or linked.relative_to(lock.parent.resolve()).as_posix() not in members:
                            raise Unsupported('Workspace links must name internal package manifests')
        for package, entry in data.get('packages', {}).items():
            if not re.fullmatch(r'/?(?:@[a-z0-9_.-]+/)?[a-z0-9_.-]+@[0-9][A-Za-z0-9.+\-]*(?:\([A-Za-z0-9_@./+\-]+\))*', str(package)):
                raise Unsupported('Only registry package identities are available')
            resolution = entry.get('resolution', {}) if isinstance(entry, dict) else {}
            if set(resolution) - {'integrity', 'tarball'} or not registry_integrity(resolution.get('integrity')):
                raise Unsupported('Registry packages require valid integrity and trusted sources')
            if 'tarball' in resolution:
                name, _, version = str(package).lstrip('/').rpartition('@')
                public_tarball(resolution['tarball'], name, version.split('(')[0])
    if len(documents) == 2:
        toolchain = documents[0]['importers'].get('.')
        if toolchain != {'configDependencies': {}, 'packageManagerDependencies': {
                'pnpm': {'specifier': PNPM_VERSION, 'version': PNPM_VERSION}}}:
            raise Unsupported('Only the pinned pnpm toolchain document is supported')
    return documents


def npm_lock(lock, manifest, members=None):
    if not lock.is_file() or lock.is_symlink() or lock.stat().st_size > 2097152:
        raise Unsupported('Expected a bounded regular npm lockfile')
    try:
        data = json.loads(lock.read_text(encoding='utf-8'))
    except ValueError:
        raise InvalidLock('Invalid npm lockfile JSON') from None
    if not isinstance(data, dict) or data.get('lockfileVersion') not in (2, 3) or not isinstance(data.get('packages'), dict):
        raise Unsupported('Available npm supports lockfileVersion 2 and 3')
    members = members or {'.': lock.parent / 'package.json'}
    for path, entry in data['packages'].items():
        if not isinstance(entry, dict) or '..' in path.split('/') or path.startswith('/') or '\\' in path:
            raise Unsupported('Unsafe npm package path')
        if path == '' or path in members:
            continue
        if not re.fullmatch(r'node_modules/[a-zA-Z0-9_@.\-/]+', path):
            raise Unsupported('Unsafe npm installation path')
        if entry.get('link'):
            if entry.get('resolved') not in members:
                raise Unsupported('Only internal npm workspace links are available')
        elif not registry_integrity(entry.get('integrity')):
            raise Unsupported('npm registry packages require valid integrity')
        elif entry.get('resolved'):
            name = entry.get('name', path.rsplit('node_modules/', 1)[-1])
            public_tarball(entry['resolved'], name, entry.get('version'))
    # 完整性/一致性最终交给真实 npm ci；只在临时锁副本映射已知公共源，不改版本或依赖关系。
    def mapped(value):
        if isinstance(value, dict):
            return {key: public_tarball(item) if key == 'resolved' and isinstance(item, str)
                    and item.startswith(('http:', 'https:')) else mapped(item) for key, item in value.items()}
        if isinstance(value, list):
            return [mapped(item) for item in value]
        return value
    return mapped(data)


def result(identity, classification, output='', code=None, **counts):
    return {**identity, 'classification': classification, 'exitCode': code,
            'tests': 0, 'executedTests': 0, 'skipped': 0, 'failures': 0, 'errors': 0,
            'reports': 0, **counts, 'output': output[-18000:]}


def prepare(sandbox, args):
    project, relative, goal, _, manifest, manager, lockfile = metadata(sandbox, args)
    root, workspace, members = installation(sandbox, project)
    root_manifest = json.loads((root / 'package.json').read_text(encoding='utf-8'))
    identity = {'checkType': 'FRONTEND', 'directory': relative, 'goal': goal,
                'snapshot': sandbox.snapshot()['snapshot'], 'module': None, 'selectedTest': None, 'packageManager': manager,
                'sourceView': sandbox.source_view(), 'executionStatus': 'NOT_STARTED',
                'configurationSnapshot': configuration_snapshot(sandbox),
                'reproducibility': 'SOURCE_LOCK' if lockfile else 'RUN_FROZEN_RESOLUTION'}
    preparation = PREPARATION
    if preparation.exists():
        raise Unsupported('Dependency preparation directory already exists')
    preparation.mkdir()
    for directory, source in members.items():
        target = preparation / directory / 'package.json'
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(prepared_manifest(json.loads(source.read_text(encoding='utf-8')), manager)), encoding='utf-8')
    if workspace and 'packages' in workspace:
        import yaml
        (preparation / 'pnpm-workspace.yaml').write_text(yaml.safe_dump(workspace), encoding='utf-8')
    if lockfile:
        if manager == 'npm':
            (preparation / lockfile).write_text(json.dumps(npm_lock(root / lockfile, root_manifest, members)), encoding='utf-8')
        else:
            import yaml
            documents = pnpm_lock(root / lockfile, members)
            # 仅移除已核验为当前固定 pnpm 的工具链文档；项目锁和 workspace 关系原样交给原生管理器。
            # 禁止自动管理工具版本时，pnpm 的项目锁读取器只接受一个文档。
            documents = documents[-1:]
            for data in documents:
                for entry in data.get('packages', {}).values():
                    resolution = entry.get('resolution', {})
                    if 'tarball' in resolution:
                        resolution['tarball'] = public_tarball(resolution['tarball'])
            (preparation / lockfile).write_text(yaml.safe_dump_all(documents, sort_keys=False), encoding='utf-8')
    binary = manager
    identity['packageManagerVersion'] = NPM_VERSION
    if manager == 'pnpm':
        binary, version = pnpm_command(root, root_manifest, lockfile)
        identity['packageManagerVersion'] = version
        code, actual = sandbox.run_bounded([binary, '--version'], cwd=pathlib.Path('/tmp'))
        if code or actual.strip() != version:
            return result(identity, 'FRONTEND_ENVIRONMENT_MISMATCH', 'Selected pinned pnpm toolchain is unavailable')
        command = [binary, 'install', '--frozen-lockfile' if lockfile else '--no-frozen-lockfile',
                   '--ignore-scripts', '--ignore-pnpmfile', '--network-concurrency=2',
                   '--config.registry=http://df-artifacts:8080/npm/', '--config.verify-store-integrity=true',
                   '--config.side-effects-cache=false', '--config.package-import-method=copy',
                   '--store-dir=/workspace/.df-frontend-store', '--config.node-linker=isolated',
                   '--config.update-notifier=false', '--config.manage-package-manager-versions=false']
        if version == PNPM_VERSION:
            command += ['--no-runtime', '--config.strict-dep-builds=false']
    else:
        command = ['npm', 'ci' if lockfile else 'install', '--ignore-scripts', '--package-lock=true',
                   '--registry=http://df-artifacts:8080/npm/', '--maxsockets=2',
                   '--cache=/workspace/.df-frontend-npm-cache', '--no-audit', '--no-fund', '--update-notifier=false']
    for parent in reversed([project, *project.parents]):
        if not parent.is_relative_to(sandbox.ROOT):
            continue
        registry = (parent / '.npmrc').relative_to(sandbox.ROOT).as_posix()
        for name, value in sandbox.RESOURCE_CAPABILITIES.get(registry, {}).get('settings', {}).items():
            if name in ('shamefully-hoist', 'auto-install-peers', 'strict-peer-dependencies', 'legacy-peer-deps', 'install-links'):
                command.append(('--config.' if manager == 'pnpm' else '--') + name + '=' + value)
    code, output = sandbox.run_bounded(command, timeout=int(os.environ.get('DF_CHECK_SECONDS', '180')) * 3, cwd=preparation)
    if code:
        classification = ('LOCKFILE_INVALID' if re.search(r'LOCKFILE|INTEGRITY|TARBALL_INTEGRITY|EUSAGE|lock file', output)
                          else 'DEPENDENCY_MISSING')
        return result(identity, classification, output, code)
    frozen = preparation / (lockfile or ('pnpm-lock.yaml' if manager == 'pnpm' else 'package-lock.json'))
    identity['resolutionSha256'] = hashlib.sha256(frozen.read_bytes()).hexdigest() if frozen.is_file() else None
    if identity['resolutionSha256'] is None:
        return result(identity, 'LOCKFILE_INVALID', 'Native installation did not freeze dependency resolution')
    return result(identity, 'DEPENDENCIES_PREPARED', output, code)


def read_report(path):
    if not path.is_file() or path.is_symlink() or path.stat().st_size > 2097152:
        raise ValueError('Missing or unbounded test report')
    return path.read_text(encoding='utf-8')


def normalize_report(runner, path, cases=None, sandbox=None):
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
        if cases is not None:
            for suite in suites:
                source = pathlib.Path(suite.get('name', ''))
                relative = source.relative_to(sandbox.ROOT).as_posix()
                for test in suite['assertionResults']:
                    outcome = {'passed': 'PASSED', 'failed': 'FAILED'}.get(test['status'], 'SKIPPED')
                    cases.append(sandbox.test_case(runner + ':' + relative + '#' + test['fullName'], outcome,
                                 '\n'.join(test.get('failureMessages', [])), source))
        return dict(tests=total, executedTests=passed + failed, skipped=pending + todo, failures=failed, errors=0)
    if runner == 'node-junit':
        if '<!DOCTYPE' in raw.upper() or '<!ENTITY' in raw.upper():
            raise ValueError('XML declarations are unsupported')
        root = ET.fromstring(raw)
        if root.tag not in ('testsuite', 'testsuites'):
            raise ValueError('Expected JUnit suites')
        # Node 嵌套 suite 的计数包含子 suite；以实际 testcase 计数，避免重复累计。
        junit_cases = list(root.iter('testcase'))
        if len(junit_cases) > 100000:
            raise ValueError('Test count budget exceeded')
        failures = sum(case.find('failure') is not None for case in junit_cases)
        errors = sum(case.find('error') is not None for case in junit_cases)
        skipped = sum(case.find('skipped') is not None for case in junit_cases)
        if failures + errors + skipped > len(junit_cases):
            raise ValueError('Contradictory JUnit cases')
        if cases is not None:
            for case in junit_cases:
                source = pathlib.Path(case.get('file', case.get('classname', '')))
                if not source.is_absolute():
                    source = sandbox.ROOT / source
                problem = case.find('error')
                if problem is None:
                    problem = case.find('failure')
                outcome = ('ERROR' if case.find('error') is not None else 'FAILED' if problem is not None
                           else 'SKIPPED' if case.find('skipped') is not None else 'PASSED')
                cases.append(sandbox.test_case(runner + ':' + str(source).removeprefix(str(sandbox.ROOT))
                             + '#' + case.get('name', ''), outcome,
                             '' if problem is None else (problem.text or ''), source))
        return dict(tests=len(junit_cases), executedTests=len(junit_cases) - skipped, skipped=skipped,
                    failures=failures, errors=errors)
    raise ValueError('Unattested test runner')


def test_result(identity, directory, code, output, sandbox=None):
    receipts = sorted(directory.glob('*.receipt'))
    if len(receipts) > 32:
        return result(identity, 'CHECK_RESULT_UNAVAILABLE', output + '\nTest report count budget exceeded', code)
    if not receipts:
        return result(identity, 'TEST_EXECUTION_UNVERIFIED' if code == 0 else 'CHECK_PROCESS_FAILED', output, code)
    counts = dict(tests=0, executedTests=0, skipped=0, failures=0, errors=0, reports=0)
    runners = set()
    seen = set()
    runner_failed = False
    cases = []
    for receipt in receipts:
        data = json.loads(read_report(receipt))
        runner, name = data['runner'], data['report']
        if runner not in ('node-junit', 'vitest-json') or not isinstance(name, str) \
                or pathlib.Path(name).name != name or name in seen or type(data['exitCode']) is not int:
            raise ValueError('Invalid runner receipt')
        seen.add(name)
        report = normalize_report(runner, directory / name, cases if sandbox is not None else None, sandbox)
        for key, count in report.items():
            counts[key] += count
        counts['reports'] += 1
        runners.add(runner)
        runner_failed |= data['exitCode'] != 0
    identity = {**identity, 'runner': '+'.join(sorted(runners))}
    failed = counts['failures'] + counts['errors'] > 0
    classification = ('TESTS_FAILED' if failed else 'CHECK_PROCESS_FAILED' if code != 0 or runner_failed
                      else 'TESTS_PASSED' if counts['executedTests'] > 0 else 'TEST_EXECUTION_UNVERIFIED')
    manifest = {'testManifest': sandbox.test_manifest(cases, counts)} if sandbox is not None else {}
    return result(identity, classification, output, code, **counts, **manifest)


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
    project, relative, goal, script_name, _, manager, lockfile = metadata(sandbox, args)
    root, workspace, members = installation(sandbox, project)
    root_manifest = json.loads((root / 'package.json').read_text(encoding='utf-8'))
    before = sandbox.snapshot()
    identity = {'checkType': 'FRONTEND', 'directory': relative, 'goal': goal,
                'snapshot': before['snapshot'], 'module': None, 'selectedTest': None, 'packageManager': manager,
                'sourceView': sandbox.source_view(), 'executionStatus': 'EXECUTED',
                'configurationSnapshot': configuration_snapshot(sandbox)}
    if not any((DEPENDENCIES / directory / 'node_modules').is_dir() for directory in members):
        return result({**identity, 'executionStatus': 'NOT_STARTED'}, 'DEPENDENCY_MISSING',
                      'Frozen dependencies are unavailable')
    sandbox.FRONTEND_OUTPUT_ROOT = root
    for directory in members:
        modules = DEPENDENCIES / directory / 'node_modules'
        if modules.is_dir():
            shutil.copytree(modules, root / directory / 'node_modules', symlinks=True)
    reports = project / '.df-check'
    reports.mkdir()
    environment = dict(os.environ, CI='true', npm_config_update_notifier='false',
                       npm_config_audit='false', npm_config_fund='false', npm_config_offline='true',
                       npm_config_pm_on_fail='ignore', pnpm_config_pm_on_fail='ignore',
                       pnpm_config_offline='true', pnpm_config_verify_deps_before_run='false')
    environment.update(DF_CHECK_REPORTS=str(reports), NODE_OPTIONS='--require=' + str(REPORT_HOOK))
    if goal == 'test':
        environment['DF_TEST_REPORTS'] = str(reports)
    binary = manager
    if manager == 'pnpm':
        binary, version = pnpm_command(root, root_manifest, lockfile)
        if binary.startswith('/'):
            environment['PATH'] = str(pathlib.Path(binary).parent) + os.pathsep + environment.get('PATH', '')
    try:
        code, output = sandbox.run_bounded([binary, 'run', script_name],
                timeout=int(os.environ.get('DF_CHECK_SECONDS', '180')), cwd=project, env=environment)
    finally:
        stop_project_processes()
    after = sandbox.snapshot()
    sandbox.check_runtime_changes(before, after, set())
    executions = sorted(reports.glob('*.execution'))
    if len(executions) > 64:
        return result(identity, 'CHECK_RESULT_UNAVAILABLE', 'Check execution receipt budget exceeded', code)
    identity['checkExecutions'] = len(executions)
    identity['observedPhase'] = 'PROJECT_NODE_PROCESS' if executions else 'PACKAGE_SCRIPT_ONLY'
    identity['unprovenCapabilities'] = [] if executions else ['PROJECT_CHECK_EXECUTION']
    if goal != 'test':
        return result(identity, 'BUILD_PASSED_NOT_TESTED' if code == 0 else (
            'TYPECHECK_FAILED' if goal == 'typecheck' else 'FRONTEND_BUILD_FAILED'), output, code)
    try:
        checked = test_result(identity, reports, code, output, sandbox)
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
