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
PROTECTED = {'.git', '.hg', '.svn', '.ssh', '.aws', '.gnupg', '.m2', '.npmrc', '.netrc', '.pypirc'}
GENERATED = {'target'}


class PolicyError(Exception):
    def __init__(self, rule, message):
        super().__init__(message)
        self.rule = rule
        self.stage = None


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


def path_for(value, allow_root=False):
    if not isinstance(value, str) or '\\' in value or '\x00' in value or len(value) > 512:
        raise PolicyError('PATH_FORMAT', 'Invalid relative path')
    if value in ('', '.') and allow_root:
        return ROOT
    if credential_path(value):
        raise PolicyError('PROTECTED_CREDENTIAL_PATH', 'Protected credential path')
    parts = value.split('/')
    if any(part in ('', '.', '..') or ':' in part or part.endswith((' ', '.'))
           or re.fullmatch(r'(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\..*)?', part)
           or part.casefold() in PROTECTED
           or part.casefold().startswith('.env') for part in parts) or value.startswith('/'):
        raise PolicyError('FORBIDDEN_PATH', 'Forbidden path')
    if any(part.lower().endswith(('.pem', '.key', '.p12', '.pfx', '.jks', '.keystore')) for part in parts):
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


def build_output(relative):
    parent = ROOT
    for part in relative.split('/'):
        child = parent / part
        if part in GENERATED and (parent / 'pom.xml').is_file():
            if (child / 'pom.xml').exists():
                raise PolicyError('AMBIGUOUS_GENERATED_PATH', 'Ambiguous target module/build directory')
            return True
        parent = child
    return False


def all_files():
    files = []
    names = set()
    for directory, dirs, filenames in os.walk(ROOT, followlinks=False):
        parent = pathlib.Path(directory)
        for name in dirs + filenames:
            path = parent / name
            relative = path.relative_to(ROOT).as_posix()
            if path.is_symlink() or not (path.is_file() or path.is_dir()):
                raise PolicyError('SOURCE_ENTRY_TYPE', 'Unsupported symlink or special file')
            if name.casefold() in PROTECTED:
                raise PolicyError('SOURCE_PROTECTED_ENTRY', 'Nested metadata or credential directory')
            if relative.casefold() in names:
                raise PolicyError('SOURCE_CASE_CONFLICT', 'Case-conflicting paths')
            names.add(relative.casefold())
            if len(names) > FILES_LIMIT * 4:
                raise PolicyError('SOURCE_ENTRY_LIMIT', 'Directory and file entry limit exceeded')
        dirs[:] = sorted(d for d in dirs if not build_output((parent / d).relative_to(ROOT).as_posix()))
        for name in sorted(filenames):
            path = parent / name
            files.append(path.relative_to(ROOT).as_posix())
            if len(files) > FILES_LIMIT:
                raise PolicyError('SOURCE_FILE_LIMIT', 'File count exceeded')
    return sorted(files)


def snapshot(include_content=False):
    result = []
    total = 0
    for relative in all_files():
        path = ROOT / relative
        size = path.stat().st_size
        total += size
        if size > FILE_LIMIT or total > TOTAL_LIMIT:
            raise PolicyError('SOURCE_CAPTURE_LIMIT', 'Source capture size limit exceeded')
        raw = path.read_bytes()
        mode = '100755' if path.stat().st_mode & stat.S_IXUSR else '100644'
        entry = {'path': relative, 'mode': mode, 'sha256': hashlib.sha256(raw).hexdigest()}
        if include_content:
            entry['content'] = base64.b64encode(raw).decode('ascii')
        result.append(entry)
    identities = [{k: v for k, v in entry.items() if k != 'content'} for entry in result]
    identity = hashlib.sha256(json.dumps(identities, sort_keys=True).encode()).hexdigest()
    return {'snapshot': identity, 'files': result}


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
            if build_output(relative):
                raise PolicyError('GENERATED_PATH', 'Generated paths are not editable')
            paths.add(relative)
    if old_remaining or new_remaining or not paths or len(paths) > 64:
        raise ValueError('Invalid or oversized unified diff')
    return sorted(paths)


