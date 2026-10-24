"""独立核验冻结/交付事实，在新容器内构建已交付 C 并执行真实 HTTP 验收；不修改目标源码。"""
import argparse
import hashlib
import io
import json
import os
import pathlib
import sqlite3
import subprocess
import tarfile
import uuid


CONTAINER_CHECK = r'''
import json,pathlib,shutil,subprocess,time,urllib.parse,urllib.request,xml.etree.ElementTree as ET
shutil.copytree('/snapshot','/workspace',dirs_exist_ok=True)
shutil.copytree('/cache','/m2',dirs_exist_ok=True)
with open('/tmp/maven.log','wb') as log:
    build=subprocess.run(['mvn','-o','-B','-ntp','-s','/opt/delveforge/offline-settings.xml',
        '-Dmaven.repo.local=/m2','verify'],cwd='/workspace/complete',stdout=log,stderr=subprocess.STDOUT)
if build.returncode:
    print(json.dumps({'stage':'independent-offline-verify','exitCode':build.returncode,
                     'diagnostic':pathlib.Path('/tmp/maven.log').read_text()[-8000:]}))
    raise SystemExit(1)
counts=dict(tests=0,failures=0,errors=0,skipped=0)
for report in pathlib.Path('/workspace/complete/target/surefire-reports').glob('TEST-*.xml'):
    suite=ET.parse(report).getroot()
    for key in counts: counts[key]+=int(suite.get(key,'0'))
assert counts['tests']>0 and not (counts['failures'] or counts['errors'] or counts['skipped']),counts
jar=list(pathlib.Path('/workspace/complete/target').glob('*.jar'))
assert len(jar)==1
log=open('/tmp/application.log','wb')
app=subprocess.Popen(['java','-jar',str(jar[0]),'--server.port=18081','--server.address=127.0.0.1'],stdout=log,stderr=subprocess.STDOUT)
try:
    def greeting(name):
        query='' if name is None else '?'+urllib.parse.urlencode({'name':name})
        with urllib.request.urlopen('http://127.0.0.1:18081/greeting'+query,timeout=3) as response:
            assert response.status==200
            return json.load(response)
    ready=False
    for _ in range(120):
        if app.poll() is not None: break
        try: greeting(None);ready=True;break
        except OSError: time.sleep(.5)
    assert ready,'Delivered application did not start'
    cases=[(None,'World'),('','World'),('   ','World'),('Ada','Ada'),
           ('  Ada   Lovelace  ','Ada Lovelace'),('Ada\t\tLovelace','Ada Lovelace'),
           ('Ada\nLovelace','Ada Lovelace'),('\t\n','World'),
           ('Ada\vLovelace','Ada Lovelace'),('Ada\fLovelace','Ada Lovelace'),
           ('Ada\rLovelace','Ada Lovelace'),
           ('张  三','张 三'),('A-B','A-B')]
    previous=0
    results=[]
    for name,expected in cases:
        result=greeting(name)
        assert set(result)=={'id','content'},result
        assert result['content']=='Hello, '+expected+'!',result
        assert isinstance(result['id'],int) and result['id']>previous,result
        previous=result['id']
        results.append({'case':len(results)+1,'passed':True})
    print(json.dumps({'stage':'independent-http-acceptance','maven':counts,'http':results,'passed':True}))
finally:
    app.terminate()
    try: app.wait(timeout=10)
    except subprocess.TimeoutExpired: app.kill();app.wait()
    log.close()
'''


