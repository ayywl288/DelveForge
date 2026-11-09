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
import tempfile

ROOT = pathlib.Path('/workspace')
FILE_LIMIT = int(os.environ.get('DF_FILE_BYTES', '262144'))
FILES_LIMIT = int(os.environ.get('DF_MAX_FILES', '10000'))
TOTAL_LIMIT = int(os.environ.get('DF_CAPTURE_BYTES', '16777216'))
BASELINE_FILE_LIMIT = int(os.environ.get('DF_BASELINE_FILE_BYTES', '16777216'))
BASELINE_TOTAL_LIMIT = int(os.environ.get('DF_BASELINE_TOTAL_BYTES', '134217728'))
# v1 约定检查冲突的未修改证据、应用阶段未知及应用后捕获阶段；旧工具不能推断兼容。
PROTOCOL_NAME = 'delveforge-sandbox'
PROTOCOL_VERSION = 10
PROTECTED = {'.git', '.hg', '.svn', '.ssh', '.aws', '.gnupg', '.m2'}
BASELINE_PATHS = set()
RESOURCE_CAPABILITIES = {}


class PolicyError(Exception):
    def __init__(self, rule, message, source_changes=None):
        super().__init__(message)
        self.rule = rule
        self.stage = None
        self.source_changes = source_changes


class PatchOutcomeUnknown(Exception):
    pass


def read_error(reason, instruction):
    return {'classification': 'INVALID_TOOL_ARGUMENT', 'reason': reason,
            'readNotStarted': True, 'mutationNotStarted': True, 'instruction': instruction}


def path_for(value, allow_root=False, resource=False):
    if not isinstance(value, str) or '\\' in value or '\x00' in value or len(value) > 512:
        raise PolicyError('PATH_FORMAT', 'Invalid relative path')
    if value in ('', '.') and allow_root:
        return ROOT
    approved = value in RESOURCE_CAPABILITIES
    if approved and RESOURCE_CAPABILITIES[value]['kind'] != 'PUBLIC_RESOURCE' and not resource:
        raise PolicyError('RESOURCE_CAPABILITY', 'Resource content is not model-visible or editable')
    parts = value.split('/')
    if any(part in ('', '.', '..') or ':' in part or part.endswith((' ', '.'))
           or re.fullmatch(r'(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\..*)?', part)
           or part.casefold() in PROTECTED and not (approved and (resource or RESOURCE_CAPABILITIES[value]['kind'] == 'PUBLIC_RESOURCE') and part == parts[-1])
           for part in parts) or value.startswith('/'):
        raise PolicyError('FORBIDDEN_PATH', 'Forbidden path')
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