def run_bounded(command, input_bytes=None, timeout=180, maximum=1048576, cwd=ROOT):
    # 文件输出有硬上限监控，不能把无界日志积存在内存或磁盘。
    import tempfile
    with tempfile.TemporaryFile() as output:
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT,
                                   cwd=cwd, start_new_session=True)
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


def report():
    total = failures = errors = skipped = reports = 0
    for directory, _, files in os.walk(ROOT):
        if not directory.endswith(('surefire-reports', 'failsafe-reports')):
            continue
        for name in sorted(files):
            if name.startswith('TEST-') and name.endswith('.xml'):
                reports += 1
                if reports > 1000:
                    raise PolicyError('REPORT_COUNT_LIMIT', 'Test report count limit exceeded')
                path = pathlib.Path(directory) / name
                if path.is_symlink() or path.stat().st_size > 2097152:
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


def checks(args):
    goal = args.get('goal', 'test')
    if goal not in ('compile', 'test', 'package', 'verify'):
        raise PolicyError('MAVEN_OPTIONS', 'Unsupported Maven goal')
    command = ['mvn', '-B', '-ntp', '-o', '-s', '/opt/delveforge/offline-settings.xml',
               '-Dmaven.repo.local=/m2', '-Dstyle.color=never']
    directory = args.get('directory', '.')
    project = path_for(directory, True)
    if not (project / 'pom.xml').is_file():
        raise CheckArgumentError('No pom.xml in selected Maven directory; choose directory containing the project POM')
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
    before = snapshot()['snapshot']
    # 清除旧报告，避免未运行的模块或跳过测试沿用早前结果。
    for report_directory, _, files in os.walk(ROOT):
        if report_directory.endswith(('surefire-reports', 'failsafe-reports')):
            for name in files:
                path = pathlib.Path(report_directory) / name
                if path.is_symlink():
                    raise PolicyError('UNSAFE_REPORT_DIRECTORY', 'Unsafe report directory')
                path.unlink()
    code, output = run_bounded(command + [goal], timeout=int(os.environ.get('DF_CHECK_SECONDS', '180')))
    after = snapshot()['snapshot']
    result = report()
    if before != after:
        raise PolicyError('CHECK_MUTATED_SOURCE', 'Build mutated source; results cannot attest the requested snapshot')
    if re.search(r'release version .* not supported|invalid target release|UnsupportedClassVersionError', output):
        classification = 'JDK_ENVIRONMENT_MISMATCH'
    elif result['failures'] or result['errors']:
        classification = 'TESTS_FAILED'
    elif code == 0:
        classification = 'TESTS_PASSED' if result['reports'] and result['executedTests'] > 0 else 'BUILD_PASSED_NOT_TESTED'
    elif re.search(r'offline mode|has not been downloaded|Cannot access .*offline|could not be resolved', output, re.I):
        classification = 'DEPENDENCY_MISSING'
    elif 'COMPILATION ERROR' in output:
        classification = 'COMPILATION_FAILED'
    else:
        classification = 'BUILD_CONFIGURATION_FAILED'
    return {'classification': classification, 'exitCode': code, 'snapshot': before,
            'goal': goal, 'directory': project.relative_to(ROOT).as_posix(), 'module': module, 'selectedTest': selected,
            **result, 'output': output[-18000:]}


