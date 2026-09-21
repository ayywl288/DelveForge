package com.ayywl.delveforge.application.repositoryanalysis.extraction;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiMessage;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.ai.AiResponseFormat;
import com.ayywl.delveforge.application.port.ai.AiRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 Repository 材料交给 AI，得到结构化的分析提议。
 *
 * <pre>
 * Repository 材料 → AI Gateway → 原始模型输出 → 解析 / 校验 → RepositoryAnalysisProposal
 * </pre>
 *
 * <p>本类只做这一步：
 *
 * <pre>
 * 不调用 Workspace，材料由调用方读出后交进来
 * 不获取 analyzedRevision
 * 不创建、不保存 RepositoryProfile
 * </pre>
 *
 * <p>它的唯一协作者是 {@link AiGateway} 与解析器，不持有任何 Repository / Aggregate，
 * 因此一次失败不可能留下领域或持久化副作用：失败只会以异常结束，不产生任何提议之外的结果。
 * 材料如何变成 Repository Profile，由后续的 Analyze Repository Use Case 决定。
 *
 * <h2>AI Proposes, Domain Decides</h2>
 *
 * <p>模型提出的分析结论不构成合法领域状态：它到这里为止只是
 * {@link RepositoryAnalysisProposal}，是否被接受、能否构成一个 Profile，
 * 由后续的 Application 流程与 {@code RepositoryProfile} Aggregate 判定（RULE-DOM-003）。
 *
 * <p>模型也无法自行决定 Evidence 的可信程度与确认状态：提议里只携带「判断 + 它在
 * Repository 中的位置」。而在提议被返回之前，每条依据的路径都会被核对——
 * 它必须确实来自本次交给模型的文件，否则整次分析被拒绝。
 */
public final class RepositoryAnalysisExtraction {

    /**
     * 系统指令。
     *
     * <p>要求模型只输出 json：DeepSeek 的 JSON 模式要求提示词中出现 "json" 字样，
     * 同时这也让模型清楚不要附带解释文本。
     */
    private static final String SYSTEM_INSTRUCTION = """
            你是 DelveForge 的软件资产理解组件。你的任务是根据给出的 Repository 文件内容，
            提出对该 Repository 的结构化分析结论。

            只输出一个 json 对象，不要输出解释、Markdown 代码块或任何其他文字。格式如下：

            {
              "purpose": "...",
              "techStack": ["..."],
              "modules": ["..."],
              "capabilities": ["..."],
              "reusableAssets": ["..."],
              "limitations": ["..."],
              "risks": ["..."],
              "evidence": [
                { "claim": "...", "sourceRef": "文件的相对路径" }
              ]
            }

            规则：
            - 每个字段都必须出现。某个方面确实没有内容时给出空数组，不要省略字段。
            - 只依据给出的文件内容得出结论。不要臆测没有读到的代码、依赖或用途，
              也不要为了填满字段而编造内容。
            - evidence 的 sourceRef 必须是给出材料中的文件路径，逐条对应支撑该判断的文件。
              不要引用没有在材料里出现过的路径。
            - purpose 用一句话说明这个 Repository 当前解决的问题或主要用途。
            """;

    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper;
    private final RepositoryAnalysisProposalParser proposalParser;

    public RepositoryAnalysisExtraction(AiGateway aiGateway, ObjectMapper objectMapper) {
        if (aiGateway == null) {
            throw new IllegalArgumentException("RepositoryAnalysisExtraction 必须指定 aiGateway");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException(
                    "RepositoryAnalysisExtraction 必须指定 objectMapper");
        }
        this.aiGateway = aiGateway;
        this.objectMapper = objectMapper;
        this.proposalParser = new RepositoryAnalysisProposalParser(objectMapper);
    }

