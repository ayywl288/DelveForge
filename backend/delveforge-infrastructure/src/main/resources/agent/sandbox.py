"""固定协议的容器内工具。stdin JSON，stdout 有界 JSON；不接受 shell 字符串。"""
import base64
import hashlib
import json
import os
import pathlib
import re
import signal
import shlex
import stat
import subprocess
import sys
import tarfile
import time
import xml.etree.ElementTree as ET

ROOT = pathlib.Path('/workspace')
FILE_LIMIT = int(os.environ.get('DF_FILE_BYTES', '262144'))
FILES_LIMIT = int(os.environ.get('DF_MAX_FILES', '10000'))
TOTAL_LIMIT = int(os.environ.get('DF_CAPTURE_BYTES', '16777216'))
BASELINE_FILE_LIMIT = int(os.environ.get('DF_BASELINE_FILE_BYTES', '16777216'))
BASELINE_TOTAL_LIMIT = int(os.environ.get('DF_BASELINE_TOTAL_BYTES', '134217728'))
# v1 约定检查冲突的未修改证据、应用阶段未知及应用后捕获阶段；旧工具不能推断兼容。
PROTOCOL_NAME = 'delveforge-sandbox'
PROTOCOL_VERSION = 8
PROTECTED = {'.git', '.hg', '.svn', '.ssh', '.aws', '.gnupg', '.m2', '.npmrc', '.netrc', '.pypirc'}
GENERATED = {'target'}
BASELINE_PATHS = set()
FRONTEND_OUTPUT_ROOT = None
RESOURCE_CAPABILITIES = {}


class PolicyError(Exception):
    def __init__(self, rule, message, source_changes=None):
        super().__init__(message)
        self.rule = rule
        self.stage = None
        self.source_changes = source_changes


class CheckArgumentError(ValueError):
    """明确尚未启动 Maven 的检查前置参数错误。"""
    pass


class PatchOutcomeUnknown(Exception):
    pass


def credential_path(value):
    name = value.rsplit('/', 1)[-1].casefold()
    return (name in ('.env', '.npmrc', '.pypirc', '.netrc') or name.startswith('.env.')
            or name.endswith(('.env', '.pem', '.key', '.p12', '.pfx', '.jks', '.keystore'))
            or (re.fullmatch(r'id_(rsa|dsa|ecdsa|ed25519)([._-].*)?', name) and not name.endswith('.pub'))
            or '/.aws/credentials' in '/' + value.casefold())


def path_for(value, allow_root=False, resource=False):
    if not isinstance(value, str) or '\\' in value or '\x00' in value or len(value) > 512:
        raise PolicyError('PATH_FORMAT', 'Invalid relative path')
    if value in ('', '.') and allow_root:
        return ROOT
    approved = value in RESOURCE_CAPABILITIES
    if approved and not resource:
        raise PolicyError('RESOURCE_CAPABILITY', 'Resource content is not model-visible or editable')
    if credential_path(value) and not (approved and resource):
        raise PolicyError('PROTECTED_CREDENTIAL_PATH', 'Protected credential path')
    parts = value.split('/')
    if any(part in ('', '.', '..') or ':' in part or part.endswith((' ', '.'))
           or re.fullmatch(r'(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\..*)?', part)
           or part.casefold() in PROTECTED and not (approved and resource and part == parts[-1])
           or part.casefold().startswith('.env') and not (approved and resource and part == parts[-1])
           for part in parts) or value.startswith('/'):
        raise PolicyError('FORBIDDEN_PATH', 'Forbidden path')
    if not (approved and resource) and any(part.lower().endswith(
            ('.pem', '.key', '.p12', '.pfx', '.jks', '.keystore')) for part in parts):
        raise PolicyError('PROTECTED_CREDENTIAL_PATH', 'Protected credential path')
    path = ROOT
    for part in parts:
        path = path / part
        if path.is_symlink():
            raise PolicyError('SYMLINK_PATH', 'Symlinks are unsupported')
    return path


def text(path):
    if not path.is_file():
        raise ValueError('File does not exist')
    if path.stat().st_size > FILE_LIMIT:
        raise ValueError('File size limit exceeded')
    raw = path.read_bytes()
    if b'\x00' in raw:
        raise ValueError('Binary files are unsupported')
    return raw.decode('utf-8', errors='strict')


def maven_module_roots(extra=()):
    # 基线角色来自宿主固定版本预检；仅沿实际 reactor 声明扩展新增模块，不能把测试夹具 POM 当项目。
    encoded = os.environ.get('DF_MAVEN_MODULES_B64', 'W10=')
    roots = set(json.loads(base64.b64decode(encoded, validate=True).decode('utf-8'))) | set(extra)
    entry = os.environ.get('DF_MAVEN_DIRECTORY', '.')
    if (ROOT / entry / 'pom.xml').is_file():
        roots.add(entry)
    pending = list(roots)
    seen = set()
    while pending:
        directory = pending.pop()
        if directory in seen:
            continue
        seen.add(directory)
        if len(seen) > 128 or sum(len(p.encode('utf-8')) for p in seen) > 8192:
            raise PolicyError('MAVEN_MODULE_LIMIT', 'Bounded reactor metadata limit exceeded')
        pom = ROOT / directory / 'pom.xml'
        if not pom.is_file() or pom.is_symlink() or pom.stat().st_size > FILE_LIMIT:
            continue
        raw = pom.read_bytes()
        if b'<!DOCTYPE' in raw.upper():
            continue
        try:
            node = ET.fromstring(raw)
        except ET.ParseError:
            continue
        for module in node.findall('./{*}modules/{*}module'):
            relative = (module.text or '').strip()
            child = (ROOT / directory / relative).resolve()
            if not relative or not child.is_relative_to(ROOT) or child.is_symlink():
                continue
            name = child.relative_to(ROOT).as_posix()
            roots.add(name)
            pending.append(name)
    return roots


