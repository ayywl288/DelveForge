// 真实生产界面的 DPR 2 截图与元素近景；所有业务数据来自隔离夹具。
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const skill = process.env.TASTE_SKILL_HOME ?? join(homedir(), '.agents/skills/design-taste-frontend');
const { Browser } = await import(pathToFileURL(join(skill, 'scripts/lib/cdp.mjs')).href);
const preview = process.argv.includes('--preview');
const root = resolve(preview ? 'target/frontend-v2-final-20261031/preview' : 'docs/design/review-v2-final-20261031');
const git = (...args) => execFileSync('git', args, { encoding: 'utf8' }).trim();
if (!preview && git('status', '--porcelain', '--', 'frontend', ':!frontend/design')) throw Error('Commit frontend before final capture');
const sourceCommit = git('rev-parse', 'HEAD');
const frontendTree = git('rev-parse', 'HEAD:frontend');
const dist = resolve('frontend/dist');
const distHtml = readFileSync(join(dist, 'index.html'), 'utf8');
const assets = [...distHtml.matchAll(/(?:src|href)="(\/assets\/[^\"]+)"/g)].map(m => m[1]);
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const buildHashes = Object.fromEntries(['/index.html', ...assets].map(p => [p, hash(readFileSync(join(dist, p)))]));
mkdirSync(join(root, 'images'), { recursive: true });
const manifest = [], checks = [];
const progressPath = join(root, 'capture-progress.json');
if (process.argv.includes('--resume') && existsSync(progressPath)) {
    const saved = JSON.parse(readFileSync(progressPath, 'utf8'));
    if (JSON.stringify(saved.buildHashes) !== JSON.stringify(buildHashes)) throw Error('Cannot resume a different build');
    manifest.push(...saved.images);
}
const browser = await Browser.launch();
let page, scenario, viewport;
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function go(name, width = 1440, height = 900) {
    scenario = name; viewport = { width, height };
    if(page) await browser.send('Target.closeTarget',{targetId:page.targetId});
    page = await browser.newPage({ ...viewport, dsf: 2, mobile: false });
    page.consoleErrors = [];
    await page.goto(`http://127.0.0.1:4179/?visual=${name}`, { settleMs: 300 });
    await page.eval(async () => { await document.fonts.ready; });
    const ready = name === 'delivery' ? '.accepted-revisions ~ .candidate-evidence'
        : ['candidate','recovery'].includes(name) ? '.candidate-evidence'
        : ['evolution','execution-failed'].includes(name) ? '.activity-section .current-title'
        : name.startsWith('initial') ? '#agent-step'
        : name.startsWith('discovery') ? '.message.agent'
        : name === 'review-incomplete' ? '.revision-notebook'
        : name.startsWith('review') ? '.profile-card'
        : ['planning','planning-authorize','plan-proposed'].includes(name) ? '.roadmap-toggle'
        : ['repository','stress'].includes(name) ? '.dossier-reading'
        : name === 'directions' ? '.direction-entry'
        : name === 'empty' ? '.workspace-empty'
        : name === 'error' ? '.history-section [role="alert"]' : '.project-row';
    for(let attempt=0;attempt<80;attempt++) {
        if(await page.eval(selector=>!!document.querySelector(selector),ready)) return;
        await sleep(100);
    }
    writeFileSync(join(root,'capture-error.json'),JSON.stringify({scenario:name,ready,errors:page.consoleErrors,
        page:await page.eval(()=>({url:location.href,text:document.querySelector('main')?.textContent}))},null,2));
    throw Error(`Fixture did not settle: ${name} / ${ready}`);
}
async function clickText(selector, text) {
    await page.eval(({ selector, text }) => {
        const node = [...document.querySelectorAll(selector)].find(n => n.textContent.trim().includes(text));
        if (!node || node.disabled) throw Error(`Unavailable control: ${selector} / ${text}`);
        node.click();
    }, { selector, text });
    await sleep(100);
}
async function open(selector) {
    await page.eval(selector => {
        const node = document.querySelector(selector);
        if (!node) throw Error(`Missing disclosure ${selector}`);
        if (node.tagName === 'DETAILS') node.open = true;
        else node.click();
    }, selector);
    await sleep(100);
}
async function shot(group, id, title, kind = 'detail', selector = null) {
    const rel = `images/${id}.png`, file = join(root, rel);
    const previous = manifest.find(m => m.path === rel);
    if (previous && existsSync(file) && previous.sha256 === hash(readFileSync(file))) return;
    const state = await page.eval(() => ({ dpr: devicePixelRatio, overflow: document.documentElement.scrollWidth > innerWidth }));
    if (state.dpr !== 2 || state.overflow || page.consoleErrors.length) throw Error(`Invalid capture state ${id}: ${JSON.stringify(state)} ${page.consoleErrors}`);
    let clip;
    if (selector) {
        clip = await page.eval(selector => {
            const el = document.querySelector(selector);
            if (!el) throw Error(`Missing screenshot element ${selector}`);
            const r = el.getBoundingClientRect();
            if (!r.width || !r.height) throw Error(`Hidden screenshot element ${selector}`);
            return { x: Math.max(0, Math.floor(r.left + scrollX)), y: Math.max(0, Math.floor(r.top + scrollY)),
                width: Math.ceil(r.width), height: Math.ceil(r.height), scale: 1 };
        }, selector);
        const { data } = await page.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true, clip }, 45000);
        writeFileSync(file, Buffer.from(data, 'base64'));
    } else {
        await page.eval(() => scrollTo(0, 0));
        await page.screenshot({ path: file, format: 'png', fullPage: kind === 'full' });
    }
    const bytes = readFileSync(file);
    if (!bytes.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10]))) throw Error(`Not PNG ${id}`);
    const width = bytes.readUInt32BE(16), height = bytes.readUInt32BE(20);
    if (Math.abs(width - (clip?.width ?? viewport.width) * 2) > 2) throw Error(`DPR mismatch ${id}: ${width}`);
    manifest.push({ path: rel, group, title, scenario, kind, selector, viewport: { ...viewport }, dpr: 2,
        width, height, format: 'PNG', bytes: bytes.length, sha256: hash(bytes) });
    writeFileSync(progressPath, JSON.stringify({sourceCommit,frontendTree,buildHashes,images:manifest},null,2));
}
async function matrix() {
    const majors = [
        ['projects','projects','项目工作台'], ['discovery','discovery','需求对话'], ['review','review','画像审阅'],
        ['repository','repository','仓库理解'], ['directions','directions','产品方向'], ['planning','planning','演化规划'],
        ['evolution','initial','执行前准备'], ['evolution','evolution','实际运行'],
        ['evolution','candidate','冻结 Candidate 与交付受阻'], ['evolution','delivery','正式 Delivery 与未测试事实'],
    ];
    for (const [group, name, title] of majors) for (const [width,height] of [[1440,900],[1280,800],[768,900],[420,900],[320,900]]) {
        await go(name,width,height);
        const layout = await page.eval(() => {
            const before = (first, second) => {
                const a = document.querySelector(first), b = document.querySelector(second);
                return a && b ? !!(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING) : null;
            };
            return { overflow: document.documentElement.scrollWidth > innerWidth, dpr: devicePixelRatio,
                preparationFirst: before('.is-initial .agent-controls', '.is-initial .agent-run-panel'),
                activityBeforeStatistics: before('.activity-section', '.run-statistics'),
                resultsBelowWorkspace: before('.operations-workspace', '.agent-changes'),
                ambiguousProjectTrack: !!document.querySelector('.project-stage-track') };
        });
        if (layout.overflow || layout.preparationFirst === false || layout.activityBeforeStatistics === false
            || layout.resultsBelowWorkspace === false || layout.ambiguousProjectTrack) throw Error(`Hierarchy failed: ${name}`);
        checks.push({ scenario:name, viewport, ...layout, errors:[...page.consoleErrors] });
        if ([1440,420,320].includes(width)) {
            await shot(group,`${name}-${width}-viewport`,`${title} · ${width}px 首屏`,'viewport');
            await shot(group,`${name}-${width}-full`,`${title} · ${width}px 完整页面`,'full');
        }
    }
}
async function sections() {
    await go('evolution');
    for(const [sel,id,title] of [['.workspace-masthead','topbar','产品栏与品牌'],['.sidebar','sidebar','左侧导航'],['.project-navigation-heading','project-context','当前项目卡'],['.workflow-navigation','active-stages','阶段导航与当前阶段']]) await shot('shell',id,title,'detail',sel);
    for(const width of [420,320]) {
        await go('evolution',width); await shot('shell',`mobile-identity-${width}`,'手机项目身份','detail','.page-header');
        await shot('shell',`mobile-project-${width}`,'手机当前项目名称','detail','.mobile-project-context');
        await open('.mobile-navigation-toggle');
        await shot('shell',`mobile-navigation-${width}`,'展开的手机项目与阶段导航','detail','.sidebar');
    }
    for(const width of [1440,420]) {
        await go('projects',width);
        for(const [sel,id,title] of [['.project-row:first-child','project-card','项目元数据、真实交付数与继续入口'],['.new-project','new-project-entry','新流程入口']]) await shot('projects',`${id}-${width}`,title,'detail',sel);
        await open('.new-project'); await shot('projects',`new-project-form-${width}`,'新流程表单与新画像选择','detail','.new-project');
        await page.eval(()=>{const r=document.querySelector('input[value="reuse"]');r.click();});
        await shot('projects',`reuse-profile-${width}`,'复用已确认画像与版本身份','detail','.new-project');
        await clickText('.project-card-heading button','项目详情');
        await shot('projects',`project-management-${width}`,'项目详情、仓库绑定与管理','detail','dialog[open]');
        await go('projects', width);
        await page.eval(() => document.querySelectorAll('.project-card-heading button')[1].click());
        await clickText('dialog[open] button','删除项目');
        await shot('dialogs',`delete-project-${width}`,'删除项目的单独确认，保留与取消入口','detail','dialog[open]');
    }
    for(const width of [1440,420]) {
        await go('discovery',width);
        for(const [sel,id,title] of [['.chat-log','conversation-bubbles','双方对话与回合状态'],['.revision-notebook','profile-notebook','画像版本笔记与维度'],['.notebook-review','profile-ready','充分性与审阅入口']]) await shot('discovery',`${id}-${width}`,title,'detail',sel);
        await page.eval(()=>{document.querySelector('.profile-section').open=true;});
        await shot('discovery',`profile-dimension-${width}`,'画像维度展开与纠正入口','detail','.profile-section');
        await go('review',width);
        await shot('review',`review-sections-${width}`,'画像审阅维度与编辑入口','detail','.profile-review-grid');
        await shot('review',`revision-confirmation-${width}`,'绑定画像版本的确认区域','detail','.review-actions');
        await clickText('.profile-card button','编辑');
        await shot('review',`profile-edit-${width}`,'纠正画像条目的原生对话框','detail','dialog[open]');
    }
    for(const width of [1440,420]) {
        await go('repository',width);
        await shot('repository',`repository-identity-${width}`,'仓库身份、完整路径与分析 revision','detail','.dossier-masthead');
        await shot('repository',`repository-navigation-${width}`,'仓库分区导航','detail','.dossier-navigation');
        await shot('repository',`purpose-stack-${width}`,'用途与技术栈、已有能力','detail','.dossier-overview');
        for(const [text,id,title] of [['结构与技术','modules','模块与技术栈'],['已有能力','capabilities','保存的已有能力'],['可复用资产','reuse','可复用资产'],['限制与风险','risks','限制与风险'],['分析依据','sources','分析依据与完整来源']]) {
            await clickText('.local-nav-button',text);
            if(id==='sources') await page.eval(()=>document.querySelectorAll('.evidence-record').forEach(n=>n.open=true));
            await shot('repository',`${id}-${width}`,title,'detail','.dossier-reading');
        }
        await open('.repository-setup');
        await shot('repository',`asset-registration-${width}`,'仓库登记、读取许可与使用授权','detail','.repository-setup');
        await open('.authorization-editor');
        await shot('repository',`asset-authorization-${width}`,'补充许可证与版本化授权','detail','.authorization-editor');
    }
    for(const width of [1440,420]) {
        await go('directions',width);
        for(const [sel,id,title] of [['.direction-list','candidate-index','候选比较与已选状态'],['.direction-detail-controls','direction-decision','当前方向与决定入口'],['.direction-proposition','problem-target','问题与目标产品'],['.direction-reasoning','fit-difference','适配理由、差异与技术价值'],['.direction-proof','direction-evidence','风险与依据']]) await shot('directions',`${id}-${width}`,title,'detail',sel);
        await page.eval(()=>document.querySelectorAll('.direction-entry')[1].click());
        await clickText('button','选择此方向'); await shot('dialogs',`select-direction-${width}`,'明确选择方向的确认','detail','dialog[open]');
        await go('directions',width);await page.eval(()=>document.querySelectorAll('.direction-entry')[1].click());
        await clickText('button','拒绝'); await shot('dialogs',`reject-direction-${width}`,'明确拒绝候选的确认','detail','dialog[open]');
        await go('planning',width);
        for(const [sel,id,title] of [['.plan-trajectory','plan-current-target','现状、目标、计划状态与执行入口'],['.prepare-box','working-copy','WorkingCopy 状态与准备边界'],['.roadmap','timeline','连续演化时间线'],['.roadmap-step.expanded','step-expanded','展开步骤的目标、范围、改动与授权前提'],['.step-prerequisites','prerequisites','逐步执行的操作前提']]) await shot('planning',`${id}-${width}`,title,'detail',sel);
        await go('planning-authorize',width); await clickText('button','审阅并授权此 Step');
        await shot('dialogs',`step-authorization-${width}`,'本次 Step 范围与明确授权','detail','dialog[open]');
        await go('plan-proposed',width);
        await shot('planning',`preparation-controls-${width}`,'审阅确认、兼容性与准备隔离副本','detail','.prepare-box');
        await page.eval(()=>{const c=document.querySelector('.prepare-box input[type="checkbox"]');c.click();});
        await clickText('button','准备隔离副本'); await shot('dialogs',`prepare-copy-${width}`,'准备 WorkingCopy 的独立确认','detail','dialog[open]');
    }
    for(const width of [1440,420]) {
        await go('initial',width); await shot('evolution',`start-config-${width}`,'当前授权 Step、运行环境、启动与费用说明','detail','.agent-controls');
        await clickText('button','开始 Agent'); await shot('dialogs',`fee-budget-${width}`,'实际 API 费用与预算确认，未勾选不能启动','detail','dialog[open]');
        await go('evolution',width);
        for(const [sel,id,title] of [['.monitor-stop','stop-control','当前 Run 与真实停止语义'],['.activity-section','live-console','实际活动、模型等待和事件'],['.run-statistics','usage-statistics','已保存的实际模型、工具和 tokens 统计']]) await shot('evolution',`${id}-${width}`,title,'detail',sel);
        for(const [sel,id,title] of [['.patch-summary','patch-operations','展开的代码修改事件'],['.exploration-summary','file-operations','展开的文件探索事件']]) {await open(`${sel} > summary`);await shot('evolution',`${id}-${width}`,title,'detail',sel);}
        await go('candidate',width);await shot('evolution',`candidate-results-${width}`,'冻结候选、文件、未运行测试与交付受阻','detail','.agent-changes');
        await open('.candidate-identity');await shot('evolution',`candidate-integrity-${width}`,'冻结基线、预期文件树与完整性标识','detail','.candidate-identity');
        await go('delivery',width);await shot('evolution',`accepted-results-${width}`,'已正式接受文件、可信 revision 与 NOT_TESTED','detail','.agent-changes');
        await shot('evolution',`accepted-revision-${width}`,'正式接受版本与本步骤基线','detail','.accepted-revisions');
    }
}
async function edges() {
    for(const [name,group,title] of [['review-incomplete','review','未记录维度与审阅提示'],['plan-proposed','planning','尚待准备副本的计划'],['initial-authorization','evolution','尚待 Step 授权'],['execution-failed','evolution','失败运行与无进展提示'],['recovery','evolution','交付结果不明确的恢复限制'],['empty','projects','空项目'],['deleted','projects','已删除项目与恢复'],['error','dialogs','后端 HTTP503']]) {
        for(const width of [1440,420]) {
            await go(name,width);
            if(name==='deleted')await page.eval(()=>document.querySelectorAll('.project-filters button')[1].click());
            if(name==='review-incomplete')await clickText('.discovery-tabs button','画像审阅');
            await shot(group,`${name}-${width}`,title,'full');
        }
    }
    for(const width of [1440,420,320]) {
        await go('discovery-waiting',width);
        await page.eval(()=>{const n=document.querySelector('#discovery-input');n.value='合成审阅：希望核对周期任务是否按时完成。';n.dispatchEvent(new Event('input',{bubbles:true}));});
        await page.eval(()=>document.querySelector('.composer').requestSubmit()); await sleep(150);
        await shot('discovery',`thinking-${width}`,'真实请求等待中的文字与三点动效','detail','.chat-panel');
        const animation = await page.eval(()=>({dots:document.querySelectorAll('.waiting-dots i').length,animations:document.querySelector('.assistant-waiting').getAnimations({subtree:true}).length}));
        await page.send('Emulation.setEmulatedMedia',{features:[{name:'prefers-reduced-motion',value:'reduce'}]});
        await shot('dialogs',`reduced-motion-${width}`,'减少动态效果：静态提示保留','detail','.assistant-waiting');
        checks.push({scenario:'thinking',viewport,animation,reduced:await page.eval(()=>document.querySelector('.assistant-waiting').getAnimations({subtree:true}).length)});
        await page.send('Emulation.setEmulatedMedia',{features:[]});
    }
    await go('discovery',420);await page.eval(()=>{const n=document.querySelector('.message.user p');n.textContent+='\n'+('较长的中英文需求与 technical-context。'.repeat(18));});
    await shot('discovery','long-conversation-420','长对话内容：记录可滚动，输入与画像仍可访问','detail','.chat-panel');
    await go('stress',420);await clickText('.local-nav-button','分析依据');await page.eval(()=>document.querySelectorAll('.evidence-record').forEach(n=>n.open=true));
    await shot('dialogs','evidence-pagination-420','25 条 Evidence、分页和完整长来源','detail','.dossier-reading');
    const pages=[];
    for(let i=0;i<5;i++){pages.push(await page.eval(()=>document.querySelector('.evidence-page-status').textContent));if(i<4)await clickText('.evidence-pagination button','下一页');}
    checks.push({scenario:'evidence-pages',pages});
    await go('review',1440);await page.eval(()=>{const nodes=[...document.querySelectorAll('body *')].filter(n=>!['SVG','PATH','SCRIPT','STYLE'].includes(n.tagName));const sizes=nodes.map(n=>parseFloat(getComputedStyle(n).fontSize));nodes.forEach((n,i)=>n.style.fontSize=`${sizes[i]*2}px`);});
    await shot('dialogs','text-200-review','200% 文字放大的审阅与确认','full');
}
function gallery() {
    const names={shell:'应用框架',projects:'项目工作台',discovery:'需求探索',review:'画像审阅',repository:'仓库理解',directions:'产品方向',planning:'演化规划',evolution:'自主演化',dialogs:'对话框与边界状态'};
    const sections=Object.entries(names).map(([key,title])=>`<section id="${key}"><h2>${title}</h2>${manifest.filter(m=>m.group===key).map(m=>`<article data-width="${m.viewport.width}" data-kind="${m.kind}"><h3>${m.title}</h3><p>${m.viewport.width}×${m.viewport.height} CSS · DPR ${m.dpr} · PNG ${m.width}×${m.height} pixels · ${(m.bytes/1024).toFixed(0)} KB · <a href="${m.path}" target="_blank">打开原始分辨率</a></p>${m.kind==='full'?'<details><summary>展开完整页面</summary>':''}<a href="${m.path}" target="_blank"><img loading="lazy" style="--css-width:${m.width/2}px" src="${m.path}" alt="${m.title}"></a>${m.kind==='full'?'</details>':''}</article>`).join('')}</section>`).join('');
    writeFileSync(join(root,'index.html'),`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>DelveForge V2 — Final Interface Review</title><style>*{box-sizing:border-box}body{margin:0;background:#f2f3f8;color:#252936;font:15px/1.7 'Segoe UI','Microsoft YaHei',sans-serif}header{padding:28px 32px;background:#20232e;color:#f4f5fa}h1{font-size:25px;margin:0}h2{font-size:23px;margin:32px 0 18px}h3{font-size:17px;margin:0 0 6px}a{color:#424ba8;text-underline-offset:3px}.layout{display:grid;grid-template-columns:190px minmax(0,1fr)}nav{position:sticky;top:0;align-self:start;padding:24px 16px;display:grid;gap:10px}main{padding:0 32px 48px;min-width:0}article{background:#fff;border:1px solid #dfe3ed;border-radius:8px;padding:20px;margin:0 0 20px}article p{font-size:13px;color:#606a7e;margin:0 0 16px}img{display:block;width:min(100%,var(--css-width));height:auto;border:1px solid #dfe3ed}summary{cursor:pointer;min-height:44px;color:#424ba8}details img{margin-top:12px}button{font:inherit;border:1px solid #a8b0c5;border-radius:5px;padding:8px 12px;background:#fff;color:#343e6b;cursor:pointer;min-height:40px}button[aria-pressed=true]{background:#e1e5f8;border-color:#4f58b8}.filters{position:sticky;top:0;z-index:2;padding:16px 0;background:#f2f3f8;display:flex;flex-wrap:wrap;gap:8px}article[hidden]{display:none}header a{color:#c3c9ff}.note{max-width:90ch;color:#cbd1e1;font-size:14px}@media(max-width:800px){.layout{display:block}nav{position:static;display:flex;flex-wrap:wrap;padding:16px;gap:8px 16px}header{padding:24px 16px}main{padding:0 16px 32px}article{padding:16px}.filters{position:static}img{max-width:100%}}</style><header><h1>DelveForge · V2 完整界面审阅</h1><p class="note">仅最终 V2 界面。真实生产构建、DPR 2、无损 PNG；所有项目、路径、活动与结果是隔离合成夹具，不代表真实后端执行。组件近景与首屏可直接阅读，完整页面可展开，每张图均可打开原始像素。</p><p><a href="DESIGN_REVIEW.md">设计审查</a> · <a href="VALIDATION.md">验证与局限</a> · <a href="manifest.json">图像清单</a> · ${manifest.length} 张截图</p></header><div class="layout"><nav aria-label="界面章节">${Object.entries(names).map(([k,t])=>`<a href="#${k}">${t}</a>`).join('')}</nav><main><div class="filters" role="group" aria-label="截图设备"><button data-filter="all" aria-pressed="true">全部</button><button data-filter="1440" aria-pressed="false">桌面 1440</button><button data-filter="420" aria-pressed="false">手机 420</button><button data-filter="320" aria-pressed="false">窄屏 320</button></div>${sections}</main></div><script>document.querySelectorAll('[data-filter]').forEach(b=>b.addEventListener('click',()=>{document.querySelectorAll('[data-filter]').forEach(n=>n.setAttribute('aria-pressed',String(n===b)));document.querySelectorAll('article[data-width]').forEach(n=>n.hidden=b.dataset.filter!=='all'&&n.dataset.width!==b.dataset.filter)}));</script></html>`);
}
try {
    page=await browser.newPage({dsf:2});
    await matrix(); await sections(); await edges();
    for(const [p,h] of Object.entries(buildHashes))if(hash(readFileSync(join(dist,p)))!==h)throw Error('Build changed during capture');
    writeFileSync(join(root,'manifest.json'),JSON.stringify({sourceCommit,frontendTree,buildHashes,images:manifest},null,2));
    writeFileSync(join(root,'browser-checks.json'),JSON.stringify(checks,null,2));gallery();
    console.log(JSON.stringify({root,count:manifest.length,bytes:manifest.reduce((a,b)=>a+b.bytes,0),sourceCommit,frontendTree,allPng:true,dpr:2}));
} finally { await browser.close(); }
