// 仅供离线测试与视觉验收使用；不是用户历史、真实 AI 分析或执行结果。
import type { Evidence, ProductDirection, ProjectSummary, UserProfile, RepositoryProfile, EvolutionPlan } from "../src/api/types";
import { direction, plan, profile, repository } from "./fixtures.ts";

export function userEvidence(count = 23): Evidence[] {
    const claims = [
        "每周都要按相同条件导出和核对业务报表，希望减少重复操作。",
        "报表使用者仍需要现有文件格式，新增能力应兼容已有导出接口。",
        "用户能维护 Java、Spring Boot 和 Maven 项目，熟悉接口测试。",
        "首次演化希望控制在配置与调度范围，先验证增量变化再继续。",
        "业务数据有跨月范围与缺失字段，需要明确时间边界和失败提示。",
    ];
    return Array.from({ length: count }, (_, index) => ({
        sourceType: "USER_INPUT", claim: `依据 ${index + 1}：${claims[index % claims.length]}`,
        sourceRef: `合成访谈记录 ${index + 1}：${claims[index % claims.length]}\n`
                + "原始输入补充：我希望复用现有工具，保留可检查的参数和报表结果，按步骤审阅变化。",
        confidence: index % 3 === 0 ? null : 0.85, confirmed: index % 5 === 0,
    }));
}
export function repositoryEvidence(count = 27): Evidence[] {
    const facts = [
        "现有 ReportService 提供报表生成与导出入口，可作为增量调度的基础。",
        "配置读取逻辑区分日期范围与导出参数，测试覆盖缺失字段。",
        "导出流程保留独立的文件格式转换层，新增配置无需替换该接口。",
        "测试夹具使用固定输入数据，能重复验证汇总和时间边界。",
        "构建依赖 Maven Wrapper；构建插件将在宿主运行，需保持可信来源。",
    ];
    return Array.from({ length: count }, (_, index) => ({
        sourceType: "REPOSITORY", claim: `源码依据 ${index + 1}：${facts[index % facts.length]}`,
        sourceRef: `src/main/java/example/reports/configuration/validation/ReportConfiguration${index + 1}.java:${42 + index}`,
        confidence: index % 4 === 0 ? null : 0.92, confirmed: false,
    }));
}
export function longProfile(): UserProfile {
    return { ...profile(), status: "CONFIRMED", revision: 2,
        interests: ["开发可以长期使用的个人工具", "Java 后端与可重复验证的自动化流程"],
        behaviors: ["每周从业务系统导出数据，检查日期范围和缺失字段后整理报表"],
        painPoints: ["重复填写参数、导出和核对数据容易遗漏，也占用工作时间"],
        technicalCapabilities: ["熟悉 Java、Spring Boot 与 Maven", "能够编写接口和自动化测试"],
        projectGoals: ["在现有报表工具中增加可配置的周期调度，保留已有导出接口"],
        constraints: ["原始仓库只读分析，变化仅在授权副本内发生", "保留文件格式与数据检查行为"],
        evidence: userEvidence() };
}
export function longRepository(): RepositoryProfile {
    return { ...repository(), purpose: "支持业务汇总、参数检查与文件导出的 Java 报表工具",
        techStack: ["Java 21", "Spring Boot", "Maven Wrapper", "JUnit 5"],
        modules: ["reports — 汇总与报表生成", "configuration — 日期与导出参数", "export — 文件格式转换", "tests — 行为验证"],
        capabilities: Array.from({ length: 16 }, (_, index) => `能力 ${index + 1}：`
                + ["按保存的日期范围查询和汇总数据", "检查导出参数与缺失字段，返回可定位的问题",
                    "复用现有接口导出报表文件", "使用测试夹具重复验证既有输出行为"][index % 4]),
        reusableAssets: ["ReportService 的生成与导出入口", "配置校验与日期边界测试", "固定输入数据与导出测试夹具"],
        limitations: ["导出目前需要手动启动", "现有 API 没有完整历史源码 diff 查询"],
        risks: ["调度需避免重复执行与遗漏", "构建插件运行于宿主环境，不能视为系统沙箱"],
        evidence: repositoryEvidence() };
}
export function longDirections(): ProductDirection[] {
    const titles = ["可配置的周期报表", "报表模板管理", "导出前的数据检查", "日期范围配置助手",
        "报表参数复用", "导出结果核对", "报表任务记录", "配置校验工作台",
        "轻量报表预览", "常用查询管理", "文件格式兼容检查", "多报表配置整理"];
    const user = longProfile(), repo = longRepository();
    return [...titles.map((title, index): ProductDirection => ({ ...direction(), id: `direction-long-${index + 1}`,
        title, status: index === 0 ? "SELECTED" : "CANDIDATE",
        problem: `方向 ${index + 1}：每周重复填写参数、启动导出和检查业务数据，容易遗漏，也占用工作时间。`
                + (index % 3 === 0 ? "不同业务使用不同的日期范围与格式，需要明确当前配置和检查边界。" : ""),
        targetProduct: `在现有 Java 报表工具中增加${title}，复用已保存的配置与导出流程。`
                + "保留文件格式、原有接口和日期检查行为；每次变化都限制范围并通过自动化测试核对。".repeat(index % 4 + 1),
        userFit: "直接改善用户每周的重复工作，沿用熟悉的工具和输出格式；规模适合现有 Java 与测试经验。",
        differentiation: "以现有报表能力为起点，保留兼容性和可检查的配置，减少迁移成本。",
        technicalValue: "练习配置校验、日期边界与可重复的增量验证。".repeat(index % 3 + 1),
        risks: ["日期与时区需要明确测试", "重复任务需显式防止重复导出"],
        evidenceSupport: {
            userNeed: user.evidence.slice(index, index + 2).map(evidence => ({ evidence,
                origin: { kind: "USER_PROFILE", userProfileId: user.id, userProfileRevision: 2, repositoryProfileId: null } })),
            userFit: [], reusableCapability: repo.evidence.slice(index, index + 2).map(evidence => ({ evidence,
                origin: { kind: "REPOSITORY_PROFILE", userProfileId: null, userProfileRevision: null, repositoryProfileId: repo.id } })),
        },
    })), { ...direction(), id: "direction-old-1", title: "合成历史 · 完整报表平台", status: "SUPERSEDED" },
        { ...direction(), id: "direction-old-2", title: "合成历史 · 全量重写方案", status: "REJECTED" }];
}
export function longProjectSummary(): ProjectSummary {
    return { key: "project:long-content", name: "合成验收 · 长内容报表工具", legacy: false, stage: "DIRECTION",
        direction: "可配置的周期报表", repositories: Array.from({ length: 9 }, (_, index) => ({
            assetId: `asset-long-${index + 1}-12345678-1234-5678-1234-123456789abc`,
            location: `C:/synthetic/repositories/team-reporting/configuration-and-export/`
                    + `service-${index + 1}/preserved-original-format/validation-and-tests/reporting-tool`,
        })), lastActivity: "2026-10-11T10:30:00Z", totalSteps: 3, succeededSteps: 1,
        stepCounts: { SUCCEEDED: 1, PENDING_CONFIRMATION: 2 }, planId: "plan-long-12345678-1234-5678-1234-123456789abc",
        planStatus: "ACTIVE", workingCopyId: "copy-long-12345678-1234-5678-1234-123456789abc",
        currentRevision: "b".repeat(40), lastVerifiedRevision: "b".repeat(40),
        issues: ["PROFILE_BASIS_CHANGED"], version: 1, deletedAt: null };
}