def build_output(relative, modules=None):
    modules = maven_module_roots() if modules is None else modules
    parent = ROOT
    for part in relative.split('/'):
        child = parent / part
        if part in GENERATED and parent.relative_to(ROOT).as_posix() in modules:
            if (child / 'pom.xml').is_file():
                if child.relative_to(ROOT).as_posix() not in modules:
                    raise PolicyError('AMBIGUOUS_GENERATED_PATH', 'Target directory is not a declared reactor module')
                parent = child
                continue
            return True
        parent = child
    return False


def all_files(modules=None, retained=()):
    files = []
    names = set()
    modules = maven_module_roots() if modules is None else modules
    retained = BASELINE_PATHS | set(retained)
    retained_directories = set()
    for relative in retained:
        parent = pathlib.PurePosixPath(relative).parent
        while parent.as_posix() != '.':
            retained_directories.add(parent.as_posix())
            if len(retained_directories) > FILES_LIMIT * 4:
                raise PolicyError('SOURCE_ENTRY_LIMIT', 'Baseline directory metadata exceeds entry budget')
            parent = parent.parent
    for directory, dirs, filenames in os.walk(ROOT, followlinks=False):
        parent = pathlib.Path(directory)
        if FRONTEND_OUTPUT_ROOT is None and (parent / 'package.json').is_file() \
                and any(name in dirs for name in ('dist', 'coverage', '.vite')):
            raise PolicyError('GENERATED_PATH', 'Frontend build output is not authoritative source')
        if FRONTEND_OUTPUT_ROOT is not None and parent.is_relative_to(FRONTEND_OUTPUT_ROOT):
            from frontend import GENERATED as FRONTEND_GENERATED
            # 仅检查副本跳过生成树；不遍历 node_modules 内的合法依赖符号链接。
            for name in list(dirs):
                if (parent == FRONTEND_OUTPUT_ROOT or (parent / 'package.json').is_file()) and name in FRONTEND_GENERATED:
                    if (parent / name).is_symlink():
                        raise PolicyError('SOURCE_ENTRY_TYPE', 'Generated root must not be a symlink')
                    dirs.remove(name)
            filenames = [name for name in filenames if not name.endswith('.tsbuildinfo')]
        for name in dirs + filenames:
            path = parent / name
            relative = path.relative_to(ROOT).as_posix()
            if FRONTEND_OUTPUT_ROOT is None and (name in ('node_modules', '.df-check', '.df-frontend-dependencies',
                                                         '.df-frontend-store', '.df-frontend-npm-cache') or name.endswith('.tsbuildinfo')):
                raise PolicyError('GENERATED_PATH', 'Frontend dependencies and generated artifacts are not source')
            if path.is_symlink() or not (path.is_file() or path.is_dir()):
                raise PolicyError('SOURCE_ENTRY_TYPE', 'Unsupported symlink or special file')
            if relative in RESOURCE_CAPABILITIES and RESOURCE_CAPABILITIES[relative]['kind'] == 'PRESERVED_ONLY':
                raise PolicyError('PRESERVED_RESOURCE_MATERIALIZED', 'Withheld resource must not exist in execution view')
            if name.casefold() in PROTECTED and not (path.is_file() and relative in RESOURCE_CAPABILITIES):
                raise PolicyError('SOURCE_PROTECTED_ENTRY', 'Nested metadata or credential directory')
            if relative.casefold() in names:
                raise PolicyError('SOURCE_CASE_CONFLICT', 'Case-conflicting paths')
            names.add(relative.casefold())
            if len(names) > FILES_LIMIT * 4:
                raise PolicyError('SOURCE_ENTRY_LIMIT', 'Directory and file entry limit exceeded')
        dirs[:] = sorted(d for d in dirs
                         if (parent / d).relative_to(ROOT).as_posix() in retained_directories
                         or not build_output((parent / d).relative_to(ROOT).as_posix(), modules))
        for name in sorted(filenames):
            relative = (parent / name).relative_to(ROOT).as_posix()
            if relative in retained or not build_output(relative, modules):
                files.append(relative)
            if len(files) > FILES_LIMIT:
                raise PolicyError('SOURCE_FILE_LIMIT', 'File count exceeded')
    return sorted(files)


