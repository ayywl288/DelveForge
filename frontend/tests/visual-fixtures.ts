// 仅用于真实浏览器视觉审阅；所有名字、路径、时间和运行证据均为合成数据。
import { asset, copy, direction, plan, profile, repository } from './fixtures';

const revision = 'a'.repeat(40);
const evidence = { sourceType: 'REPOSITORY', sourceRef: 'src/main/java/reports/ReportService.java:24-68',
    claim: 'ReportService 已提供按日期范围导出 CSV 的能力，可作为周期报表的复用基础。', confidence: 0.9, confirmed: false };

export function visualFixture(scenario: string, path: string, query?: URLSearchParams): unknown {
    const user = { ...profile(), revision: 4, status: scenario.startsWith('discovery') ? 'EXPLORING' : scenario.startsWith('review') ? 'REVIEWING' : 'CONFIRMED',
        interests: ['让重复工作变得简单，保留对代码的理解与控制。'], behaviors: ['每周五整理项目数据，手动导出报表并核对。'],
        painPoints: ['重复导出容易遗漏，缺少可追溯的执行记录。'], technicalCapabilities: ['熟悉 Java、Spring Boot 与 Maven，能够维护现有服务。'],
        projectGoals: ['在已有报表工具上增加周期调度与可靠记录。'], constraints: ['先支持本地运行；每一步都需要明确范围与验证。'],
        evidence: [{ ...evidence, sourceType: 'USER_STATEMENT', sourceRef: 'conversation:2', claim: '每周都需要重复导出并手动核对报表。', confirmed: true }] };
    if (scenario === 'review-incomplete') { user.status = 'EXPLORING'; user.constraints = []; user.painPoints = []; }
    const a = { ...asset(), location: '/sample/workspace/report-kit' };
    const repo = { ...repository(), purpose: 'Report Kit · 本地报表服务', techStack: ['Java 21', 'Spring Boot', 'Maven', 'SQLite'],
        modules: ['reports — 报表生成与 CSV 导出', 'storage — 本地任务与记录', 'api — REST 接口'],
        capabilities: ['按日期范围生成报表', '导出标准 CSV 文件', '记录导出任务与结果'],
        reusableAssets: ['ReportService：导出与数据汇总', 'ExportRepository：本地记录持久化'],
        limitations: ['目前只能手动触发导出', '缺少周期配置与调度失败记录'], risks: ['调度时区需要明确', '重复触发必须避免重复写入'], evidence: [evidence] };
    if (scenario === 'stress') repo.evidence = Array.from({ length: 25 }, (_, index) => ({ ...evidence,
        claim: `合成依据 ${index + 1}：完整保存模块、能力与来源之间的对应关系。`,
        sourceRef: 'src/' + 'long-unbroken-repository-path'.repeat(20) + `/ReportService${index + 1}.java:24-68` }));
    const selected = { ...direction(), userProfileRevision: 4, title: '让每周报表按时完成', status: 'SELECTED',
        targetProduct: '基于 Report Kit 的周期报表工作台', problem: '每周重复手动导出，难以知道哪些报表已成功生成。',
        userFit: '沿用你熟悉的 Java 服务，把时间花在数据核对而不是重复操作上。',
        differentiation: '保留现有导出逻辑，以小步调度能力和执行记录补齐闭环。', technicalValue: '练习可测试的调度边界、幂等执行与失败追踪。',
        evidenceSupport: { userNeed: [{ evidence: user.evidence[0], origin: { kind: 'USER_PROFILE', userProfileId: user.id, userProfileRevision: 4, repositoryProfileId: null } }],
            userFit: [], reusableCapability: [{ evidence, origin: { kind: 'REPOSITORY_PROFILE', userProfileId: null, userProfileRevision: null, repositoryProfileId: repo.id } }] } };
    const alternatives = [{ ...selected, id: 'direction-2', title: '报表差异核对助手', status: 'CANDIDATE', problem: '比较两次导出，快速定位数据变化。' },
        { ...selected, id: 'direction-3', title: '轻量导出任务中心', status: 'CANDIDATE', problem: '集中查看手动任务与历史导出文件。' }];
    const p = plan();
    p.currentState = { summary: '按需生成，手动导出', capabilities: repo.capabilities, modules: repo.modules, limitations: repo.limitations };
    p.targetState = { problem: selected.problem, targetProduct: selected.targetProduct, differentiation: selected.differentiation };
    p.evidence = selected.evidenceSupport.reusableCapability;
    p.risks = repo.risks;
    p.steps = [
        { ...p.steps[0]!, goal: '建立周期配置与校验', scope: 'reports / scheduling configuration', status: 'SUCCEEDED', baselineRevision: revision, deliveryRevision: 'c'.repeat(40), plannedChanges: ['新增周期与时区配置', '为配置边界添加单元测试'] },
        { ...p.steps[0]!, id: 'step-2', goal: '连接调度与现有导出能力', scope: 'reports / scheduler and export service', status: scenario === 'delivery' ? 'SUCCEEDED' : 'READY', baselineRevision: scenario === 'delivery' ? 'c'.repeat(40) : null, deliveryRevision: scenario === 'delivery' ? 'd'.repeat(40) : null,
            plannedChanges: ['复用 ReportService，不重写现有导出', '记录调度触发与失败原因', '验证重复触发和时区边界'] },
        { ...p.steps[0]!, id: 'step-3', goal: '提供执行记录与验收入口', scope: 'api / report execution history', status: 'PENDING_CONFIRMATION', plannedChanges: ['展示真实执行结果', '补充端到端接口回归'] },
    ];
    if (scenario === 'delivery') p.steps = p.steps.slice(0, 2);
    if (scenario === 'initial-authorization' || scenario === 'planning-authorize') p.steps[1]!.status = 'PENDING_CONFIRMATION';
    if (scenario === 'plan-proposed') {
        p.status = 'PROPOSED'; p.workingCopyId = null;
        p.steps = p.steps.map(step => ({ ...step, status: 'PENDING_CONFIRMATION' as const, baselineRevision: null, deliveryRevision: null }));
    }
    const wc = { ...copy(), location: '/sample/isolated/report-kit', currentRevision: 'c'.repeat(40), lastVerifiedRevision: 'c'.repeat(40) };
    if (scenario === 'delivery') {
        p.status = 'COMPLETED';
        wc.status = 'CLOSED';
        wc.currentRevision = wc.lastVerifiedRevision = 'd'.repeat(40);
    }
    const stage = ({ discovery: 'DISCOVERY', 'discovery-waiting': 'DISCOVERY', review: 'DISCOVERY', 'review-incomplete': 'DISCOVERY', repository: 'REPOSITORY', stress: 'REPOSITORY', directions: 'DIRECTION', planning: 'PLANNING', 'planning-authorize': 'PLANNING', 'plan-proposed': 'PLANNING' } as Record<string, string>)[scenario] ?? 'EVOLUTION';
    const context = { id: 'visual-project', name: 'Report Kit · 周期报表', userProfileId: user.id, assetIds: [a.id], repositoryProfileIds: [repo.id], directionIds: [selected.id, ...alternatives.map(d => d.id)], planId: p.id,
        createdAt: '2026-10-30T08:00:00Z', updatedAt: '2026-10-31T02:20:00Z', version: 2, flowId: 'visual-flow', previousContextId: null, deletedAt: null };
    const summary = { key: 'project:visual-project', name: context.name, legacy: false, stage: 'EVOLUTION', direction: selected.targetProduct,
        repositories: [{ assetId: a.id, location: a.location }], lastActivity: context.updatedAt, totalSteps: p.steps.length, succeededSteps: p.steps.filter(step => step.status === 'SUCCEEDED').length,
        stepCounts: p.steps.reduce((counts, step) => ({ ...counts, [step.status]: (counts[step.status] ?? 0) + 1 }), {} as Record<string, number>), planId: p.id, planStatus: p.status, workingCopyId: p.workingCopyId,
        currentRevision: wc.currentRevision, lastVerifiedRevision: wc.lastVerifiedRevision, issues: [], version: 2, deletedAt: null };
    const run = { id: 'run-visual-1', planId: p.id, stepId: 'step-2', workingCopyId: wc.id, baseline: 'c'.repeat(40),
        state: scenario === 'evolution' ? 'RUNNING' : 'SUCCEEDED', startedAt: '2026-10-31T02:15:00Z', endedAt: scenario === 'evolution' ? null : '2026-10-31T02:18:00Z',
        reason: null, candidateId: scenario === 'evolution' ? null : 'candidate-visual-1', cancelRequested: false, retained: false, modelCalls: 4, toolCalls: 12, tokens: 8620 };
    if (scenario === 'execution-failed') Object.assign(run, { state: 'FAILED', candidateId: null, reason: 'NO_PROGRESS' });
    if (path === '/system/connectivity') return { service: 'visual-fixture', status: 'UP', timestamp: context.updatedAt };
    if (path === '/system/workspace-configuration') return { root: '/sample/isolated', existingRoots: [] };
    if (path === '/projects' && scenario === 'empty') return [];
    if (path === '/projects' && scenario === 'deleted') return [{ ...summary, deletedAt: '2026-10-31T03:00:00Z', stepCounts: { SUCCEEDED: 1, PENDING_CONFIRMATION: 2 } }];
    if (path === '/projects') return [summary, { ...summary, key: 'project:archive', name: 'CLI 日志阅读器', direction: '从日志中找到故障线索', stage: 'PLANNING', planStatus: 'PROPOSED', totalSteps: 2, succeededSteps: 0, stepCounts: { PENDING_CONFIRMATION: 2 }, planId: 'plan-archive', workingCopyId: null },
        { ...summary, key: 'project:research', name: 'API 契约检查', direction: null, stage: 'DISCOVERY', planId: null, planStatus: null, totalSteps: 0, succeededSteps: 0, stepCounts: {} }];
    if (path.includes('/rounds')) return [];
    if (path === '/projects/resources') {
        const kind = query?.get('kind') ?? 'USER_PROFILE';
        if (kind === 'USER_PROFILE') return [{ id: user.id, status: 'CONFIRMED', revision: 4, summary: '周期报表需求画像', assetId: null, location: null, analyzedRevision: null }];
        return [{ id: kind === 'SOFTWARE_ASSET' ? a.id : repo.id, summary: repo.purpose, status: 'AVAILABLE', revision: null, assetId: a.id, location: a.location, analyzedRevision: repo.analyzedRevision }];
    }
    if (path.startsWith('/projects/')) return { key: summary.key, context, legacy: false, userProfile: user, assets: [a], repositoryProfiles: [repo], directions: [selected, ...alternatives], plan: p, workingCopy: scenario === 'plan-proposed' ? null : wc, stage, boundUserProfileRevision: 4, issues: [] };
    if (path.endsWith('/review-readiness')) return { profileId: user.id, revision: 4, sufficient: scenario !== 'review-incomplete', missingAreas: scenario === 'review-incomplete' ? ['需要进一步明确约束与使用场景。'] : [] };
    if (path.includes('/conversation')) return { messages: [
        { sequence: 1, requestId: 'request-1', role: 'agent', text: '从最近一次重复工作开始吧。你最想省下哪一部分时间？', question: null, questionState: null, createdAt: '2026-10-31T01:00:00Z', projectId: context.id },
        { sequence: 2, requestId: 'request-2', role: 'user', text: '每周五都要手动导出报表，然后逐项核对有没有遗漏。我已经有一个 Java 报表服务，希望先在现有代码上加周期调度。', question: null, questionState: null, createdAt: '2026-10-31T01:01:00Z', projectId: context.id },
        { sequence: 3, requestId: 'request-2', role: 'agent', text: '保留已有服务是很清楚的起点。除了按时导出，你希望如何确认一次任务确实完成？', question: '你希望如何确认一次任务确实完成？', questionState: 'ASKED', createdAt: '2026-10-31T01:02:00Z', projectId: context.id },
    ], before: null, pendingRequestId: null, unansweredQuestion: '你希望如何确认一次任务确实完成？' };
    if (path.startsWith('/user-profiles/')) return user;
    if (path.endsWith('/compatibility')) return { revision, status: 'COMPATIBLE', policyVersion: 'fixture', mavenDirectory: '.', modules: ['reports'], buildJdk: 21, sourceBytes: 45200, files: 24, textFiles: 24, opaqueFiles: 0, generatedPaths: [], immutablePaths: [], issues: [] };
    if (path.startsWith('/software-assets/')) return a;
    if (path.startsWith('/repository-profiles/')) return repo;
    if (path.startsWith('/product-directions/')) return selected;
    if (path.endsWith('/quick-start')) return { planId: p.id, candidateId: 'candidate-visual-1', revision: 'd'.repeat(40), status: 'AVAILABLE', stale: false, updatedAt: '2026-11-08T00:00:00Z', reason: null,
        markdown: '## 环境要求\n- **Java 21**\n- **Node 22** 与 pnpm\n## 配置\n复制仓库提供的环境示例，填写本地配置；秘密值不包含在指南中。\n## 安装依赖\n```sh\n./mvnw package\npnpm --dir frontend install --frozen-lockfile\n```\n## 启动项目\n```sh\njava -jar target/report-kit.jar\npnpm --dir frontend dev\n```\n请根据 README 中的说明访问本地地址。以上为合成界面数据，命令未执行验证。' };
    if (path.endsWith('/agent-runs')) return scenario.startsWith('initial') ? [] : [run];
    if (path.endsWith('/stream')) return [
        { kind: 'RUNNING', summary: '隔离沙箱已准备，开始当前 Step。' },
        { kind: 'TOOL_FINISHED', summary: JSON.stringify({ tool: 'read_file', path: 'src/main/java/reports/ReportService.java', durationMs: 12, ok: true }) },
        { kind: 'TOOL_FINISHED', summary: JSON.stringify({ tool: 'apply_patch', path: 'src/main/java/reports/ReportScheduler.java', durationMs: 18, ok: true, classification: 'PATCH_APPLIED' }) },
        { kind: 'WORKING_NOTES', summary: JSON.stringify({tasks: [{title: '检查已有导出接口', status: 'completed'}, {title: '实现周期调度', status: 'completed'}, {title: '运行检查并记录限制', status: 'in_progress'}]}) },
        { kind: 'TOOL_FINISHED', summary: JSON.stringify({tool: 'exec_command', command: './mvnw test', directory: '.', exitCode: 1, durationMs: 1532, ok: false, classification: 'COMMAND_FAILED', resultRecorded: true, stdout: 'Tests run: 12, Failures: 1, Skipped: 0', stderr: 'SchedulerTest > scheduledTime: expected local timezone', outputTruncated: false}) },
        { kind: 'MODEL_REQUEST', summary: '正在结合现有导出接口准备下一次检查。' },
    ].map((event, i) => ({ ...event, sequence: i + 1, runId: run.id, at: '2026-10-31T02:16:00Z' }));
    if (path.startsWith('/agent-runs/')) return run;
    if (path.endsWith('/delivery')) return { candidateId: run.candidateId, state: scenario === 'delivery' ? 'COMPLETED' : scenario === 'recovery' ? 'RECOVERY_REQUIRED' : 'DELIVERY_BLOCKED', revision: scenario === 'delivery' ? 'd'.repeat(40) : null, reason: scenario === 'delivery' ? null : scenario === 'recovery' ? 'MUTATION_STATE_UNKNOWN' : 'WORKING_COPY_BUSY' };
    if (path.endsWith('/diff')) return 'diff --git a/src/main/java/reports/ReportScheduler.java b/src/main/java/reports/ReportScheduler.java\nnew file mode 100644\n--- /dev/null\n+++ b/src/main/java/reports/ReportScheduler.java\n@@ -0,0 +1 @@\n+class ReportScheduler {}\n';
    if (path.startsWith('/agent-candidates/')) return { id: run.candidateId, runId: run.id, baseline: run.baseline, expectedTree: 'b'.repeat(40), sha256: 'f'.repeat(64), changedPaths: ['src/main/java/reports/ReportScheduler.java', 'src/test/java/reports/ReportSchedulerTest.java'], diffBytes: 1340, testEvidence: '{"status":"NOT_TESTED"}', createdAt: run.endedAt };
    if (path.endsWith('/final-changes')) return { planId: p.id, stepId: 'step-2', baselineRevision: 'c'.repeat(40), verifiedRevision: 'd'.repeat(40), availability: 'AVAILABLE', reason: null, baselineTree: 'b'.repeat(40), verifiedTree: 'e'.repeat(40), files: [{ path: 'src/main/java/reports/ReportScheduler.java', changeType: 'ADDED' }], counts: { added: 1, modified: 0, deleted: 0 } };
    if (path.startsWith('/evolution-plans/')) return p;
    if (path.startsWith('/working-copies/')) return wc;
    return undefined;
}
