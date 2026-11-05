"""独立 HTTP 验收；只操作隔离应用的内存数据库，不修改目标源码。"""
import copy
import json
import sys
import urllib.error
import urllib.request

BASE = sys.argv[1].rstrip('/') if len(sys.argv) > 1 else 'http://127.0.0.1:9966/petclinic'
records = []


def request(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={'Content-Type': 'application/json'})
    try:
        response = urllib.request.urlopen(req, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read().decode()
        try:
            payload = json.loads(raw) if raw else None
        except ValueError:
            payload = raw
        return response.status, payload, response.headers.get('Location')


def get(path):
    status, body, _ = request('GET', path)
    if status != 200:
        raise AssertionError(f'GET {path}: expected 200, got {status}')
    return body


def snapshot():
    return {'pet': get('/api/pets/1'), 'visits': get('/api/visits')}


def case(identity, description, run):
    try:
        evidence = run()
        records.append({'id': identity, 'description': description, 'status': 'PASS', 'evidence': evidence})
    except (AssertionError, OSError, ValueError) as error:
        records.append({'id': identity, 'description': description, 'status': 'FAIL', 'reason': str(error)})


original = snapshot()
unrelated = copy.deepcopy(get('/api/owners/2'))
pet = original['pet']
fields = {key: copy.deepcopy(pet[key]) for key in ('name', 'birthDate', 'type')}
visit_fields = {'date': '2024-01-01', 'description': 'Independent acceptance visit'}


def rejected_update(owner, pet_id):
    before = snapshot()
    body = dict(fields, name='MustNotBeSaved')
    status, response, _ = request('PUT', f'/api/owners/{owner}/pets/{pet_id}', body)
    after = snapshot()
    assert status == 404, f'expected 404, got {status}; stateUnchanged={before == after}'
    assert after == before, 'rejected update changed pet or visit data'
    return {'httpStatus': status, 'body': response, 'stateUnchanged': True}


def rejected_visit(owner, pet_id):
    before = snapshot()
    status, response, _ = request('POST', f'/api/owners/{owner}/pets/{pet_id}/visits', visit_fields)
    after = snapshot()
    assert status == 404, f'expected 404, got {status}; stateUnchanged={before == after}'
    assert after == before, 'rejected visit changed pet or visit data'
    return {'httpStatus': status, 'body': response, 'stateUnchanged': True}


case('A2', 'wrong owner cannot update existing pet', lambda: rejected_update(2, 1))
case('A3', 'missing owner cannot update existing pet', lambda: rejected_update(99999, 1))
case('A4', 'missing pet cannot be updated', lambda: rejected_update(1, 99999))
case('A6', 'wrong owner cannot add a visit', lambda: rejected_visit(2, 1))
case('A7', 'missing owner cannot add a visit', lambda: rejected_visit(99999, 1))
case('A8', 'missing pet cannot receive a visit', lambda: rejected_visit(1, 99999))


def valid_update():
    body = dict(fields, name='AcceptedPet')
    status, _, _ = request('PUT', '/api/owners/1/pets/1', body)
    assert status == 204, f'expected 204, got {status}'
    current = get('/api/owners/1/pets/1')
    assert current['name'] == body['name'], 'updated value did not persist'
    assert current['birthDate'] == body['birthDate'], 'birth date unexpectedly changed'
    assert current['type']['id'] == body['type']['id'], 'pet type unexpectedly changed'
    return {'httpStatus': status, 'persistedName': current['name'], 'ownerScopedReadStatus': 200}


case('A1', 'matching owner can update its pet', valid_update)


def valid_visit():
    before = get('/api/visits')
    status, created, location = request('POST', '/api/owners/1/pets/1/visits', visit_fields)
    assert status == 201, f'expected 201, got {status}'
    actual = get('/api/visits/' + str(created['id']))
    assert actual['petId'] == 1, 'visit belongs to a different pet'
    assert actual['description'] == visit_fields['description'], 'visit description did not persist'
    assert actual['date'] == visit_fields['date'], 'visit date did not persist'
    assert len(get('/api/visits')) == len(before) + 1, 'unexpected visit count'
    assert location and location.endswith('/api/visits/' + str(created['id'])), 'legacy Location path changed'
    return {'httpStatus': status, 'visit': actual, 'location': location, 'countIncrease': 1}


case('A5', 'matching owner can add a persisted visit', valid_visit)


def reads_unchanged():
    assert get('/api/owners/2') == unrelated, 'unrelated owner or pet changed'
    assert get('/api/owners/1/pets/1')['name'] == 'AcceptedPet', 'nested read regressed'
    return {'unrelatedOwnerUnchanged': True, 'nestedReadStatus': 200}


case('A9', 'existing reads and unrelated entities stay available', reads_unchanged)


def invalid_payload():
    before = snapshot()
    first, _, _ = request('PUT', '/api/owners/1/pets/1', {})
    second, _, _ = request('POST', '/api/owners/1/pets/1/visits', {})
    assert first == 400 and second == 400, f'expected 400/400, got {first}/{second}'
    assert snapshot() == before, 'invalid payload caused a mutation'
    return {'updateStatus': first, 'visitStatus': second, 'stateUnchanged': True}


case('A10', 'invalid payload still rejects without mutation', invalid_payload)
result = {'kind': 'INDEPENDENT_REAL_HTTP_BUSINESS_ACCEPTANCE', 'baseUrl': BASE, 'cases': records,
          'passed': sum(item['status'] == 'PASS' for item in records),
          'failed': sum(item['status'] == 'FAIL' for item in records), 'skipped': 0}
print(json.dumps(result, ensure_ascii=False, indent=2))
raise SystemExit(1 if result['failed'] else 0)