def snapshot(include_content=False, baseline=None, modules=None):
    result = []
    total = captured = 0
    baseline = baseline or {}
    for relative in all_files(modules, baseline):
        path = ROOT / relative
        size = path.stat().st_size
        total += size
        if size > BASELINE_FILE_LIMIT or total > BASELINE_TOTAL_LIMIT:
            raise PolicyError('SOURCE_CAPTURE_LIMIT', 'Bounded baseline materialization limit exceeded')
        digest = hashlib.sha256()
        with path.open('rb') as source:
            while chunk := source.read(65536):
                digest.update(chunk)
        mode = '100755' if path.stat().st_mode & stat.S_IXUSR else '100644'
        sha256 = digest.hexdigest()
        entry = {'path': relative, 'mode': mode, 'sha256': sha256}
        if relative in RESOURCE_CAPABILITIES:
            entry['resource'] = True
        if include_content and relative not in RESOURCE_CAPABILITIES and baseline.get(relative) != sha256 + ':' + mode:
            captured += size
            if size > FILE_LIMIT or captured > TOTAL_LIMIT:
                raise PolicyError('SOURCE_CAPTURE_LIMIT', 'Editable source capture size limit exceeded')
            raw = path.read_bytes()
            if hashlib.sha256(raw).hexdigest() != sha256:
                raise PolicyError('SOURCE_CHANGED_DURING_CAPTURE', 'Source changed during capture')
            entry['content'] = base64.b64encode(raw).decode('ascii')
        result.append(entry)
    for relative, resource in RESOURCE_CAPABILITIES.items():
        if resource['kind'] == 'PRESERVED_ONLY':
            if (ROOT / relative).exists() or (ROOT / relative).is_symlink():
                raise PolicyError('PRESERVED_RESOURCE_MATERIALIZED', 'Withheld resource must not exist in execution view')
            result.append({'path': relative, 'mode': resource['mode'], 'sha256': resource['sha256'],
                           'preservedOnly': True})
            total += resource['size']
    if len(result) > FILES_LIMIT or total > BASELINE_TOTAL_LIMIT:
        raise PolicyError('SOURCE_CAPTURE_LIMIT', 'Full preserved baseline exceeds capture budget')
    result.sort(key=lambda entry: entry['path'])
    identities = [{k: v for k, v in entry.items() if k != 'content'} for entry in result]
    identity = hashlib.sha256(json.dumps(identities, sort_keys=True).encode()).hexdigest()
    return {'snapshot': identity, 'files': result, 'sourceView': source_view()}


def source_view():
    count = sum(resource['kind'] == 'PRESERVED_ONLY' for resource in RESOURCE_CAPABILITIES.values())
    return {'kind': 'PROJECTED' if count else 'FULL', 'preservedOnlyCount': count,
            'readOnlyResourceCount': len(RESOURCE_CAPABILITIES) - count,
            'withheldSourceCount': sum(resource.get('blocksChecks', False) for resource in RESOURCE_CAPABILITIES.values())}


def approved_resource(relative):
    resource = RESOURCE_CAPABILITIES.get(relative)
    if not resource or resource['kind'] != 'PUBLIC_RESOURCE':
        return False
    path = path_for(relative, resource=True)
    return path.is_file() and not path.is_symlink() and path.stat().st_size == resource['size'] \
        and hashlib.sha256(path.read_bytes()).hexdigest() == resource['sha256'] \
        and ('100755' if path.stat().st_mode & stat.S_IXUSR else '100644') == resource['mode']


def patch_paths(patch):
    paths = set()
    old_remaining = new_remaining = 0
    if len(patch.encode('utf-8')) > int(os.environ.get('DF_PATCH_BYTES', '131072')):
        raise PolicyError('PATCH_SIZE', 'Patch size limit exceeded')
    if any(line.startswith(('GIT binary patch', 'Binary files ', 'new file mode 120', 'old mode 120',
                            'new file mode 160', 'rename from ', 'rename to ', 'copy from ', 'copy to '))
           for line in patch.splitlines()):
        raise PolicyError('PATCH_TYPE', 'Unsupported patch type')
    for line in patch.splitlines():
        # hunk 正文中的 ---/+++ 是删除/新增内容；只有正文之外才是文件头。
        if old_remaining or new_remaining:
            if line.startswith('\\ No newline at end of file'):
                continue
            if not line or line[0] not in (' ', '-', '+'):
                raise ValueError('Invalid unified diff hunk')
            old_remaining -= line[0] in (' ', '-')
            new_remaining -= line[0] in (' ', '+')
            if old_remaining < 0 or new_remaining < 0:
                raise ValueError('Invalid unified diff hunk counts')
            continue
        if line.startswith('@@'):
            hunk = re.match(r'^@@ -\d+(?:,(\d+))? \+\d+(?:,(\d+))? @@', line)
            if not hunk:
                raise ValueError('Invalid unified diff hunk header')
            old_remaining = int(hunk.group(1) or '1')
            new_remaining = int(hunk.group(2) or '1')
            continue
        if line.startswith('diff --git '):
            values = shlex.split(line[11:])
            if len(values) != 2 or not values[0].startswith('a/') or not values[1].startswith('b/'):
                raise PolicyError('PATCH_DIFF_PATHS', 'Unsupported diff paths')
            for value in values:
                path_for(value[2:])
                paths.add(value[2:])
        if line.startswith(('new mode ', 'old mode ', 'new file mode ', 'deleted file mode ')):
            if line.split()[-1] not in ('100644', '100755'):
                raise PolicyError('PATCH_FILE_MODE', 'Unsupported file mode')
        if line.startswith(('--- ', '+++ ')):
            value = line[4:]
            if value == '/dev/null':
                continue
            if not value.startswith(('a/', 'b/')) or '\t' in value or '"' in value:
                raise PolicyError('PATCH_PATH_FORMAT', 'Expected plain a/ and b/ patch paths')
            relative = value[2:]
            path_for(relative)
            frontend_output = False
            candidate = ROOT / relative
            for parent in candidate.parents:
                if parent.is_relative_to(ROOT) and (parent / 'package.json').is_file():
                    frontend_output |= candidate.relative_to(parent).parts[0] in ('dist', 'coverage', '.vite')
            if build_output(relative) or frontend_output or any(part in ('node_modules', '.df-check')
                                             for part in relative.split('/')) or relative.endswith('.tsbuildinfo'):
                raise PolicyError('GENERATED_PATH', 'Generated paths are not editable')
            paths.add(relative)
    if old_remaining or new_remaining or not paths or len(paths) > 64:
        raise ValueError('Invalid or oversized unified diff')
    return sorted(paths)


