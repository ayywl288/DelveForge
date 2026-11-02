"""仅记录公共工件身份和有限下载指标，不泄露响应体、代理 URL 或凭据。"""
import collections
import hashlib
import importlib.util
import io
import json
import pathlib
import tempfile
import threading
import unittest
import urllib.error
from unittest.mock import Mock, patch

SOURCE = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/agent/proxy.py'
spec = importlib.util.spec_from_file_location('maven_proxy', SOURCE)
proxy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proxy)


class MavenProxyDiagnosticTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = pathlib.Path(self.temporary.name)
        for name, value in [('ROOT', root / 'cache'), ('MAVEN_DIAGNOSTIC', root / 'progress.json'),
                            ('MAVEN_PROGRESS', collections.Counter()), ('MAVEN_ACTIVE', {}),
                            ('MAVEN_RECENT', collections.deque(maxlen=8))]:
            active = patch.object(proxy, name, value)
            active.start()
            self.addCleanup(active.stop)

    def progress(self):
        return json.loads(proxy.MAVEN_DIAGNOSTIC.read_text(encoding='utf-8'))

    def handler(self):
        handler = object.__new__(proxy.Handler)
        handler.path = '/maven2/example/demo/1/demo-1.pom'
        handler.wfile = io.BytesIO()
        for name in ['send_response', 'send_error', 'send_header', 'end_headers']:
            setattr(handler, name, Mock())
        return handler

    def test_cold_download_and_shared_cache_hit_have_separate_counters(self):
        content = b'<project>private-looking-response-must-not-be-logged</project>'
        checksum = hashlib.sha1(content).hexdigest().encode()
        with patch.object(proxy, 'read_upstream', side_effect=[content, checksum]) as upstream:
            first = self.handler()
            first.do_GET()
            second = self.handler()
            second.do_GET()
        self.assertEqual(2, upstream.call_count)
        self.assertEqual(content, first.wfile.getvalue())
        self.assertEqual(content, second.wfile.getvalue())
        counts = self.progress()['counts']
        self.assertEqual(1, counts['CACHE_MISS'])
        self.assertEqual(1, counts['CACHE_HIT'])
        self.assertEqual(2, counts['UPSTREAM_OK'])
        self.assertNotIn('private-looking', proxy.MAVEN_DIAGNOSTIC.read_text())

    def test_inflight_download_is_observable_before_a_blocked_upstream_returns(self):
        entered, release = threading.Event(), threading.Event()

        def upstream(_):
            entered.set()
            self.assertTrue(release.wait(3))
            return b'public'

        with patch.object(proxy, 'read_upstream', side_effect=upstream):
            worker = threading.Thread(target=proxy.fetch, args=('example/demo.pom',))
            worker.start()
            try:
                self.assertTrue(entered.wait(3))
                self.assertEqual('example/demo.pom', self.progress()['active'][0]['artifact'])
                self.assertEqual(1, self.progress()['counts']['UPSTREAM_START'])
                self.assertNotIn('UPSTREAM_OK', self.progress()['counts'])
            finally:
                release.set()
                worker.join(3)
        self.assertFalse(worker.is_alive())
        self.assertEqual([], self.progress()['active'])
        self.assertEqual(1, self.progress()['counts']['UPSTREAM_OK'])

    def test_verified_checksum_is_reused_when_maven_requests_it_after_the_artifact(self):
        content = b'<project/>'
        checksum = hashlib.sha1(content).hexdigest().encode()
        with patch.object(proxy, 'read_upstream',
                          side_effect=lambda path: checksum if path.endswith('.sha1') else content) as upstream:
            self.handler().do_GET()
            check = self.handler()
            check.path += '.sha1'
            check.do_GET()
        check.send_response.assert_called_with(200)
        self.assertEqual(checksum, check.wfile.getvalue())
        self.assertEqual(2, upstream.call_count)
        self.assertEqual(1, self.progress()['counts']['CACHE_HIT'])

    def test_retries_record_categories_without_sensitive_error_urls_or_proxy_environment(self):
        failure = urllib.error.HTTPError('https://user:private@untrusted.invalid/token', 503,
                                         'private-message', {}, None)
        with patch.object(proxy, 'read_upstream', side_effect=[failure, b'ok']), \
                patch.object(proxy.time, 'sleep'), \
                patch.object(proxy.urllib.request, 'getproxies', return_value={'https': 'private-proxy'}):
            self.assertEqual(b'ok', proxy.fetch('example/demo.pom'))
        text = proxy.MAVEN_DIAGNOSTIC.read_text()
        for hidden in ['private', 'untrusted.invalid', 'https://']:
            self.assertNotIn(hidden, text)
        self.assertTrue(self.progress()['upstreamProxyConfigured'])
        self.assertEqual(1, self.progress()['counts']['RETRY'])
        self.assertEqual('HTTP_503', self.progress()['recent'][1]['category'])

    def test_mismatched_checksum_never_publishes_artifact_or_sidecar(self):
        with patch.object(proxy, 'read_upstream', side_effect=[b'public', b'0' * 40]), patch('builtins.print'):
            handler = self.handler()
            handler.do_GET()
        handler.send_error.assert_called_with(502)
        self.assertFalse((proxy.ROOT / 'example/demo/1/demo-1.pom').exists())
        self.assertFalse((proxy.ROOT / 'example/demo/1/demo-1.pom.sha1').exists())
        self.assertEqual(1, self.progress()['counts']['HTTP_502'])
        self.assertEqual('ValueError', self.progress()['recent'][-1]['category'])

    def test_diagnostic_history_and_material_are_bounded(self):
        for index in range(100):
            proxy.maven_progress('CACHE_HIT', 'group/' + ('a' * 480) + '/' + str(index))
        progress = self.progress()
        self.assertEqual(8, len(progress['recent']))
        self.assertEqual(100, progress['counts']['CACHE_HIT'])
        self.assertLess(proxy.MAVEN_DIAGNOSTIC.stat().st_size, 32768)


if __name__ == '__main__':
    unittest.main()