def all_files(modules=None, retained=()):
    # 原生 Git 解释 ignore；已捕获源码始终优先，后加规则不能隐藏其修改/删除。
    retained = BASELINE_PATHS | set(retained)
    if any((ROOT / name).exists() for name in ('.git', '.hg', '.svn', '.ssh', '.aws')):
        raise PolicyError('SOURCE_PROTECTED_ENTRY', 'Control or credential directories are not source')
    with tempfile.TemporaryDirectory(prefix='df-index-') as metadata:
        subprocess.run(['git', 'init', '--bare', '--template=', metadata], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
        command = ['git', '--git-dir=' + metadata, '--work-tree=' + str(ROOT),
                   'ls-files', '--others', '--exclude-standard', '-z']
        for runtime in ('node_modules/', '.df-check/', '.df-frontend-dependencies/',
                        '.df-frontend-store/', '.df-frontend-npm-cache/', '__pycache__/', '*.tsbuildinfo'):
            command.extend(['--exclude', runtime])
        result = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
        if result.returncode or len(result.stdout) > FILES_LIMIT * 1024:
            raise PolicyError('SOURCE_CAPTURE', 'Repository ignore evaluation failed or exceeded capture budget')
    paths = set(result.stdout.decode('utf-8', errors='strict').rstrip('\0').split('\0')) - {''}
    paths |= {name for name in retained if (ROOT / name).exists() or (ROOT / name).is_symlink()}
    if len(paths) > FILES_LIMIT:
        raise PolicyError('SOURCE_FILE_LIMIT', 'Source file count exceeded')
    folded = set()
    for relative in sorted(paths):
        path = path_for(relative, resource=True)
        if not path.is_file() or path.is_symlink():
            raise PolicyError('SOURCE_ENTRY_TYPE', 'Unsupported source file type')
        if relative.casefold() in folded:
            raise PolicyError('SOURCE_CASE_CONFLICT', 'Case-conflicting paths')
        folded.add(relative.casefold())
    return sorted(paths)


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
            if relative not in BASELINE_PATHS and any(part in ('node_modules', '.df-check')
                    for part in relative.split('/')):
                raise PolicyError('GENERATED_PATH', 'Runtime dependency paths are not editable')
            paths.add(relative)
    if paths.intersection(RESOURCE_CAPABILITIES):
        raise PolicyError('RESOURCE_CAPABILITY', 'Public and preserved resources are not editable')
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
    if name == 'probe_environment':
        code, output = run_bounded(['/bin/sh', '-c', 'test -r . && test -w . && command -v git && command -v python3'],
                                   timeout=5, maximum=4096, cwd=ROOT)
        if code:
            raise PolicyError('SOURCE_CAPTURE', 'Workspace or shell environment is unavailable')
        return {'execution': 'native-shell', 'workspaceWritable': True}
    if name == 'validate_directory':
        path = path_for(args.get('path', '.'), True)
        if not path.is_dir():
            return {'classification': 'INVALID_TOOL_ARGUMENT', 'reason': 'DIRECTORY_NOT_FOUND',
                    'mutationNotStarted': True}
        return {'directory': path.relative_to(ROOT).as_posix()}
    if name == 'snapshot':
        return snapshot(args.get('content', False), args.get('baseline'))
    if name == 'test_reports':
        import test_reports
        return test_reports.collect(ROOT, BASELINE_PATHS, args.get('before'))
    if name == 'list_files':
        prefix = args.get('path', '.')
        path = path_for(prefix, True)
        if not path.exists():
            return read_error('PATH_NOT_FOUND', '该源码路径不存在；先用 list_files 查找，再使用返回的准确路径。')
        offset = args.get('offset', 0)
        limit = args.get('limit', 100)
        if type(offset) is not int or type(limit) is not int or offset < 0 or not 1 <= limit <= 200:
            return read_error('INVALID_PAGINATION', 'offset 必须为非负整数，limit 必须为 1 到 200 的整数。')
        files = [p for p in all_files() if (p not in RESOURCE_CAPABILITIES or RESOURCE_CAPABILITIES[p]['kind'] == 'PUBLIC_RESOURCE')
                 and (path == ROOT or (ROOT / p).is_relative_to(path))]
        selected = files[offset:offset + limit]
        directories = sorted({pathlib.PurePosixPath(value).parent.as_posix() for value in selected}
                             - {'.'})
        return {'files': selected, 'directories': directories, 'total': len(files),
                'nextOffset': offset + limit if offset + limit < len(files) else None}
    if name == 'read_file':
        path = path_for(args.get('path'))
        start, count = args.get('start_line', 1), args.get('line_count', 120)
        if type(start) is not int or type(count) is not int or start < 1 or not 1 <= count <= 300:
            return read_error('INVALID_RANGE', 'start_line 必须为正整数，line_count 必须为 1 到 300 的整数。')
        if not path.exists():
            return read_error('PATH_NOT_FOUND', '该源码路径不存在；先用 list_files 查找，再使用返回的准确路径。')
        content = text(path)
        lines = content.splitlines(keepends=True)
        return {'path': args['path'], 'startLine': start, 'totalLines': len(lines),
                'count': count, '_source': content}
    if name == 'search_code':
        prefix = args.get('path', '.')
        parent = path_for(prefix, True)
        query = args.get('query')
        if not isinstance(query, str) or not 1 <= len(query) <= 200:
            return read_error('INVALID_QUERY', 'query 必须为 1 到 200 个字符的字面量。')
        if not parent.exists():
            return read_error('PATH_NOT_FOUND', '该源码路径不存在；先用 list_files 查找，再使用返回的准确路径。')
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
        print(json.dumps({'ok': result.get('classification') not in ('PATCH_CONFLICT', 'INVALID_TOOL_ARGUMENT'), 'tool': request['name'], 'durationMs': int(
            (time.monotonic() - started) * 1000), 'result': result}, ensure_ascii=True))
    except PolicyError as error:
        rejected = {'ok': False, 'classification': 'POLICY_REJECTED',
                    'policyRule': error.rule, 'policyStage': error.stage}
        if error.source_changes is not None:
            rejected['sourceChanges'] = error.source_changes
        print(json.dumps(rejected))
    except PatchOutcomeUnknown:
        print(json.dumps({'ok': False, 'classification': 'PATCH_OUTCOME_UNCERTAIN'}))
    except (ValueError, OSError, UnicodeError) as error:
        print(json.dumps({'ok': False, 'classification': 'TOOL_ERROR', 'reason': type(error).__name__ + ': ' + str(error)[:300]}))


if __name__ == '__main__':
    main()
