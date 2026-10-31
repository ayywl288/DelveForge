// 核对最终图像字节、来源、浏览器解码与独立图库的本地链接。
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const root = resolve('docs/design/review-v2-final-20261031');
const manifest = JSON.parse(readFileSync(join(root, 'manifest.json'), 'utf8'));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const fail = message => { throw Error(message); };
const listed = new Set();
for (const entry of manifest.images) {
    if (listed.has(entry.path) || !entry.path.startsWith('images/') || entry.path.includes('..')) fail('Invalid image path');
    listed.add(entry.path);
    const bytes = readFileSync(join(root, entry.path));
    if (!bytes.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10]))) fail(`PNG magic: ${entry.path}`);
    if (bytes.readUInt32BE(16) !== entry.width || bytes.readUInt32BE(20) !== entry.height
        || bytes.length !== entry.bytes || hash(bytes) !== entry.sha256 || entry.dpr !== 2) fail(`Manifest: ${entry.path}`);
    if (entry.kind !== 'detail' && entry.width !== entry.viewport.width * 2) fail(`DPR width: ${entry.path}`);
}
if (readdirSync(join(root, 'images')).length !== listed.size) fail('Unlisted image files');
const html = readFileSync(join(root, 'index.html'), 'utf8');
const links = [...html.matchAll(/(?:src|href)="([^"#]+)"/g)].map(m => m[1]);
for (const path of links) if (path.includes('://') || !existsSync(join(root, path))) fail(`Missing/nonlocal link: ${path}`);
const git = (...args) => execFileSync('git', args, { encoding: 'utf8' }).trim();
if (git('rev-parse', 'HEAD:frontend') !== manifest.frontendTree) fail('Frontend tree drift');
if (git('diff', '--name-only', '--', 'frontend')) fail('Uncommitted frontend changes');
for (const [path, expected] of Object.entries(manifest.buildHashes)) {
    if (hash(readFileSync(join('frontend/dist', path))) !== expected) fail(`Build drift: ${path}`);
}
const skill = process.env.TASTE_SKILL_HOME ?? join(homedir(), '.agents/skills/design-taste-frontend');
const { Browser } = await import(pathToFileURL(join(skill, 'scripts/lib/cdp.mjs')).href);
const browser = await Browser.launch();
const checks = [];
try {
    const page = await browser.newPage({ width: 1440, height: 900, dsf: 1 });
    await page.goto(pathToFileURL(join(root, 'index.html')).href, { settleMs: 150 });
    const decoded = await page.eval(async entries => {
        const rows = [];
        for (const entry of entries) {
            const img = new Image(); img.src = entry.path;
            await img.decode();
            rows.push({ path: entry.path, width: img.naturalWidth, height: img.naturalHeight });
            img.src = '';
        }
        return rows;
    }, manifest.images);
    if (decoded.some((row, i) => row.width !== manifest.images[i].width || row.height !== manifest.images[i].height)) fail('Browser decode dimensions');
    for (const width of [1440,420,320]) {
        await page.setViewport({ width, height: 900, dsf: 1 });
        for (const filter of ['all', '1440', '420', '320']) {
            const state = await page.eval(filter => {
                document.querySelector(`[data-filter="${filter}"]`).click();
                return { overflow: document.documentElement.scrollWidth > innerWidth,
                    visible: [...document.querySelectorAll('article')].filter(n => !n.hidden).length,
                    mismatches: [...document.querySelectorAll('article')].filter(n => n.hidden !== (filter !== 'all' && n.dataset.width !== filter)).length,
                    sections: [...document.querySelectorAll('main section')].length,
                    upscaled: [...document.images].filter(n => n.getBoundingClientRect().width > parseFloat(n.style.getPropertyValue('--css-width')) + 1).length };
            }, filter);
            if (state.overflow || state.mismatches || state.sections !== 9 || state.upscaled || !state.visible) fail(`Gallery layout: ${width}/${filter}`);
            checks.push({ width, filter, ...state });
        }
    }
    if (page.consoleErrors.length) fail(`Gallery runtime: ${page.consoleErrors}`);
    const report = { imageCount: decoded.length, allDecoded: true, allHashesMatch: true, allLocalLinksExist: true,
        sourceCommit: manifest.sourceCommit, frontendTree: manifest.frontendTree, buildHashes: manifest.buildHashes,
        totalBytes: manifest.images.reduce((sum, n) => sum + n.bytes, 0), checks };
    writeFileSync(join(root, 'integrity-check.json'), JSON.stringify(report, null, 2));
    console.log(JSON.stringify({ images: decoded.length, checks: checks.length, bytes: report.totalBytes, pass: true }));
} finally { await browser.close(); }
