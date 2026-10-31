// 隔离的视觉审阅服务：真实 Vue 页面，所有 /api 请求只访问确定性合成夹具。
import { createServer } from 'vite';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import { readFileSync, existsSync } from 'node:fs';
import { createServer as createHttpServer } from 'node:http';

const root = fileURLToPath(new URL('../', import.meta.url));
let fixtures;
const annotate = html => html.replace('<head>', `<head><script>
    localStorage.clear();
    if (!['projects', 'empty', 'deleted', 'error'].includes(new URLSearchParams(location.search).get('visual'))) {
        localStorage.setItem('delveforge.project.v1', 'visual-project');
    }
    </script>`).replace('</body>', `<div style="position:fixed;bottom:6px;right:8px;z-index:9999;background:#17282c;color:white;font:11px/1.5 'Segoe UI',sans-serif;padding:3px 8px;border-radius:3px;pointer-events:none">SYNTHETIC FIXTURE · 视觉审阅</div></body>`);
function fixtureApi(req, res, next) {
    if (!req.url?.startsWith('/api/')) return next();
    const scenario = new URL(req.headers.referer ?? 'http://localhost').searchParams.get('visual') ?? 'projects';
    const url = new URL(req.url, 'http://localhost');
    const path = decodeURIComponent(url.pathname.replace('/api', ''));
    if (scenario === 'error' && path === '/projects') {
        res.writeHead(503, { 'Content-Type': 'application/json' });
        return res.end(JSON.stringify({ code: 'SERVICE_UNAVAILABLE' }));
    }
    const data = fixtures(scenario, path);
    res.setHeader('Cache-Control', 'no-store');
    if (path.endsWith('/stream')) {
        res.writeHead(200, { 'Content-Type': 'text/event-stream' });
        for (const event of data) res.write(`event: activity\ndata: ${JSON.stringify(event)}\n\n`);
        const timer = setInterval(() => res.write(': fixture connection\n\n'), 15000);
        req.on('close', () => clearInterval(timer));
        return;
    }
    if (req.method !== 'GET') {
        // 仅延迟拒绝合成场景，供真实页面展示请求等待；不调用 Provider、不接受任何写入。
        if (scenario === 'discovery-waiting') {
            const timer = setTimeout(() => {
                res.writeHead(409, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ code: 'VISUAL_FIXTURE_READ_ONLY' }));
            }, 6000);
            res.on('close', () => clearTimeout(timer));
            return;
        }
        res.writeHead(409, { 'Content-Type': 'application/json' });
        return res.end(JSON.stringify({ code: 'VISUAL_FIXTURE_READ_ONLY' }));
    }
    res.writeHead(data === undefined ? 404 : 200, { 'Content-Type': path.endsWith('/diff') ? 'text/plain' : 'application/json' });
    res.end(typeof data === 'string' ? data : JSON.stringify(data ?? { code: 'FIXTURE_NOT_FOUND', path }));
}
const server = await createServer({
    root,
    configFile: resolve(root, 'vite.config.ts'),
    server: { host: '127.0.0.1', port: 4178, strictPort: true },
    plugins: [{
        name: 'isolated-visual-fixtures',
        transformIndexHtml(html) {
            return annotate(html);
        },
        configureServer(vite) {
            vite.middlewares.use(fixtureApi);
        },
    }],
});
const module = await server.ssrLoadModule('/tests/visual-fixtures.ts');
fixtures = module.visualFixture;
if (process.argv.includes('--production')) {
    await server.close();
    const dist = resolve(root, 'dist');
    const mime = { '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' };
    const staticServer = createHttpServer((req, res) => fixtureApi(req, res, () => {
        const pathname = new URL(req.url, 'http://localhost').pathname;
        if (pathname === '/') {
            res.setHeader('Content-Type', 'text/html; charset=utf-8');
            return res.end(annotate(readFileSync(resolve(dist, 'index.html'), 'utf8')));
        }
        const file = resolve(dist, '.' + decodeURIComponent(pathname));
        if (!file.startsWith(dist + '\\') && !file.startsWith(dist + '/')) { res.writeHead(403); return res.end(); }
        if (!existsSync(file)) { res.writeHead(404); return res.end(); }
        res.setHeader('Content-Type', mime[file.slice(file.lastIndexOf('.'))] ?? 'application/octet-stream');
        res.end(readFileSync(file));
    }));
    staticServer.listen(4179, '127.0.0.1', () => console.log('Production fixture UI: http://127.0.0.1:4179/?visual=projects'));
    for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => staticServer.close(() => process.exit(0)));
} else {
    await server.listen();
    console.log('Isolated fixture UI: http://127.0.0.1:4178/?visual=projects');
    for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, async () => { await server.close(); process.exit(0); });
}
