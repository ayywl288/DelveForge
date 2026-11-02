"""受控 Central 镜像；项目网络无外部路由，代理拒绝 CONNECT 和任意上游选择。"""
import hashlib
import collections
import json
import http.server
import http.client
import os
import pathlib
import re
import tempfile
import threading
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path('/cache')
UPSTREAM = 'https://repo.maven.apache.org/maven2/'
MAX_BYTES = 64 * 1024 * 1024
MAVEN_DIAGNOSTIC = pathlib.Path('/tmp/delveforge-maven-progress.json')
MAVEN_PROGRESS_LOCK = threading.Lock()
MAVEN_PROGRESS = collections.Counter()
MAVEN_ACTIVE = {}
MAVEN_RECENT = collections.deque(maxlen=8)
MAVEN_STARTED = time.monotonic()


def maven_progress(event, path, **values):
    # 仅记录通过固定 Central 路径校验的工件身份、类别与数值；不记录 URL、凭据、环境或响应体。
    with MAVEN_PROGRESS_LOCK:
        MAVEN_PROGRESS[event] += 1
        identity = str(threading.get_ident())
        now = int((time.monotonic() - MAVEN_STARTED) * 1000)
        if event == 'UPSTREAM_START':
            if len(MAVEN_ACTIVE) < 8:
                MAVEN_ACTIVE[identity] = {'artifact': path, 'startedMs': now}
        elif event in ('UPSTREAM_OK', 'UPSTREAM_ERROR'):
            MAVEN_ACTIVE.pop(identity, None)
        MAVEN_RECENT.append({'event': event, 'artifact': path, 'atMs': now, **values})
        payload = {'elapsedMs': now, 'updatedAtEpochMs': int(time.time() * 1000),
                   'counts': dict(MAVEN_PROGRESS), 'active': list(MAVEN_ACTIVE.values()),
                   'recent': list(MAVEN_RECENT), 'upstreamProxyConfigured': bool(urllib.request.getproxies())}
        try:
            temporary = MAVEN_DIAGNOSTIC.with_suffix('.tmp')
            temporary.write_text(json.dumps(payload), encoding='utf-8')
            os.replace(temporary, MAVEN_DIAGNOSTIC)
        except OSError:
            # 诊断故障不伪装成下载故障；固定告警只输出一次。
            if not MAVEN_PROGRESS['DIAGNOSTIC_UNAVAILABLE']:
                print('MAVEN_DIAGNOSTIC_UNAVAILABLE', flush=True)
            MAVEN_PROGRESS['DIAGNOSTIC_UNAVAILABLE'] += 1


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


# 只有可信代理可使用 Engine 配置的出站代理。工程容器显式清空代理环境，且无外部路由。
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler(), NoRedirect())
CONNECTIONS = threading.local()


def read_upstream(path):
    if urllib.request.getproxies():
        with OPENER.open(UPSTREAM + path, timeout=15) as response:
            return response.read(MAX_BYTES + 1)
    connection = getattr(CONNECTIONS, 'central', None)
    if connection is None:
        connection = http.client.HTTPSConnection('repo.maven.apache.org', timeout=15)
        CONNECTIONS.central = connection
    try:
        connection.request('GET', '/maven2/' + path, headers={
            'User-Agent': 'DelveForge-Artifact-Proxy/1', 'Accept-Encoding': 'identity'})
        response = connection.getresponse()
        if response.status != 200:
            response.read(MAX_BYTES + 1)
            raise urllib.error.HTTPError(UPSTREAM + path, response.status, 'Central response', {}, None)
        return response.read(MAX_BYTES + 1)
    except (OSError, http.client.HTTPException):
        connection.close()
        CONNECTIONS.central = None
        raise


def fetch(path):
    for attempt in range(5):
        started = time.monotonic()
        maven_progress('UPSTREAM_START', path, attempt=attempt + 1)
        try:
            data = read_upstream(path)
            maven_progress('UPSTREAM_OK', path, bytes=len(data),
                           durationMs=int((time.monotonic() - started) * 1000))
            break
        except urllib.error.HTTPError as error:
            maven_progress('UPSTREAM_ERROR', path, category='HTTP_' + str(error.code),
                           durationMs=int((time.monotonic() - started) * 1000))
            if error.code not in (429, 500, 502, 503, 504) or attempt == 4:
                raise
            maven_progress('RETRY', path)
            time.sleep(0.5 * (attempt + 1))
        except (OSError, http.client.HTTPException):
            maven_progress('UPSTREAM_ERROR', path, category='TRANSPORT',
                           durationMs=int((time.monotonic() - started) * 1000))
            if attempt == 4:
                raise
            maven_progress('RETRY', path)
            time.sleep(0.5 * (attempt + 1))
    if len(data) > MAX_BYTES:
        raise ValueError('Artifact size limit')
    return data


def publish_maven_artifact(path, data):
    cached = ROOT / path
    cached.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix='.df-pending-', dir=cached.parent)
    try:
        with os.fdopen(descriptor, 'wb') as output:
            output.write(data)
        os.replace(temporary, cached)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    maven_progress('CACHE_PUBLISHED', path, bytes=len(data))


