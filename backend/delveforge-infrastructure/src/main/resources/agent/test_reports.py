"""可选 JUnit 证据读取；不选择语言、命令或检查入口，也不承担执行授权。"""
import hashlib
import os
import pathlib
import xml.etree.ElementTree as ET


def collect(root, retained, before=None):
    fingerprints = {}
    cases = {}
    total = 0
    try:
        for directory, children, files in os.walk(root, followlinks=False):
            children[:] = [name for name in children if name not in ('.git', 'node_modules', '.df-check')
                           and not (pathlib.Path(directory) / name).is_symlink()]
            for name in files:
                if not name.startswith('TEST-') or not name.endswith('.xml'):
                    continue
                path = pathlib.Path(directory) / name
                relative = path.relative_to(root).as_posix()
                if relative in retained or path.is_symlink():
                    continue  # 跟踪的报告夹具不是本次执行的产物。
                total += path.stat().st_size
                if total > 8388608 or len(fingerprints) >= 1024:
                    return {'availability': 'UNAVAILABLE', 'reason': 'REPORT_LIMIT'}
                raw = path.read_bytes()
                fingerprint = str(path.stat().st_mtime_ns) + ':' + hashlib.sha256(raw).hexdigest()
                fingerprints[relative] = fingerprint
                if before is None or before.get(relative) == fingerprint:
                    continue
                if b'<!DOCTYPE' in raw.upper():
                    return {'availability': 'UNAVAILABLE', 'reason': 'REPORT_FORMAT'}
                node = ET.fromstring(raw)
                for case in node.iter('testcase'):
                    identity = case.get('classname', '') + '#' + case.get('name', '')
                    if not case.get('name') or len(identity) > 500 or identity in cases or len(cases) >= 10000:
                        return {'availability': 'UNAVAILABLE', 'reason': 'REPORT_IDENTITY'}
                    cases[identity] = ('SKIPPED' if case.find('skipped') is not None else
                                       'FAILED' if case.find('failure') is not None or case.find('error') is not None
                                       else 'PASSED')
        if before is None:
            return {'fingerprints': fingerprints}
        if not cases:
            return {'availability': 'UNAVAILABLE'}
        return {'availability': 'AVAILABLE', 'cases': cases,
                'executedTests': sum(outcome != 'SKIPPED' for outcome in cases.values()),
                'failures': sum(outcome == 'FAILED' for outcome in cases.values()),
                'skipped': sum(outcome == 'SKIPPED' for outcome in cases.values())}
    except (OSError, ValueError, ET.ParseError):
        return {'availability': 'UNAVAILABLE', 'reason': 'REPORT_FORMAT'}
