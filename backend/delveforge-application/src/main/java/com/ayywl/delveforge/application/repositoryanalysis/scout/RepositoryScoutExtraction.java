package com.ayywl.delveforge.application.repositoryanalysis.scout;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiMessage;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.ai.AiResponseFormat;
import com.ayywl.delveforge.application.port.ai.AiRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * 执行一次 Repository Scout：把源码候选的描述符交给 AI，得到一份查看计划。
 *
 * <pre>
 * RepositoryScoutInputs
 *         ↓  渲染成「编号 + 描述符」清单（**不含文件内容**）
 * AI Gateway → 原始模型输出
 *         ↓ RepositoryScoutProposalParser      严格解析
 * AiRepositoryScoutProposal（仍然带着未校验的引用）
 *         ↓ RepositoryScoutProposalResolver    引用校验
 * RepositoryInspectionPlan（已解析的真实描述符）
 * </pre>
 *
 * <p>本类只做这一步：
 *
 * <pre>
 * 不访问 Workspace，不读取任何文件内容
 * 不按计划去读文件——那属于后续阶段
 * 不创建、不保存 RepositoryProfile
 * </pre>
 *
 * <p>它的协作者只有 {@link AiGateway}、解析器与解析器，因此一次失败不可能留下领域或持久化
 * 副作用：失败只以异常结束。计划怎么被用来读取、读到的内容怎么变成 Profile，
 * 由后续的 Application 流程决定。
 *
 * <h2>它问的是「去哪里看」</h2>
 *
 * <p>提示词里写死了这个边界：模型看不到代码，因此没有资格对仓库下结论。它只能依据文件名、
 * 路径、大小与结构提示指出「接下来应该读哪些文件」。产出的 {@code label} 是查看意图，
 * 不是仓库能力——把 Scout 的分组当成 RepositoryProfile 的能力或依据，
 * 等于让一个只见过路径的模型替整次分析定调（ADR-0004）。
 *
 * <h2>失败不留下任何可信结果</h2>
 *
 * <p>Gateway 失败、解析失败、引用校验失败——任一种都以异常结束，不会返回部分计划。
 * 也不存在「丢掉不合法的区域、保留其余部分」这种降级：调用方会以为模型只指出了这几处，
 * 而真实情况是它的输出不可用。
 */
public final class RepositoryScoutExtraction {

    /**
     * 系统指令。
     *
     * <p>要求模型只输出 json：DeepSeek 的 JSON 模式要求提示词中出现 "json" 字样，
     * 同时这也让模型清楚不要附带解释文本。
     *
     * <p>区域数量用 {@link AiRepositoryScoutProposal} 的常量格式化进来，避免提示词与解析器
     * 各写一份会彼此漂移的范围。
     */
    private static final String SYSTEM_INSTRUCTION = """
            你是 DelveForge 的 Repository 侦察组件。

            你的任务不是分析代码，也不是给出任何结论。你拿到一份**文件清单**——每个文件有编号、
            路径、大小、语言、材料类别与结构角色提示，但**没有文件内容**。

            你要回答的只有一个问题：

                为了理解这个项目真正实现过什么业务，接下来应该去读哪些文件？

            只输出一个 json 对象，不要输出解释、Markdown 代码块或任何其他文字。格式如下：

            {
              "focusAreas": [
                { "label": "简短的查看区域名", "fileRefs": ["RF-1", "RF-7"] }
              ]
            }

            规则：
            - 给出 %d 到 %d 个彼此有区别的查看区域。
            - 每个区域的 label 简短说明「这一组文件打算用来看什么」。
            - 每个区域的 fileRefs 按建议的查看顺序排列：越靠前越应该先看。
            - fileRefs 只能使用清单里给出的编号。不要创造编号，也不要给出文件路径。
            - 同一个区域里不要重复同一个编号。同一个文件允许出现在不同的区域里。
            - 只依据清单里的描述符判断，不要猜测文件内容，也不要断言某个文件实现了什么。

            边界：
            - 你不是在给这个仓库下结论。label 只是「打算去哪里看」的提示，
              不是已经确认的仓库能力、可复用资产或风险。
            - 不要输出能力列表、复用资产、风险、复杂度、置信度或任何分析结论。
            - 你看到的只是文件名与路径，看不到代码，因此不要假装读过它们。
            """.formatted(
                    AiRepositoryScoutProposal.MIN_FOCUS_AREAS,
                    AiRepositoryScoutProposal.MAX_FOCUS_AREAS);

    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper;
    private final FileCatalogPayload fileCatalog;
    private final RepositoryScoutProposalParser proposalParser;
    private final RepositoryScoutProposalResolver proposalResolver;