def read_npm_upstream(path, accept, metadata):
    headers = {'Accept': accept, 'User-Agent': 'DelveForge-Artifact-Proxy/1'}
    if metadata:
        headers['Accept-Encoding'] = 'gzip'
    if urllib.request.getproxies():
        request = urllib.request.Request('https://registry.npmjs.org/' + path, headers=headers)
        with OPENER.open(request, timeout=30) as response:
            return response.read(MAX_BYTES + 1), response.headers.get('Content-Encoding', '') if metadata else ''
    # 与 Central 通道一样复用每线程连接，避免冻结锁核对大量 metadata 时反复 TLS 握手。
    connection = getattr(CONNECTIONS, 'registry', None)
    if connection is None:
        connection = http.client.HTTPSConnection('registry.npmjs.org', timeout=30)
        CONNECTIONS.registry = connection
    try:
        connection.request('GET', '/' + path, headers=headers)
        response = connection.getresponse()
        if response.status != 200:
            response.read(MAX_BYTES + 1)
            raise urllib.error.HTTPError('https://registry.npmjs.org/' + path, response.status, 'Registry response', {}, None)
        return response.read(MAX_BYTES + 1), response.getheader('Content-Encoding', '') if metadata else ''
    except (OSError, http.client.HTTPException):
        connection.close()
        CONNECTIONS.registry = None
        raise


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        if self.path.startswith('/npm/'):
            if os.environ.get('DF_ARTIFACT_MODE', 'maven') == 'frontend':
                return self.npm_tarball()
            self.send_error(403)
            return
        if os.environ.get('DF_ARTIFACT_MODE', 'maven') != 'maven':
            self.send_error(403)
            return
        path = self.path.removeprefix('/maven2/')
        if (not self.path.startswith('/maven2/') or len(path) > 512
                or not re.fullmatch(r'[A-Za-z0-9_./+\-]+', path)
                or any(p in ('', '.', '..') or p.startswith('.df-pending-') for p in path.split('/'))):
            self.send_error(403)
            return
        cached = ROOT.joinpath(path)
        try:
            if cached.is_file():
                maven_progress('CACHE_HIT', path)
                data = cached.read_bytes()
            else:
                maven_progress('CACHE_MISS', path)
                data = fetch(path)
                # 校验独立上游 checksum 后原子发布；未完成内容不进入共享缓存。
                if not path.endswith(('.sha1', '.sha256', '.sha512', '.md5')):
                    checksum_path = path + '.sha1'
                    checksum_file = ROOT / checksum_path
                    checksum = checksum_file.read_bytes() if checksum_file.is_file() else fetch(checksum_path)
                    expected = checksum.decode('ascii').strip().split()[0]
                    if hashlib.sha1(data).hexdigest() != expected:
                        raise ValueError('Upstream checksum mismatch')
                    # 已与内容匹配的校验材料一并缓存，避免 Maven 紧接着再次向同一上游下载它。
                    publish_maven_artifact(checksum_path, checksum)
                publish_maven_artifact(path, data)
            self.send_response(200)
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            maven_progress('HTTP_200', path, bytes=len(data))
        except urllib.error.HTTPError as error:
            print('ARTIFACT_UPSTREAM_HTTP ' + str(error.code), flush=True)
            maven_progress('HTTP_404' if error.code == 404 else 'HTTP_502', path,
                           category='UPSTREAM_HTTP_' + str(error.code))
            self.send_error(404 if error.code == 404 else 502)
        except (OSError, ValueError, UnicodeError, http.client.HTTPException) as error:
            print('ARTIFACT_UPSTREAM_FAILURE ' + type(error).__name__, flush=True)
            maven_progress('HTTP_502', path, category=type(error).__name__)
            self.send_error(502)

    def npm_tarball(self):
        # 仅固定 registry 的包 metadata 与版本化 tarball；pnpm 12 的供应链核对需要 metadata。
        # 不接受任意 URL、重定向、CONNECT 或 registry 选择。
        path = self.path[5:]
        metadata = re.fullmatch(r'(?:@[a-z0-9_.-]+(?:/|%2[Ff]))?[a-z0-9_.-]+', path)
        tarball = re.fullmatch(r'(?:@[a-z0-9_.-]+/)?[a-z0-9_.-]+/-/[a-zA-Z0-9_.+\-]+\.tgz', path)
        if len(path) > 512 or not (metadata or tarball) \
                or any(part in ('.', '..') for part in path.split('/')):
            self.send_error(403)
            return
        cached = ROOT / 'npm' / path
        encoding = ''
        try:
            if tarball and cached.is_file():
                data = cached.read_bytes()
            else:
                requested = getattr(self, 'headers', {}).get('Accept', '')
                accept = ('application/octet-stream' if tarball else
                          'application/vnd.npm.install-v1+json' if 'application/vnd.npm.install-v1+json' in requested
                          else 'application/json')
                data, encoding = read_npm_upstream(path, accept, bool(metadata))
                if len(data) > MAX_BYTES:
                    raise ValueError('Artifact size limit')
                if tarball:
                    cached.parent.mkdir(parents=True, exist_ok=True)
                    descriptor, temporary = tempfile.mkstemp(prefix='.df-pending-', dir=cached.parent)
                    try:
                        with os.fdopen(descriptor, 'wb') as output:
                            output.write(data)
                        os.replace(temporary, cached)
                    finally:
                        if os.path.exists(temporary):
                            os.unlink(temporary)
            # 实际消费者 pnpm 必须按冻结锁中的 SHA-512 校验，代理缓存不具有证据权威。
            self.send_response(200)
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Content-Type', 'application/octet-stream' if tarball else 'application/json')
            if encoding == 'gzip':
                self.send_header('Content-Encoding', 'gzip')
            self.end_headers()
            self.wfile.write(data)
        except (OSError, ValueError, urllib.error.URLError, http.client.HTTPException):
            self.send_error(502)


if __name__ == '__main__':
    http.server.ThreadingHTTPServer(('0.0.0.0', 8080), Handler).serve_forever()
