// 仅访问本机合成夹具；不连接正式 API，不执行指南命令。
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';

const skill = process.env.TASTE_SKILL_HOME ?? join(homedir(), '.agents/skills/design-taste-frontend');
const { Browser } = await import(pathToFileURL(join(skill, 'scripts/lib/cdp.mjs')).href);
const polish = process.argv.includes('--polish');
const output = resolve(polish ? 'target/final-agent-ui-polish-review' : 'target/agent-run-quickstart-review');
mkdirSync(output, { recursive: true });
const browser = await Browser.launch();
const captures = [];
try {
    for (const width of [1440, 390]) {
        for (const scene of polish ? ['delivery', 'quick-start'] : ['evolution', 'delivery', 'quick-start', 'initial-ready']) {
            const page = await browser.newPage({ width, height: 1000, dsf: 1 });
            try {
                await page.goto(`http://127.0.0.1:4179/?visual=${scene === 'quick-start' ? 'delivery' : scene}`
                    + (scene === 'quick-start' ? '#/plans/plan-1/quick-start' : ''), { settleMs: 600 });
                const selector = scene === 'quick-start' ? '.guide-sources' : scene === 'initial-ready'
                    ? '.advanced-run-settings' : '.working-plan';
                for (let retry = 0; retry < 30 && !await page.eval(s => !!document.querySelector(s), selector); retry++) {
                    await new Promise(done => setTimeout(done, 100));
                }
                const checks = await page.eval(({ scene, selector }) => {
                    if (!document.querySelector(selector)) throw Error(`Missing ${selector}`);
                    const visible = element => element && !element.closest('details:not([open])');
                    if (document.documentElement.scrollWidth > innerWidth) throw Error('Page overflows horizontally');
                    if (document.querySelector('.original-events')) throw Error('Raw event dump is rendered');
                    if (scene === 'quick-start') {
                        if (document.querySelector('.guide-sources').open) throw Error('Provenance should start collapsed');
                        if (document.querySelectorAll('.guide-verification').length !== 1) throw Error('Repeated verification note');
                    } else if (scene !== 'initial-ready') {
                        if (document.querySelectorAll('.working-plan').length !== 1) throw Error('Repeated checklist');
                        if (document.querySelector('.activity-section .working-plan')) throw Error('Checklist is inside timeline');
                        if ([...document.querySelectorAll('.command-preview,.run-statistics,.preflight-summary')].some(visible)) {
                            throw Error('Technical details leaked into normal presentation');
                        }
                        const timeline = getComputedStyle(document.querySelector('.timeline-viewport'));
                        if (timeline.maxHeight !== 'none' || ['auto', 'scroll'].includes(timeline.overflowY)) {
                            throw Error('Timeline still scrolls inside the page');
                        }
                        const preflight = document.querySelectorAll('.developer-event-history [data-kind="PREFLIGHT_PROGRESS"]');
                        if (preflight.length !== 1 || preflight[0].getAttribute('data-tone') !== 'completed') {
                            throw Error('Preflight did not collapse to one successful activity');
                        }
                    }
                    return { width: innerWidth, horizontalOverflow: false, title: document.querySelector('main h1')?.textContent,
                        developerClosed: !document.querySelector('.developer-details')?.open };
                }, { scene, selector });
                const path = join(output, `${scene}-${width}.png`);
                await page.screenshot({ path, format: 'png', fullPage: true });
                captures.push({ scene, width, path, checks, sha256: createHash('sha256').update(readFileSync(path)).digest('hex') });
                if (scene === 'evolution' || polish && scene === 'delivery') {
                    await page.eval(() => document.querySelector('.developer-details > summary').click());
                    await page.eval(() => document.querySelector('.check-summary > summary').click());
                    await page.eval(() => [...document.querySelectorAll('.command-details button')].find(button => button.textContent.includes('查看已保存输出')).click());
                    const path = join(output, `developer-${width}.png`);
                    await page.screenshot({ path, format: 'png', fullPage: true });
                    const commandCount = await page.eval(() => document.querySelectorAll('[aria-label="命令"]').length);
                    if (commandCount !== 1) throw Error('Repeated command text');
                    captures.push({ scene: 'developer', width, path, checks: { commandCount }, sha256: createHash('sha256').update(readFileSync(path)).digest('hex') });
                }
                if (page.consoleErrors.length) throw Error(JSON.stringify(page.consoleErrors));
            } finally { await page.close(); }
        }
    }
    writeFileSync(join(output, 'manifest.json'), JSON.stringify({ fixtureOnly: true, captures }, null, 2));
    console.log(`Verified ${captures.length} screenshots at 1440px and 390px: ${output}`);
} finally { await browser.close(); }