def run_bounded(command, input_bytes=None, timeout=180, maximum=1048576, cwd=ROOT, env=None):
    # 文件输出有硬上限监控，不能把无界日志积存在内存或磁盘。
    import tempfile
    with tempfile.TemporaryFile() as output:
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT,
                                   cwd=cwd, env=env, start_new_session=True)
        try:
            if input_bytes:
                process.stdin.write(input_bytes)
            process.stdin.close()
            deadline = time.monotonic() + timeout
            while process.poll() is None:
                if output.tell() > maximum or time.monotonic() > deadline:
                    raise PolicyError('COMMAND_BUDGET', 'Command time or output budget exhausted')
                time.sleep(0.05)
            if output.tell() > maximum:
                raise PolicyError('COMMAND_BUDGET', 'Command output budget exhausted')
            output.seek(0)
            return process.returncode, output.read(maximum).decode('utf-8', errors='replace')
        finally:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=5)


def maven_report_files(modules=None):
    modules = maven_module_roots() if modules is None else modules
    for module in sorted(modules):
        prefix = '' if module == '.' else module + '/'
        for kind in ('surefire-reports', 'failsafe-reports'):
            directory = path_for(prefix + 'target/' + kind)
            if not directory.exists():
                continue
            if not directory.is_dir():
                raise PolicyError('UNSAFE_REPORT_DIRECTORY', 'Unsafe report directory')
            for path in sorted(directory.iterdir()):
                if path.is_symlink():
                    raise PolicyError('UNSAFE_REPORT_DIRECTORY', 'Unsafe report directory')
                if path.is_file() and path.relative_to(ROOT).as_posix() not in BASELINE_PATHS:
                    yield path


def report(modules=None):
    total = failures = errors = skipped = reports = 0
    for path in maven_report_files(modules):
        name = path.name
        if name.startswith('TEST-') and name.endswith('.xml'):
            reports += 1
            if reports > 1000:
                raise PolicyError('REPORT_COUNT_LIMIT', 'Test report count limit exceeded')
            if path.stat().st_size > 2097152:
                raise PolicyError('UNSAFE_REPORT', 'Unsafe test report')
            node = ET.fromstring(path.read_bytes())
            for suite in ([node] if node.tag == 'testsuite' else node.findall('testsuite')):
                total += int(suite.get('tests', '0'))
                failures += int(suite.get('failures', '0'))
                errors += int(suite.get('errors', '0'))
                skipped += int(suite.get('skipped', '0'))
    if min(total, failures, errors, skipped) < 0 or failures + errors + skipped > total:
        raise PolicyError('REPORT_COUNTS', 'Contradictory test report counts')
    return {'reports': reports, 'tests': total, 'executedTests': total - skipped,
            'failures': failures, 'errors': errors, 'skipped': skipped}


def h2_runtime_database(path):
    # 仅识别已有 H2 MVStore 二进制数据库，不按目录或任意后缀豁免源文件。
    if not path.name.endswith('.mv.db'):
        return False
    with path.open('rb') as source:
        header = source.read(4096).split(b'\n', 1)[0]
    fields = dict(field.split(b':', 1) for field in header.split(b',') if b':' in field)
    return (fields.get(b'H') == b'2' and fields.get(b'blockSize') == b'1000'
            and all(key in fields for key in (b'block', b'chunk', b'format', b'fletcher')))


H2_TRACE_HEADER = re.compile(
    rb'\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})? '
    rb'[A-Za-z][A-Za-z0-9_\[\] .-]{0,63}:')


def h2_runtime_traces(before, databases):
    # 只接纳已有、与已识别 MVStore 同名的 H2 文本日志，不按后缀忽略任意文件。
    entries = {entry['path']: entry for entry in before['files']}
    traces = {}
    for database in databases:
        relative = database[:-len('.mv.db')] + '.trace.db'
        if relative not in entries:
            continue
        path = path_for(relative)
        with path.open('rb') as source:
            if not H2_TRACE_HEADER.match(source.read(512).lstrip(b'\r\n')):
                continue
        traces[relative] = (path.stat().st_size, entries[relative]['sha256'])
    return traces


def h2_trace_appended(path, original):
    # 前缀必须逐字节保留；只返回摘要，不能把日志里的 SQL 或用户数据带出检查副本。
    length, expected = original
    digest = hashlib.sha256()
    with path.open('rb') as source:
        remaining = length
        while remaining:
            part = source.read(min(remaining, 65536))
            if not part:
                return False
            digest.update(part)
            remaining -= len(part)
        if digest.hexdigest() != expected:
            return False
        return bool(H2_TRACE_HEADER.match(source.read(512).lstrip(b'\r\n')))


