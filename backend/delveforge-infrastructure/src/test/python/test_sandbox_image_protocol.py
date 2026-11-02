"""显式选择测试镜像：执行镜像内真实工具，只使用一次性 tmpfs 夹具，无模型或用户数据。"""
import json
import os
import pathlib
import subprocess
import unittest
import uuid

IMAGE = os.environ.get('DELVEFORGE_SANDBOX_PROTOCOL_IMAGE')
SOURCE = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/agent/sandbox.py'
FIXTURE = r'''
import importlib.util
import io
import json
import pathlib
import sys
from contextlib import redirect_stdout
from unittest.mock import patch
spec = importlib.util.spec_from_file_location('sandbox', '/opt/delveforge/sandbox.py')
sandbox = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sandbox)
mode = MODE
root = pathlib.Path('/workspace')
target = root / 'Counter.java'
original = b'class Counter { int value = 1; }\n'
target.write_bytes(original)
(root / 'pom.xml').write_text('<project/>', encoding='utf-8')
diff = '--- a/Counter.java\n+++ b/Counter.java\n@@ -1 +1 @@\n-class Counter { int value = 1; }\n+class Counter { int value = 2; }\n'
if mode == 'conflict':
    target.write_bytes(original.replace(b'= 1', b'= 3'))
before = target.read_bytes()
preview = sandbox.dispatch('inspect_patch', {'patch': diff})
if preview.get('classification') == 'PATCH_CONFLICT':
    # 验证应用入口自己的 Git冲突路径，前像仍取当前实际文件，不伪造不存在的修改结果。
    args = {'patch': diff, 'preimages': {'Counter.java': sandbox.patch_state(target)},
            'postimages': {'Counter.java': sandbox.patch_state(target)}}
else:
    args = {'patch': diff,
            'preimages': {key: item['preimage'] for key, item in preview['files'].items()},
            'postimages': {key: item['postimage'] for key, item in preview['files'].items()}}
request = json.dumps({'name': 'apply_patch', 'arguments': args}).encode()
sys.stdin = io.TextIOWrapper(io.BytesIO(request), encoding='utf-8')
output = io.StringIO()
real_run = sandbox.run_bounded

def run(command, *args):
    if '--check' in command:
        return real_run(command, *args)
    # 模拟进入应用后已产生部分修改，不能宣称未开始修改。
    target.write_bytes(b'class Counter { int value = 9; }\n')
    if mode == 'apply-oserror':
        raise OSError('fixture-private-detail')
    return 1, 'fixture-private-detail'

def capture_failure(*args):
    if mode == 'capture-policy':
        raise sandbox.PolicyError('SOURCE_CAPTURE_LIMIT', 'fixture-private-detail')
    raise OSError('fixture-private-detail')

with redirect_stdout(output):
    if mode in ('apply-failure', 'apply-oserror'):
        with patch.object(sandbox, 'run_bounded', side_effect=run):
            sandbox.main()
    elif mode in ('capture-failure', 'capture-policy'):
        with patch.object(sandbox, 'snapshot', side_effect=capture_failure):
            sandbox.main()
    else:
        sandbox.main()
response = json.loads(output.getvalue())
print(json.dumps({'response': response, 'unchanged': target.read_bytes() == before,
                  'successfulBytes': target.read_bytes() == original.replace(b'= 1', b'= 2')}))
'''


@unittest.skipUnless(IMAGE, 'Set DELVEFORGE_SANDBOX_PROTOCOL_IMAGE to a dedicated compatible image')
class SandboxImageProtocolTest(unittest.TestCase):
    def container(self, arguments, data=None):
        name = 'df-protocol-fixture-' + str(uuid.uuid4())
        command = ['docker', 'run', '--rm', '--name', name, '-i', '--network', 'none', '--read-only',
                   '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--pids-limit', '64',
                   '--memory', '512m', '--cpus', '1',
                   '--tmpfs', '/workspace:rw,nosuid,size=16m,uid=11000,gid=11000',
                   '--tmpfs', '/tmp:rw,nosuid,size=16m', IMAGE, *arguments]
        try:
            result = subprocess.run(command, input=data, capture_output=True, timeout=45, check=False)
            self.assertEqual(0, result.returncode, 'Isolated image protocol fixture must exit normally')
            self.assertEqual(b'', result.stderr)
            return result.stdout
        finally:
            subprocess.run(['docker', 'rm', '-f', name], capture_output=True, timeout=15, check=False)

    def fixture(self, mode):
        code = FIXTURE.replace('mode = MODE', 'mode = ' + repr(mode))
        return json.loads(self.container(['python3', '-'], code.encode()))

    def test_actual_installed_script_matches_build_source(self):
        actual = self.container(['python3', '-c',
                                 "import pathlib,sys; sys.stdout.buffer.write(pathlib.Path('/opt/delveforge/sandbox.py').read_bytes())"])
        self.assertEqual(SOURCE.read_bytes(), actual)

    def test_actual_cli_protocol_envelope(self):
        output = self.container(['python3', '/opt/delveforge/sandbox.py'],
                                json.dumps({'name': 'protocol', 'arguments': {}}).encode())
        response = json.loads(output)
        self.assertEqual({'ok': True, 'tool': 'protocol', 'result': {
            'protocol': 'delveforge-sandbox', 'version': 4}},
            {k: v for k, v in response.items() if k != 'durationMs'})

    def test_successful_patch_changes_exact_fixture_bytes(self):
        result = self.fixture('success')
        self.assertTrue(result['response']['ok'])
        self.assertEqual('PATCH_APPLIED', result['response']['result']['classification'])
        self.assertTrue(result['successfulBytes'])

    def test_pre_mutation_conflict_has_safe_feedback_and_unchanged_files(self):
        result = self.fixture('conflict')
        response = result['response']
        details = response['result']
        self.assertFalse(response['ok'])
        self.assertEqual('PATCH_CONFLICT', details['classification'])
        self.assertTrue(details['mutationNotStarted'])
        self.assertEqual('CONTEXT_MISMATCH', details['conflictReason'])
        self.assertNotIn('output', details)
        self.assertIn('重新读取', details['instruction'])
        self.assertTrue(result['unchanged'])

    def test_apply_stage_failures_cannot_claim_no_mutation(self):
        for mode in ('apply-failure', 'apply-oserror'):
            with self.subTest(mode=mode):
                result = self.fixture(mode)
                self.assertEqual({'ok': False, 'classification': 'PATCH_OUTCOME_UNCERTAIN'}, result['response'])
                self.assertFalse(result['unchanged'])
                self.assertNotIn('fixture-private-detail', json.dumps(result['response']))

    def test_post_application_capture_failure_remains_uncertain(self):
        result = self.fixture('capture-failure')
        self.assertEqual({'ok': False, 'classification': 'PATCH_OUTCOME_UNCERTAIN'}, result['response'])
        self.assertTrue(result['successfulBytes'])

    def test_post_application_policy_still_blocks_and_retains_stage(self):
        result = self.fixture('capture-policy')
        self.assertEqual({'ok': False, 'classification': 'POLICY_REJECTED',
                          'policyRule': 'SOURCE_CAPTURE_LIMIT', 'policyStage': 'SOURCE_CAPTURE'}, result['response'])
        self.assertTrue(result['successfulBytes'])


if __name__ == '__main__':
    unittest.main()
