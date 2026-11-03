"""固定 registry 代理只允许规范包 metadata/tarball，拒绝任意外部目标。"""
import importlib.util
import io
import gzip
import json
import os
import pathlib
import tempfile
import unittest
from unittest.mock import Mock, patch

SOURCE = pathlib.Path(__file__).resolve().parents[2] / 'main/resources/agent/proxy.py'
spec = importlib.util.spec_from_file_location('artifact_proxy', SOURCE)
proxy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proxy)


class FrontendProxyTest(unittest.TestCase):
    def test_native_metadata_resolution_keeps_identity_and_stays_on_the_proxy(self):
        metadata = {'name': '@fixture/package', 'versions': {'1.2.3': {
            'name': '@fixture/package', 'version': '1.2.3', 'dist': {
                'integrity': 'unchanged', 'tarball': 'https://registry.npmjs.org/@fixture/package/-/package-1.2.3.tgz'}}}}
        result = json.loads(proxy.registry_metadata(gzip.compress(json.dumps(metadata).encode()), 'gzip', '@fixture%2Fpackage'))
        distribution = result['versions']['1.2.3']['dist']
        self.assertEqual('http://df-artifacts:8080/npm/@fixture/package/-/package-1.2.3.tgz', distribution['tarball'])
        self.assertEqual('unchanged', distribution['integrity'])
        for source in ('https://evil.invalid/@fixture/package/-/package-1.2.3.tgz',
                       'https://registry.npmjs.org/other/-/other-1.2.3.tgz',
                       'https://registry.npmjs.org/@fixture/package/-/package-9.9.9.tgz'):
            metadata['versions']['1.2.3']['dist']['tarball'] = source
            with self.assertRaises(ValueError):
                proxy.registry_metadata(json.dumps(metadata).encode(), '', '@fixture%2Fpackage')

    def handler(self, path):
        handler = object.__new__(proxy.Handler)
        handler.path = path
        handler.send_error = Mock()
        handler.send_response = Mock()
        handler.send_header = Mock()
        handler.end_headers = Mock()
        handler.wfile = io.BytesIO()
        return handler

    def test_only_canonical_registry_packages_and_tarballs_can_be_requested(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(proxy, 'ROOT', pathlib.Path(temporary)), \
                patch.object(proxy.urllib.request, 'getproxies', return_value={'https': 'fixture-proxy'}):
            response = Mock()
            response.headers = {}
            response.read.return_value = b'{}'
            response.__enter__ = Mock(return_value=response)
            response.__exit__ = Mock(return_value=False)
            for path in ('vue', '@vue%2Fshared', '@vue/shared', 'vue/-/vue-3.5.17.tgz'):
                handler = self.handler('/npm/' + path)
                with patch.object(proxy.OPENER, 'open', return_value=response) as upstream:
                    handler.npm_tarball()
                    self.assertEqual('https://registry.npmjs.org/' + path, upstream.call_args.args[0].full_url)
                    handler.send_response.assert_called_with(200)
            for accept in ('application/json', 'application/vnd.npm.install-v1+json'):
                handler = self.handler('/npm/vue')
                handler.headers = {'Accept': accept}
                with patch.object(proxy.OPENER, 'open', return_value=response) as upstream:
                    handler.npm_tarball()
                    self.assertEqual(accept, upstream.call_args.args[0].get_header('Accept'))
                    self.assertEqual('gzip', upstream.call_args.args[0].get_header('Accept-encoding'))
            for path in ('../secret', '%2e%2e/secret', 'https://evil.invalid/data', 'vue?token=secret',
                         'vue/-/../secret.tgz', '@scope%2f../secret', '//evil.invalid', 'vue\nheader'):
                handler = self.handler('/npm/' + path)
                with patch.object(proxy.OPENER, 'open') as upstream:
                    handler.npm_tarball()
                    upstream.assert_not_called()
                    handler.send_error.assert_called_with(403)

    def test_redirects_are_not_followed(self):
        self.assertIsNone(proxy.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://evil.invalid'))

    def test_fixed_registry_connections_are_reused_without_redirecting(self):
        response = Mock(status=200)
        response.read.return_value = b'{}'
        response.getheader.return_value = 'gzip'
        connection = Mock()
        connection.getresponse.return_value = response
        with patch.object(proxy.urllib.request, 'getproxies', return_value={}), \
                patch.object(proxy.CONNECTIONS, 'registry', None, create=True), \
                patch.object(proxy.http.client, 'HTTPSConnection', return_value=connection) as constructor:
            for _ in range(2):
                self.assertEqual((b'{}', 'gzip'), proxy.read_npm_upstream('vue', 'application/json', True))
            constructor.assert_called_once_with('registry.npmjs.org', timeout=30)
            self.assertEqual(2, connection.request.call_count)
            self.assertEqual('/vue', connection.request.call_args.args[1])

    def test_maven_preparation_does_not_gain_registry_access(self):
        for mode, path in (('maven', '/npm/vue'), ('frontend', '/maven2/fixture/artifact.pom')):
            handler = self.handler(path)
            with patch.dict(os.environ, {'DF_ARTIFACT_MODE': mode}), patch.object(proxy.OPENER, 'open') as upstream:
                handler.do_GET()
                upstream.assert_not_called()
                handler.send_error.assert_called_with(403)


if __name__ == '__main__':
    unittest.main()