def dispatch(name, args):
    if name == 'initialize':
        with tarfile.open('/tmp/source.tar') as archive:
            for entry in archive:
                relative = entry.name.rstrip('/')
                path = path_for(relative)
                if entry.isdir():
                    path.mkdir(parents=True, exist_ok=True)
                elif entry.isfile():
                    path.parent.mkdir(parents=True, exist_ok=True)
                    if entry.size > FILE_LIMIT:
                        raise PolicyError('BASELINE_FILE_LIMIT', 'Baseline file limit exceeded')
                    path.write_bytes(archive.extractfile(entry).read(FILE_LIMIT + 1))
                    path.chmod(0o755 if entry.mode & 0o100 else 0o644)
                else:
                    raise PolicyError('BASELINE_FILE_TYPE', 'Unsupported baseline file type')
        return snapshot()
    if name == 'snapshot':
        return snapshot(args.get('content', False))
    if name == 'list_files':
        prefix = args.get('path', '.')
        path = path_for(prefix, True)
        if not path.exists():
            raise ValueError('Path does not exist')
        offset = args.get('offset', 0)
        limit = args.get('limit', 100)
        if type(offset) is not int or type(limit) is not int or offset < 0 or not 1 <= limit <= 200:
            raise ValueError('Invalid pagination')
        files = [p for p in all_files() if not credential_path(p)
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
                'content': ''.join(lines[start - 1:start - 1 + count])}
    if name == 'search_code':
        query = args.get('query')
        if not isinstance(query, str) or not 1 <= len(query) <= 200:
            raise ValueError('Expected bounded literal query')
        prefix = args.get('path', '.')
        parent = path_for(prefix, True)
        if not parent.exists():
            raise ValueError('Path does not exist')
        matches = []
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
                    matches.append({'path': relative, 'line': index, 'excerpt': line[:400]})
                    if len(matches) == 40:
                        return {'matches': matches, 'truncated': True}
        return {'matches': matches, 'truncated': False}
    if name == 'inspect_patch':
        return {relative: text(ROOT / relative) if (ROOT / relative).exists() else ''
                for relative in patch_paths(args['patch'])}
    if name == 'apply_patch':
        patch = args['patch']
        paths = patch_paths(patch)
        for relative in paths:
            if (ROOT / relative).exists():
                text(ROOT / relative)
        command = ['git', '-c', 'core.autocrlf=false', '-c', 'core.hooksPath=/dev/null',
                   'apply', '--no-index', '--whitespace=nowarn']
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
        try:
            code, _ = run_bounded(command + ['-'], patch.encode(), 10, 16000)
        except OSError:
            raise PatchOutcomeUnknown() from None
        if code != 0:
            raise PatchOutcomeUnknown()
        try:
            captured = snapshot()['snapshot']
        except PolicyError as error:
            error.stage = 'SOURCE_CAPTURE'
            raise
        except (OSError, ValueError, UnicodeError):
            raise PatchOutcomeUnknown() from None
        return {'classification': 'PATCH_APPLIED', 'paths': paths, 'snapshot': captured}
    if name == 'run_checks':
        return checks(args)
    raise PolicyError('UNKNOWN_TOOL', 'Unknown tool')


if __name__ == '__main__':
    started = time.monotonic()
    try:
        request = json.loads(sys.stdin.buffer.read(524289))
        result = dispatch(request['name'], request.get('arguments', {}))
        print(json.dumps({'ok': result.get('classification') != 'PATCH_CONFLICT', 'tool': request['name'], 'durationMs': int(
            (time.monotonic() - started) * 1000), 'result': result}, ensure_ascii=True))
    except CheckArgumentError as error:
        print(json.dumps({'ok': False, 'classification': 'INVALID_ARGUMENTS', 'checkStarted': False, 'reason': str(error)}))
    except PolicyError as error:
        print(json.dumps({'ok': False, 'classification': 'POLICY_REJECTED',
                          'policyRule': error.rule, 'policyStage': error.stage}))
    except PatchOutcomeUnknown:
        print(json.dumps({'ok': False, 'classification': 'PATCH_OUTCOME_UNCERTAIN'}))
    except (ValueError, OSError, UnicodeError, ET.ParseError) as error:
        print(json.dumps({'ok': False, 'classification': 'TOOL_ERROR', 'reason': type(error).__name__ + ': ' + str(error)[:300]}))