    /**
     * 依据给定的 Repository 材料提取分析提议。
     *
     * <p>AI 调用与解析都发生在同一步内，两者任一失败都以异常结束，不返回半成品。
     *
     * @param files 从 Repository 读出的材料，不得为 {@code null} 或空
     * @return 模型提出、并已通过结构校验的分析提议
     * @throws IllegalArgumentException 材料缺失、为空，或含为 {@code null} 的条目
     * @throws AiGatewayException       AI 调用失败，或返回内容不满足约定
     */
    public RepositoryAnalysisProposal extract(List<RepositorySourceFile> files) {
        requireFiles(files);

        RepositoryAnalysisProposal proposal =
                proposalParser.parse(aiGateway.generate(buildRequest(files)));
        requireEvidenceRefersToSentFiles(proposal, files);

        return proposal;
    }

    /**
     * 校验每条 Evidence 的 sourceRef 都指向本次真正交给模型的文件。
     *
     * <p>提示词已经要求模型只能引用材料中的路径，但那只是要求：模型完全可以给出一个
     * 看起来合理、却从未被读取过的路径。那样产生的 Evidence 无法指向实际存在的代码或配置，
     * 而「依据可追溯」正是 Evidence 存在的意义（DOMAIN_MODEL.md §3.6：
     * 依据「不应由无法定位依据的模型输出凭空产生」）。因此这条限制必须由代码兜住。
     *
     * <p>只要有一条依据的路径不在本次材料中，整次分析就被拒绝，而不是丢掉那一条：
     * 无法定位的依据说明模型这次没有按材料作答，其结论整体都不可信，
     * 保留其余部分等于把一份来源已经不可靠的分析当成可用的分析。
     *
     * <p>匹配是精确的：路径必须与交给模型的相对路径完全相同。不做归一化——
     * {@code ./pom.xml} 与 {@code pom.xml}、不同分隔符都算不同路径，
     * 因为本层没有关于「什么算同一个路径」的可靠依据可依赖。
     *
     * @throws AiGatewayException 存在指向本次材料之外文件的 Evidence
     */
    private static void requireEvidenceRefersToSentFiles(
            RepositoryAnalysisProposal proposal, List<RepositorySourceFile> files) {

        Set<String> sentPaths = new HashSet<>(files.size());
        for (RepositorySourceFile file : files) {
            sentPaths.add(file.relativePath());
        }

        for (RepositoryEvidenceProposal evidence : proposal.evidence()) {
            if (!sentPaths.contains(evidence.sourceRef())) {
                throw new AiGatewayException(
                        "AI 提出的依据指向了本次没有提供的文件: " + evidence.sourceRef());
            }
        }
    }

    /**
     * 校验材料非空。
     *
     * <p>没有材料就没有可分析的内容：让模型在这种情况下「凭空」给出结论，
     * 得到的不是分析而是编造，因此这里直接拒绝，而不是把空材料交给模型。
     *
     * @throws IllegalArgumentException 材料为 {@code null}、为空，或含 {@code null} 条目
     */
    public static void requireFiles(List<RepositorySourceFile> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Repository 分析必须提供待分析的文件");
        }
        for (RepositorySourceFile file : files) {
            if (file == null) {
                throw new IllegalArgumentException("Repository 分析的文件条不能为 null");
            }
        }
    }

    private AiRequest buildRequest(List<RepositorySourceFile> files) {
        return new AiRequest(
                List.of(
                        new AiMessage(AiRole.SYSTEM, SYSTEM_INSTRUCTION),
                        new AiMessage(AiRole.USER, describeFiles(files))),
                AiResponseFormat.JSON);
    }

    /**
     * 请求内容：材料按「相对路径 + 内容」结构化给出。
     *
     * <p>只发文件本身，不发宿主机的绝对路径，也不发 analyzedRevision：
     * 模型需要的是内容，位置由相对路径表达——这也是 evidence 的 sourceRef 能指向的东西。
     */
    private String describeFiles(List<RepositorySourceFile> files) {
        List<Map<String, Object>> material = new ArrayList<>(files.size());
        for (RepositorySourceFile file : files) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", file.relativePath());
            entry.put("content", file.content());
            material.add(entry);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("repositoryFiles", material);

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new AiGatewayException("无法构造 AI 请求内容", exception);
        }
    }
}