export function fitFinishPlan(): EvolutionPlan {
    const current = plan();
    return { ...current, status: "PROPOSED", workingCopyId: null,
        currentState: {
            summary: "现有工具按日期范围生成业务报表，并通过独立接口导出文件。\n\n"
                    + "当前实现保留参数检查、缺失字段提示和固定输入测试。调度尚未纳入已有能力，需要先明确增量范围，再核对兼容性。\n\n".repeat(8),
            capabilities: Array.from({ length: 24 }, (_, i) => `现状能力 ${i + 1}：复用 ReportService、日期范围检查和现有导出格式，保留输入校验与定位错误的能力。`),
            modules: ["reports — 业务汇总与报告生成", "configuration — 参数与日期边界检查", "export — 文件格式转换与导出"],
            limitations: ["目前需要用户手动启动导出", "原有代码没有已实现的任务重试与调度状态"],
        },
        targetState: {
            problem: "每周按相同条件导出报表占用时间，参数和日期范围容易重复填写。\n\n".repeat(12),
            targetProduct: "在现有报表工具中增加可配置的周期调度。\n\n"
                    + "新增能力复用已有导出接口、配置检查与测试夹具，继续支持原格式。每一步限制变化范围，通过固定输入检查与既有行为测试确认结果。\n\n".repeat(14),
            differentiation: "以已有能力为基础，保留使用者熟悉的文件格式和可检查的参数；完整规划原文与验证要求一直可读。\n\n".repeat(6),
        },
        reusableCapabilities: Array.from({ length: 15 }, (_, i) => `复用能力 ${i + 1}：现有接口、参数检查及固定输入测试夹具`),
        changes: ["为已有导出入口增加周期配置", "补充日期边界与兼容性测试"],
        evidence: repositoryEvidence().map(evidence => ({ evidence,
            origin: { kind: "REPOSITORY_PROFILE", userProfileId: null, userProfileRevision: null, repositoryProfileId: "repo-1" } })),
        steps: current.steps.map(step => ({ ...step, status: "PENDING_CONFIRMATION" })),
    };
}