def check_runtime_changes(before, after, databases, traces=None):
    traces = traces or {}
    old = {entry['path']: entry for entry in before['files']}
    new = {entry['path']: entry for entry in after['files']}
    changes = []
    diagnostics = []
    for relative in sorted(old.keys() | new.keys()):
        original, current = old.get(relative), new.get(relative)
        if original == current:
            continue
        database_runtime = (relative in databases and current is not None
                            and original['mode'] == current['mode'] and h2_runtime_database(path_for(relative)))
        trace_runtime = (relative in traces and current is not None
                         and original['mode'] == current['mode']
                         and h2_trace_appended(path_for(relative), traces[relative]))
        runtime = database_runtime or trace_runtime
        if original is None:
            kind = 'ADDED'
        elif current is None:
            kind = 'DELETED'
        elif original['sha256'] == current['sha256']:
            kind = 'MODE'
        else:
            kind = 'CONTENT' if original['mode'] == current['mode'] else 'CONTENT_AND_MODE'
        # 诊断仅含相对路径、固定类别和哈希；绝不带捕获内容或命令输出。
        state = lambda entry: {key: entry[key] for key in ('mode', 'sha256')} if entry else None
        diagnostics.append({'path': relative, 'changeType': kind, 'before': state(original),
                            'after': state(current), 'runtimeData': bool(runtime)})
        if runtime:
            changes.append({'path': relative, 'category': 'H2_MVSTORE_CONTENT' if database_runtime else 'H2_TRACE_APPEND',
                            'before': original, 'after': current})
    unexpected = [entry for entry in diagnostics if not entry['runtimeData']]
    if unexpected:
        # 优先保留违规项，超过八项明确标记截断；原拒绝语义不因诊断而改变。
        selected = (unexpected + [entry for entry in diagnostics if entry['runtimeData']])[:8]
        diagnostic = {'beforeSnapshot': before.get('snapshot'), 'afterSnapshot': after.get('snapshot'),
                      'totalChanges': len(diagnostics), 'unexpectedChanges': len(unexpected),
                      'files': selected, 'truncated': len(diagnostics) > len(selected)}
        raise PolicyError('CHECK_MUTATED_SOURCE', 'Build mutated source; results cannot attest the requested snapshot', diagnostic)
    return changes


def checks(args, dependency_probe=False):
    if source_view()['withheldSourceCount']:
        raise CheckArgumentError('Executable source or build configuration is withheld; no checks can attest this view')
    goal = args.get('goal', 'test')
    if goal not in ('compile', 'test', 'package', 'verify'):
        raise PolicyError('MAVEN_OPTIONS', 'Unsupported Maven goal')
    command = ['mvn', '-B', '-ntp', '-o', '-s', '/opt/delveforge/offline-settings.xml',
               '-Dmaven.repo.local=/m2', '-Dstyle.color=never']
    directory = args.get('directory', '.')
    project = path_for(directory, True)
    if not (project / 'pom.xml').is_file():
        import frontend
        raise CheckArgumentError('No Maven entry in selected directory. Available check declarations: '
                                 + json.dumps(frontend.describe(sys.modules[__name__])))
    if project != ROOT:
        command += ['-f', directory + '/pom.xml']
    module = args.get('module')
    if module:
        path_for(module)
        if not re.fullmatch(r'[A-Za-z0-9_./-]+', module):
            raise PolicyError('MAVEN_OPTIONS', 'Invalid module selector')
        command += ['-pl', module, '-am']
    selected = args.get('test')
    if selected:
        if not re.fullmatch(r'[A-Za-z0-9_.$*#,+-]{1,200}', selected) or goal != 'test':
            raise PolicyError('MAVEN_OPTIONS', 'Invalid test selector')
        command += ['-Dtest=' + selected, '-Dsurefire.failIfNoSpecifiedTests=false']
    modules = maven_module_roots({project.relative_to(ROOT).as_posix()})
    captured = snapshot(modules=modules)
    before = captured['snapshot']
    databases = ({entry['path'] for entry in captured['files'] if entry['path'] not in RESOURCE_CAPABILITIES
                  and h2_runtime_database(path_for(entry['path']))}
                 if os.environ.get('DF_CHECK_COPY') == 'true' else set())
    traces = h2_runtime_traces(captured, databases)
    # 只清理实际构建目录的生成报告；测试夹具和基线资源不能被清理或冒充执行证据。
    for path in maven_report_files(modules):
        path.unlink()
    actual_goal = ('org.apache.maven.plugins:maven-dependency-plugin:3.8.1:go-offline'
                   if dependency_probe else goal)
    code, output = run_bounded(command + [actual_goal], timeout=int(os.environ.get('DF_CHECK_SECONDS', '180')))
    after = snapshot(modules=modules)
    changes = check_runtime_changes(captured, after, databases, traces)
    result = report(modules)
    if re.search(r'release version .* not supported|invalid target release|UnsupportedClassVersionError', output):
        classification = 'JDK_ENVIRONMENT_MISMATCH'
    elif result['failures'] or result['errors']:
        classification = 'TESTS_FAILED'
    elif code == 0 and dependency_probe:
        classification = 'DEPENDENCIES_PREPARED'
    elif code == 0:
        classification = 'TESTS_PASSED' if result['reports'] and result['executedTests'] > 0 else 'BUILD_PASSED_NOT_TESTED'
    elif re.search(r'offline mode|has not been downloaded|Cannot access .*offline|could not be resolved|'
                   r'MultipleArtifactsNotFoundException', output, re.I):
        classification = 'DEPENDENCY_MISSING'
    elif 'COMPILATION ERROR' in output:
        classification = 'COMPILATION_FAILED'
    else:
        classification = 'BUILD_CONFIGURATION_FAILED'
    phases = re.findall(r'(?m)^\[INFO\] --- [^\r\n]+ ---', output)
    return {'classification': classification, 'exitCode': code, 'snapshot': before, 'runtimeChanges': changes,
            'sourceView': source_view(),
            'observedPhase': phases[-1] if phases else 'NOT_OBSERVED',
            'unprovenCapabilities': [] if result['executedTests'] > 0 else ['TEST_RUNNER'],
            'goal': goal, 'directory': project.relative_to(ROOT).as_posix(), 'module': module, 'selectedTest': selected,
            **result, 'output': output[-18000:]}