    public RepositoryScoutExtraction(AiGateway aiGateway, ObjectMapper objectMapper) {
        if (aiGateway == null) {
            throw new IllegalArgumentException("RepositoryScoutExtraction 必须指定 aiGateway");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException(
                    "RepositoryScoutExtraction 必须指定 objectMapper");
        }
        this.aiGateway = aiGateway;
        this.objectMapper = objectMapper;
        this.fileCatalog = new FileCatalogPayload(objectMapper);
        this.proposalParser = new RepositoryScoutProposalParser(objectMapper);
        this.proposalResolver = new RepositoryScoutProposalResolver();
    }

    /**
     * 依据本次源码候选清单提取查看计划。
     *
     * <p>AI 调用、解析与引用校验都发生在同一步内，任一失败都以异常结束，不返回半成品。
     *
     * @param inputs 本次侦察的输入，不得为 {@code null}
     * @return 模型提出、并已通过结构与引用校验的查看计划
     * @throws IllegalArgumentException inputs 为 {@code null}
     * @throws AiGatewayException       AI 调用失败，返回内容不满足约定，或引用了本次没有提供的文件
     */
    public RepositoryInspectionPlan scout(RepositoryScoutInputs inputs) {
        if (inputs == null) {
            throw new IllegalArgumentException("RepositoryScoutExtraction 必须指定 inputs");
        }
        AiRepositoryScoutProposal proposal =
                proposalParser.parse(aiGateway.generate(buildRequest(inputs)));

        return proposalResolver.resolve(proposal, inputs);
    }

    /**
     * 本次 Scout 会发给模型的**目录载荷**大小，按 UTF-8 字节计。
     *
     * <p>它量的是用户消息——也就是描述符清单那一段；系统指令是固定文本，不随仓库变化。
     *
     * <p>存在的原因是「先量再调」：模型调用不便宜，而清单过大是本版本处理不了的仓库形态。
     * 量完之后由调用方决定怎么办——本类不自己判定上限，因为「这个仓库是否在当前版本的能力
     * 范围内」是一条**流程前置条件**，它的判定与失败语义属于调用这条链路的那一层。
     *
     * <p>本方法与 {@link #scout} 用的是同一个渲染入口与同一份输入，因此量到的就是即将发出的
     * 那一份载荷。
     *
     * @param inputs 本次侦察的输入，不得为 {@code null}
     * @return 目录载荷的 UTF-8 字节数
     */
    public int catalogPayloadBytes(RepositoryScoutInputs inputs) {
        if (inputs == null) {
            throw new IllegalArgumentException("RepositoryScoutExtraction 必须指定 inputs");
        }
        return fileCatalog.payloadBytes(inputs.analyzedRevision(), inputs.catalog());
    }

    private AiRequest buildRequest(RepositoryScoutInputs inputs) {
        return new AiRequest(
                List.of(
                        new AiMessage(AiRole.SYSTEM, SYSTEM_INSTRUCTION),
                        new AiMessage(AiRole.USER,
                                fileCatalog.render(inputs.analyzedRevision(), inputs.catalog()))),
                AiResponseFormat.JSON);
    }
}