def command(args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, **kwargs).stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('attempt', type=pathlib.Path)
    parser.add_argument('--image', required=True)
    parser.add_argument('--cache', required=True)
    args = parser.parse_args()
    root = args.attempt.resolve()
    evidence = json.loads((root / 'evidence.json').read_text(encoding='utf-8'))
    connection = sqlite3.connect((root / 'application.db').as_uri() + '?mode=ro', uri=True)
    connection.row_factory = sqlite3.Row
    connection.execute('PRAGMA query_only=ON')
    run = connection.execute('SELECT * FROM agent_run WHERE id=?', (evidence['runId'],)).fetchone()
    assert run['state'] == 'SUCCEEDED'
    candidate = connection.execute('SELECT * FROM agent_candidate WHERE id=?', (run['candidate_id'],)).fetchone()
    delivery = connection.execute('SELECT * FROM candidate_delivery WHERE candidate_id=?', (candidate['id'],)).fetchone()
    step = connection.execute('SELECT * FROM evolution_step WHERE id=?', (run['step_id'],)).fetchone()
    copy = connection.execute('SELECT * FROM working_copy WHERE id=?', (run['working_copy_id'],)).fetchone()
    assert delivery['state'] == 'COMPLETED' and step['status'] == 'SUCCEEDED'
    assert candidate['run_id'] == run['id'] and candidate['baseline'] == run['baseline'] == evidence['baseline']
    assert hashlib.sha256(candidate['diff']).hexdigest() == candidate['sha256']
    c = copy['current_revision']
    assert c == copy['last_verified_revision'] == delivery['revision']
    assert step['baseline_revision'] == candidate['baseline'] and step['delivery_revision'] == c
    official = pathlib.Path(copy['location'])
    git = ['git', '-c', 'core.hooksPath=', '-c', 'core.autocrlf=false', '-C', str(official)]
    assert command(git + ['rev-parse', 'HEAD']).decode().strip() == c
    assert command(git + ['rev-parse', c + '^']).decode().strip() == candidate['baseline']
    assert command(git + ['rev-parse', c + '^{tree}']).decode().strip() == candidate['expected_tree']
    assert not command(git + ['status', '--porcelain']).strip()
    events = connection.execute('SELECT kind,summary FROM agent_event WHERE run_id=? ORDER BY sequence', (run['id'],)).fetchall()
    tools = [json.loads(row['summary']) for row in events if row['kind'] == 'TOOL_FINISHED']
    assert any(t.get('tool') in ('read_file', 'search_code', 'list_files') and t.get('ok') for t in tools)
    assert any(t.get('classification') == 'PATCH_APPLIED' and t.get('ok') for t in tools)
    ready = next(json.loads(row['summary']) for row in events if row['kind'] == 'SANDBOX_READY')
    assert ready['imageId'] == args.image
    tests = json.loads(candidate['test_evidence'])
    assert tests['status'] == 'TESTED'
    assert any(check['classification'] == 'TESTS_PASSED' and not check['stale']
               and check['snapshot'] == tests['finalSnapshot'] and check['executedTests'] > 0
               and check['skipped'] == 0 for check in tests['checks'])
    original = pathlib.Path(evidence.get('publicSource', root.parent / 'source'))
    original_git = ['git', '-C', str(original)]
    assert command(original_git + ['rev-parse', 'HEAD']).decode().strip() == candidate['baseline']
    assert command(original_git + ['rev-parse', 'HEAD^{tree}']).decode().strip() == evidence['sourceTree']
    assert not command(original_git + ['status', '--porcelain']).strip()
    (root / 'candidate.diff').write_bytes(candidate['diff'])
    replay = root / ('independent-replay-' + str(uuid.uuid4()) + '.git')
    command(['git', 'clone', '--bare', '--no-hardlinks', str(official), str(replay)])
    env = dict(os.environ, GIT_INDEX_FILE=str(root / ('replay-' + str(uuid.uuid4()) + '.index')))
    independent = ['git', '--git-dir=' + str(replay)]
    command(independent + ['read-tree', candidate['baseline']], env=env)
    command(independent + ['apply', '--cached', '--whitespace=nowarn', str(root / 'candidate.diff')], env=env)
    assert command(independent + ['write-tree'], env=env).decode().strip() == candidate['expected_tree']
    snapshot = root / ('acceptance-source-' + str(uuid.uuid4()))
    snapshot.mkdir()
    archive = command(git + ['archive', '--format=tar', c])
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        # 宿主 Python 3.10 没有 extraction filter；只接受普通相对目录/文件再解包。
        for entry in tar.getmembers():
            name = pathlib.PurePosixPath(entry.name)
            assert (entry.isfile() or entry.isdir()) and not name.is_absolute()
            assert '..' not in name.parts and ':' not in entry.name and '\\' not in entry.name
        tar.extractall(snapshot)
    docker = ['docker', 'run', '--rm', '-i', '--network=none', '--read-only', '--cap-drop=ALL',
              '--security-opt=no-new-privileges', '--memory=2g', '--cpus=2', '--pids-limit=256',
              '--label', 'delveforge.owner=df-real-independent-' + str(uuid.uuid4()),
              '--mount', 'type=bind,src=' + str(snapshot) + ',dst=/snapshot,readonly',
              '--mount', 'type=volume,src=' + args.cache + ',dst=/cache,readonly',
              '--tmpfs', '/workspace:rw,nosuid,size=256m,uid=11000,gid=11000',
              '--tmpfs', '/m2:rw,nosuid,size=1g,uid=11000,gid=11000', '--tmpfs', '/tmp:rw,nosuid,size=256m']
    for name in ('HTTP_PROXY', 'HTTPS_PROXY', 'FTP_PROXY', 'ALL_PROXY', 'NO_PROXY'):
        for variant in (name, name.lower()):
            docker += ['--env', variant + '=']
    result = subprocess.run(docker + [args.image, 'python3', '-'], input=CONTAINER_CHECK.encode(),
                            capture_output=True, timeout=600)
    output = result.stdout.decode('utf-8')
    if result.stderr:
        (root / 'independent-diagnostic.log').write_bytes(result.stderr)
    report = dict(run=run['id'], candidate=candidate['id'], A=candidate['baseline'],
                  B=candidate['expected_tree'], C=c, replay='PASS', sandbox=ready,
                  testEvidence=tests, originalUnchanged=True,
                  independent=json.loads(output) if output.strip() else {},
                  exitCode=result.returncode)
    (root / 'independent-acceptance.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    assert result.returncode == 0, 'Independent check failed; inspect independent-acceptance.json'
    assert report['independent']['passed']
    print(json.dumps({key: report[key] for key in ('run', 'candidate', 'A', 'B', 'C', 'replay', 'independent')}))


if __name__ == '__main__':
    main()