def fetch_maven_dependencies(args):
    import tempfile
    artifacts = args.get('artifacts')
    if set(args) != {'artifacts'} or not isinstance(artifacts, list) or not 1 <= len(artifacts) <= 32 \
            or any(not isinstance(value, str) or len(value) > 512 or not re.fullmatch(
                r'[A-Za-z0-9_][A-Za-z0-9_.-]*:[A-Za-z0-9_][A-Za-z0-9_.-]*:'
                r'[A-Za-z0-9_][A-Za-z0-9_.+\-]*:(?:jar|pom|war|zip|test-jar)(?::[A-Za-z0-9_.-]+)?', value)
                for value in artifacts):
        raise CheckArgumentError('Expected bounded Maven artifact identities')
    # 联网 Maven 永不加载项目模型、.mvn 或生命周期；依赖描述由固定插件原生解析。
    with tempfile.TemporaryDirectory(prefix='df-artifact-fetch-') as directory:
        project = pathlib.Path(directory)
        (project / 'pom.xml').write_text('<project><modelVersion>4.0.0</modelVersion>'
            '<groupId>delveforge.preparation</groupId><artifactId>fetch</artifactId><version>1</version></project>')
        outputs = []
        for artifact in artifacts:
            code, output = run_bounded(['mvn', '-B', '-ntp', '-U', '-s', '/opt/delveforge/offline-settings.xml',
                '-Dmaven.repo.local=/m2', '-Dstyle.color=never', '-f', str(project / 'pom.xml'),
                'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get', '-Dtransitive=true',
                '-Dartifact=' + artifact], timeout=int(os.environ.get('DF_CHECK_SECONDS', '180')) * 3, cwd=project)
            outputs.append(output[-4000:])
            if code:
                return {'classification': 'DEPENDENCY_MISSING', 'exitCode': code, 'output': '\n'.join(outputs)[-18000:]}
        return {'classification': 'DEPENDENCIES_PREPARED', 'exitCode': 0, 'output': '\n'.join(outputs)[-18000:]}


def patch_state(path):
    if not path.exists():
        return 'absent'
    raw = path.read_bytes()
    if not path.is_file() or len(raw) > FILE_LIMIT or b'\x00' in raw:
        raise PolicyError('PATCH_PREIMAGE', 'Unsupported patch preimage')
    raw.decode('utf-8', errors='strict')
    mode = '100755' if path.stat().st_mode & 0o111 else '100644'
    return hashlib.sha256(raw).hexdigest() + ':' + mode


def preview_patch(patch):
    # 预演只写私有临时目录；返回原始前后像给宿主检查，绝不直接交给模型。
    import shutil
    import tempfile
    paths = patch_paths(patch)
    with tempfile.TemporaryDirectory() as directory:
        before_root = pathlib.Path(directory) / 'before'
        after_root = pathlib.Path(directory) / 'after'
        before_root.mkdir()
        after_root.mkdir()
        files = {}
        for relative in paths:
            original = path_for(relative)
            old, new = before_root / relative, after_root / relative
            old.parent.mkdir(parents=True, exist_ok=True)
            new.parent.mkdir(parents=True, exist_ok=True)
            state = patch_state(original)
            before = text(original) if state != 'absent' else None
            files[relative] = {'before': before, 'preimage': state}
            if before is not None:
                old.write_bytes(original.read_bytes())
                new.write_bytes(original.read_bytes())
                old.chmod(0o755 if original.stat().st_mode & 0o111 else 0o644)
                new.chmod(old.stat().st_mode & 0o777)
        command = ['git', '-c', 'core.autocrlf=false', '-c', 'core.hooksPath=/dev/null',
                   'apply', '--no-index', '--unidiff-zero', '--whitespace=nowarn']
        code, output = run_bounded(command + ['--check', '-'], patch.encode(), 10, 16000, cwd=after_root)
        if code:
            reverse, _ = run_bounded(command + ['--reverse', '--check', '-'], patch.encode(), 10, 16000, cwd=after_root)
            reason = 'CHANGE_ALREADY_PRESENT' if reverse == 0 else (
                'INVALID_DIFF' if 'corrupt patch' in output or 'unrecognized input' in output else
                'PATH_STATE_MISMATCH' if 'No such file' in output or 'already exists' in output else
                'CONTEXT_MISMATCH' if 'does not apply' in output or 'patch failed' in output else 'CHECK_FAILED')
            endings = set()
            for value in files.values():
                content = value['before']
                if content is not None:
                    endings.add('CRLF' if '\r\n' in content and '\n' not in content.replace('\r\n', '')
                                else 'MIXED' if '\r\n' in content else 'LF')
            return {'classification': 'PATCH_CONFLICT', 'mutationNotStarted': True, 'conflictReason': reason,
                    'paths': paths, 'sourceLineEndings': sorted(endings),
                    'instruction': '补丁预演未通过，实际源码未写入。重新读取后修正唯一原文与上下文。'}
        code, _ = run_bounded(command + ['-'], patch.encode(), 10, 16000, cwd=after_root)
        if code:
            raise ValueError('Controlled patch preview failed')
        for relative in paths:
            old, new = before_root / relative, after_root / relative
            files[relative]['after'] = text(new) if new.exists() else None
            files[relative]['postimage'] = patch_state(new)
            # 固定名称避免把仓库文件名当作 diff 语法；原始diff仅在进程内转换为行范围。
            left, right = pathlib.Path(directory) / 'left', pathlib.Path(directory) / 'right'
            left.write_bytes(old.read_bytes() if old.exists() else b'')
            right.write_bytes(new.read_bytes() if new.exists() else b'')
            code, diff = run_bounded(['git', 'diff', '--no-index', '--no-ext-diff', '--no-textconv',
                                      '--unified=0', '--', str(left), str(right)], timeout=10,
                                     maximum=FILE_LIMIT * 3, cwd=after_root)
            if code not in (0, 1):
                raise ValueError('Controlled patch comparison failed')
            changes = []
            for line in diff.splitlines():
                if line.startswith('@@'):
                    match = re.match(r'^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@', line)
                    if not match:
                        raise ValueError('Invalid controlled change range')
                    changes.append([int(match[1]), int(match[2] or '1'), int(match[3]), int(match[4] or '1')])
            files[relative]['changes'] = changes
        return {'files': files}


