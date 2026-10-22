"""受控 Central 镜像；项目网络无外部路由，代理拒绝 CONNECT 和任意上游选择。"""
import hashlib
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
        try:
            data = read_upstream(path)
            break
        except urllib.error.HTTPError as error:
            if error.code not in (429, 500, 502, 503, 504) or attempt == 4:
                raise
            time.sleep(0.5 * (attempt + 1))
        except (OSError, http.client.HTTPException):
            if attempt == 4:
                raise
            time.sleep(0.5 * (attempt + 1))
    if len(data) > MAX_BYTES:
        raise ValueError('Artifact size limit')
    return data


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        path = self.path.removeprefix('/maven2/')
        if (not self.path.startswith('/maven2/') or len(path) > 512
                or not re.fullmatch(r'[A-Za-z0-9_./+\-]+', path)
                or any(p in ('', '.', '..') or p.startswith('.df-pending-') for p in path.split('/'))):
            self.send_error(403)
            return
        cached = ROOT.joinpath(path)
        try:
            if cached.is_file():
                data = cached.read_bytes()
            else:
                data = fetch(path)
                # 校验独立上游 checksum 后原子发布；未完成内容不进入共享缓存。
                if not path.endswith(('.sha1', '.sha256', '.sha512', '.md5')):
                    expected = fetch(path + '.sha1').decode('ascii').strip().split()[0]
                    if hashlib.sha1(data).hexdigest() != expected:
                        raise ValueError('Upstream checksum mismatch')
                cached.parent.mkdir(parents=True, exist_ok=True)
                descriptor, temporary = tempfile.mkstemp(prefix='.df-pending-', dir=cached.parent)
                try:
                    with os.fdopen(descriptor, 'wb') as output:
                        output.write(data)
                    os.replace(temporary, cached)
                finally:
                    if os.path.exists(temporary):
                        os.unlink(temporary)
            self.send_response(200)
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except urllib.error.HTTPError as error:
            print('ARTIFACT_UPSTREAM_HTTP ' + str(error.code), flush=True)
            self.send_error(404 if error.code == 404 else 502)
        except (OSError, ValueError, UnicodeError, http.client.HTTPException) as error:
            print('ARTIFACT_UPSTREAM_FAILURE ' + type(error).__name__, flush=True)
            self.send_error(502)


if __name__ == '__main__':
    http.server.ThreadingHTTPServer(('0.0.0.0', 8080), Handler).serve_forever()