def dispatch(name, args):
    if name == 'protocol':
        return {'protocol': PROTOCOL_NAME, 'version': PROTOCOL_VERSION}
    if name == 'initialize':
        total = 0
        with tarfile.open('/tmp/source.tar') as archive:
            for entry in archive:
                relative = entry.name.rstrip('/')
                path = path_for(relative, resource=True)
                if entry.isdir():
                    path.mkdir(parents=True, exist_ok=True)
                elif entry.isfile():
                    path.parent.mkdir(parents=True, exist_ok=True)
                    total += entry.size
                    if entry.size > BASELINE_FILE_LIMIT or total > BASELINE_TOTAL_LIMIT:
                        raise PolicyError('BASELINE_FILE_LIMIT', 'Baseline file limit exceeded')
                    path.write_bytes(archive.extractfile(entry).read(BASELINE_FILE_LIMIT + 1))
                    path.chmod(0o755 if entry.mode & 0o100 else 0o644)
                else:
                    raise PolicyError('BASELINE_FILE_TYPE', 'Unsupported baseline file type')
        for relative, resource in RESOURCE_CAPABILITIES.items():
            if resource['kind'] == 'PUBLIC_RESOURCE' and not approved_resource(relative):
                raise PolicyError('RESOURCE_INTEGRITY', 'Transported resource differs from its trusted identity')
        return snapshot()
    if name == 'snapshot':
        return snapshot(args.get('content', False), args.get('baseline'))
    if name == 'describe_checks':
        import frontend
        return frontend.describe(sys.modules[__name__])
    if name == 'fetch_maven_dependencies':
        return fetch_maven_dependencies(args)
    if name == 'probe_maven_dependencies':
        return checks(args, dependency_probe=True)
    if name == 'list_files':
        prefix = args.get('path', '.')
        path = path_for(prefix, True)
        if not path.exists():
            raise ValueError('Path does not exist')
        offset = args.get('offset', 0)
        limit = args.get('limit', 100)
        if type(offset) is not int or type(limit) is not int or offset < 0 or not 1 <= limit <= 200:
            raise ValueError('Invalid pagination')
        files = [p for p in all_files() if p not in RESOURCE_CAPABILITIES and not credential_path(p)
                 and (path == ROOT or (ROOT / p).is_relative_to(path))]
        selected = files[offset:offset + limit]
        directories = sorted({pathlib.PurePosixPath(value).parent.as_posix() for value in selected}
                             - {'.'})
        return {'files': selected, 'directories': directories, 'total': len(files),
                'nextOffset': offset + limit if offset + limit < len(files) else None}
    if name == 'read_file':
        content = text(path_for(args.get('path')))
        start, count = args.get('start_line', 1), args.get('line_count', 120)
        if type(start) is not int or type(count) is not int or start < 1 or not 1 <= count <= 300:
            raise ValueError('Invalid line range')
        lines = content.splitlines(keepends=True)
        return {'path': args['path'], 'startLine': start, 'totalLines': len(lines),
                'count': count, '_source': content}
    if name == 'search_code':
        query = args.get('query')
        if not isinstance(query, str) or not 1 <= len(query) <= 200:
            raise ValueError('Expected bounded literal query')
        prefix = args.get('path', '.')
        parent = path_for(prefix, True)
        if not parent.exists():
            raise ValueError('Path does not exist')
        matches = []
        sources = {}
        for relative in all_files():
            path = ROOT / relative
            if parent != ROOT and not path.is_relative_to(parent):
                continue
            try:
                content = text(path_for(relative))
            except (ValueError, UnicodeError, PolicyError):
                continue
            for index, line in enumerate(content.splitlines(), 1):
                if query in line:
                    matches.append({'path': relative, 'line': index})
                    sources[relative] = content
                    if len(matches) == 40:
                        return {'matches': matches, '_sources': sources, 'truncated': True}
        return {'matches': matches, '_sources': sources, 'truncated': False}
    if name == 'inspect_patch':
        return preview_patch(args['patch'])
    if name == 'apply_patch':
        patch = args['patch']
        paths = patch_paths(patch)
        for relative in paths:
            if (ROOT / relative).exists():
                text(ROOT / relative)
        if (set(args.get('preimages', {})) != set(paths) or set(args.get('postimages', {})) != set(paths)):
            raise PolicyError('PATCH_PREIMAGE', 'Controlled patch witness required')
        if any(patch_state(path_for(relative)) != args['preimages'][relative] for relative in paths):
            return {'classification': 'PATCH_CONFLICT', 'mutationNotStarted': True,
                    'conflictReason': 'CONTEXT_MISMATCH', 'instruction': '预检后源码变化；未应用补丁，重新读取。'}
        command = ['git', '-c', 'core.autocrlf=false', '-c', 'core.hooksPath=/dev/null',
                   'apply', '--no-index', '--unidiff-zero', '--whitespace=nowarn']
        code, output = run_bounded(command + ['--check', '-'], patch.encode(), 10, 16000)
        if code != 0:
            # --check 从未应用补丁；只返回固定诊断，不转发 Git 自由文本。
            reverse, _ = run_bounded(command + ['--reverse', '--check', '-'], patch.encode(), 10, 16000)
            reason = 'CHANGE_ALREADY_PRESENT' if reverse == 0 else (
                'INVALID_DIFF' if 'corrupt patch' in output or 'unrecognized input' in output else
                'PATH_STATE_MISMATCH' if 'No such file' in output or 'already exists' in output else
                'CONTEXT_MISMATCH' if 'does not apply' in output or 'patch failed' in output else 'CHECK_FAILED')
            endings = set()
            for relative in paths:
                if (ROOT / relative).is_file():
                    content = text(path_for(relative))
                    endings.add('CRLF' if '\r\n' in content and '\n' not in content.replace('\r\n', '')
                                else 'MIXED' if '\r\n' in content else 'LF')
            return {'classification': 'PATCH_CONFLICT', 'mutationNotStarted': True, 'conflictReason': reason,
                    'paths': paths, 'sourceLineEndings': sorted(endings), 'snapshot': snapshot()['snapshot'],
                    'instruction': '本次补丁未应用，已有修改保持不变。重新读取目标源码，核对行尾与上下文，再构造正确行数的补丁；不要重复相同补丁。'}
        if any(patch_state(path_for(relative)) != args['preimages'][relative] for relative in paths):
            return {'classification': 'PATCH_CONFLICT', 'mutationNotStarted': True,
                    'conflictReason': 'CONTEXT_MISMATCH', 'instruction': '写入前源码变化；未应用补丁，重新读取。'}
        try:
            code, _ = run_bounded(command + ['-'], patch.encode(), 10, 16000)
        except OSError:
            raise PatchOutcomeUnknown() from None
        if code != 0:
            raise PatchOutcomeUnknown()
        try:
            if any(patch_state(path_for(relative)) != args['postimages'][relative] for relative in paths):
                raise PatchOutcomeUnknown()
            captured = snapshot()['snapshot']
        except PolicyError as error:
            error.stage = 'SOURCE_CAPTURE'
            raise
        except (OSError, ValueError, UnicodeError):
            raise PatchOutcomeUnknown() from None
        return {'classification': 'PATCH_APPLIED', 'paths': paths, 'snapshot': captured}
    if name == 'run_checks':
        return checks(args)
    if name in ('run_frontend_checks', 'prepare_frontend_dependencies'):
        import frontend
        return frontend.dispatch(sys.modules[__name__], args, name == 'prepare_frontend_dependencies')
    raise PolicyError('UNKNOWN_TOOL', 'Unknown tool')


def main():
    global BASELINE_PATHS, RESOURCE_CAPABILITIES
    started = time.monotonic()
    try:
        raw_request = sys.stdin.buffer.read(8388609)
        if len(raw_request) > 8388608:
            raise PolicyError('REQUEST_LIMIT', 'Bounded protocol input exceeded')
        request = json.loads(raw_request)
        BASELINE_PATHS = set(request.get('baselinePaths', []))
        RESOURCE_CAPABILITIES = request.get('resourceCapabilities', {})
        if not isinstance(RESOURCE_CAPABILITIES, dict) or len(RESOURCE_CAPABILITIES) > FILES_LIMIT:
            raise PolicyError('RESOURCE_CAPABILITIES', 'Invalid trusted capability manifest')
        for path, resource in RESOURCE_CAPABILITIES.items():
            if not isinstance(resource, dict) or resource.get('kind') not in ('PUBLIC_RESOURCE', 'PRESERVED_ONLY') \
                    or resource.get('mode') not in ('100644', '100755') \
                    or not re.fullmatch(r'[0-9a-f]{64}', str(resource.get('sha256'))) \
                    or type(resource.get('size')) is not int or not 0 <= resource['size'] <= BASELINE_FILE_LIMIT:
                raise PolicyError('RESOURCE_CAPABILITIES', 'Invalid trusted resource identity')
            path_for(path, resource=True)
        result = dispatch(request['name'], request.get('arguments', {}))
        print(json.dumps({'ok': result.get('classification') != 'PATCH_CONFLICT', 'tool': request['name'], 'durationMs': int(
            (time.monotonic() - started) * 1000), 'result': result}, ensure_ascii=True))
    except CheckArgumentError as error:
        print(json.dumps({'ok': False, 'classification': 'INVALID_ARGUMENTS', 'checkStarted': False, 'reason': str(error)}))
    except PolicyError as error:
        rejected = {'ok': False, 'classification': 'POLICY_REJECTED',
                    'policyRule': error.rule, 'policyStage': error.stage}
        if error.source_changes is not None:
            rejected['sourceChanges'] = error.source_changes
        print(json.dumps(rejected))
    except PatchOutcomeUnknown:
        print(json.dumps({'ok': False, 'classification': 'PATCH_OUTCOME_UNCERTAIN'}))
    except (ValueError, OSError, UnicodeError, ET.ParseError) as error:
        print(json.dumps({'ok': False, 'classification': 'TOOL_ERROR', 'reason': type(error).__name__ + ': ' + str(error)[:300]}))


if __name__ == '__main__':
    main()
