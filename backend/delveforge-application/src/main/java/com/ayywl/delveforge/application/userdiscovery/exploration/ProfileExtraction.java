package com.ayywl.delveforge.application.userdiscovery.exploration;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.userdiscovery.conversation.DiscoveryAiException;
import com.ayywl.delveforge.application.userdiscovery.shared.DiscoveryContext;
import com.ayywl.delveforge.application.userdiscovery.shared.ProfilePromptContext;
import com.ayywl.delveforge.application.userdiscovery.sufficiency.DiscoveryQuestions;
import com.ayywl.delveforge.domain.user.UserProfile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 提取完整分区建议；确定性策略决定自动追加或整轮待批准，模型不授予覆盖权限。 */
public final class ProfileExtraction {

    private static final String SYSTEM_INSTRUCTION = """
            你是 DelveForge 的用户理解组件。根据当前 User Profile 与本轮输入提出更新建议。
            只输出一个 json 对象，不附解释或 Markdown。可用字段：
            interests、behaviors、painPoints、technicalCapabilities、projectGoals、constraints、evidenceClaims。
            六个内容区的值是建议的完整字符串数组；省略或 null 表示不变，不是增量。
            每区最多64条，每条为不超过2048字符的非空字符串，不能用对象、数字或嵌套数组表示条目。
            保留已有条目的原文和顺序，将新增事实放在末尾。用户明确纠正时可以建议新的完整分区。
            当前画像是已接受的事实；历史对话和 Evidence 只是当时的输入，不能恢复已被取代的值。
            仅从本轮真实输入提取新信息，可忠实整理和转述，不要求逐字摘录，不臆测、不填空。
            evidenceClaims 是可省略的辅助说明，只接受本轮输入直接支持的简短字符串数组，最多64条、每条最多2048字符。
            不在其中输出对象、数组、数字、布尔值、来源或确认状态；不把历史判断写成本轮新依据。
            应用始终保留真实本轮输入作为来源，缺少辅助说明不妨碍有效的内容建议。
            模型只提出建议，应用会要求用户批准覆盖、删除、重排或受人工保护分区的任何变化。
            不撤回历史依据。
            附和、请求下一问、不知道、拒答、询问原因或含糊纠正不产生画像事实，不推断为没有能力或约束。
            请求改聊生活、换个话题或询问能否聊某类问题，是对话操作，不据此新增 behaviors 或 constraints。
            没有新事实时不输出内容区和 evidenceClaims。明确没有重要约束可记录为 constraints。
            用户明确放弃原项目或改变目标是真实意图变化：可建议替换或清空 projectGoals 中已过时的目标，
            由用户批准；不要保留互相矛盾的新旧目标，也不要删除无关能力、历史或依据。
            投入时间未知不等于没有约束，更不能编造时长；不要求每轮都补全六区。

            json 示例（每个内容区都是建议的完整分区，不是仅列新增项）：
            没有新事实：{"hasNewFacts":false}
            只请求换话题（如询问能否聊生活）：{"hasNewFacts":false}
            当前 technicalCapabilities=["Java"]，本轮说也在用 Spring Boot：
            {"hasNewFacts":true,"technicalCapabilities":["Java","Spring Boot"]}
            当前 projectGoals=["开发 Coding Agent"]，本轮明确改做课程笔记工具：
            {"hasNewFacts":true,"projectGoals":["整理课程笔记的小工具"]}
            如需附带可选说明：
            {"hasNewFacts":true,"projectGoals":["整理课程笔记的小工具"],"evidenceClaims":["用户希望改做课程笔记工具"]}
            """;

    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper;
    private final UserProfileProposalParser parser;

    public ProfileExtraction(AiGateway aiGateway, ObjectMapper objectMapper) {
        if (aiGateway == null || objectMapper == null) {
            throw new IllegalArgumentException("ProfileExtraction 必须指定 aiGateway 和 objectMapper");
        }
        this.aiGateway = aiGateway;
        this.objectMapper = objectMapper;
        this.parser = new UserProfileProposalParser(objectMapper);
    }

    public static void requireUserInput(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("User Discovery 的用户输入不能为空");
        }
    }

    public void applyTo(UserProfile candidate, String input) {
        extract(candidate, input, List.of(), null, List.of(), List.of(), false);
    }

    public ProfileUpdatePolicy.Update applyTo(UserProfile candidate, String input, List<String> protectedSections) {
        return extract(candidate, input, List.of(), null, List.of(), protectedSections, false);
    }

    public void applyTurnTo(UserProfile candidate, String input, List<DiscoveryContext.Message> context) {
        applyTurnTo(candidate, input, context, null, List.of(), List.of());
    }

    public ProfileUpdatePolicy.Update applyTurnTo(UserProfile candidate, String input,
            List<DiscoveryContext.Message> context, String pendingQuestion, List<String> deferredAreas,
            List<String> protectedSections) {
        return extract(candidate, input, context, pendingQuestion, deferredAreas, protectedSections, true);
    }

    private ProfileUpdatePolicy.Update extract(UserProfile candidate, String input,
            List<DiscoveryContext.Message> context, String pendingQuestion, List<String> deferredAreas,
            List<String> protectedSections, boolean turn) {
        requireUserInput(input);
        String control = input.strip().replaceAll("[\\s，。！？,.!?]", "");
        if (DiscoveryQuestions.isUncertainAnswer(input) || Set.of("嗯", "嗯嗯", "呢", "下一个问题是什么", "下一个问题是啥", "下一个问题",
                "下一问", "继续提问", "请继续提问", "继续探索", "继续").contains(control)) {
            return new ProfileUpdatePolicy.Update(Map.of(), List.of(), false);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("currentUserProfile", ProfilePromptContext.currentContent(candidate));
        payload.put("currentEvidence", candidate.evidence());
        payload.put("currentUserInput", input);
        payload.put("pendingQuestion", pendingQuestion);
        payload.put("deferredAreas", deferredAreas);
        String content;
        try {
            content = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException failure) {
            throw new AiGatewayException("无法构造提取请求", failure);
        }
        String instruction = SYSTEM_INSTRUCTION + (turn ? "\n还必须输出布尔值 hasNewFacts；无新事实只返回 {\"hasNewFacts\":false}。" : "");
        String raw;
        try {
            raw = aiGateway.generate(DiscoveryContext.request(instruction, context, content, AiRequest.Thinking.DISABLED));
        } catch (AiGatewayException failure) {
            throw new DiscoveryAiException(DiscoveryAiException.Stage.PROFILE_EXTRACTION, failure);
        }
        try {
            UserProfileProposal proposal = turn ? parser.parseTurn(raw, 0).profile() : parser.parse(raw);
            ProfileUpdatePolicy.Update update = ProfileUpdatePolicy.evaluate(candidate, proposal, protectedSections);
            if (!update.sections().isEmpty() && update.evidenceClaims().isEmpty()) {
                // 缺少可用说明时记录本轮原文片段；来源仍是完整真实输入，不借用历史或制造解释。
                String quotation = input.strip();
                int quotationEnd = Math.min(quotation.length(), 2048);
                if (quotationEnd < quotation.length() && Character.isHighSurrogate(quotation.charAt(quotationEnd - 1))) {
                    quotationEnd--;
                }
                quotation = quotation.substring(0, quotationEnd);
                update = new ProfileUpdatePolicy.Update(update.sections(), List.of(quotation),
                        update.approvalRequired());
            }
            if (!update.approvalRequired()) {
                ProfileUpdatePolicy.apply(candidate, update, input);
            }
            return update;
        } catch (AiGatewayException | IllegalArgumentException failure) {
            throw new DiscoveryAiException(DiscoveryAiException.Stage.PROFILE_EXTRACTION,
                    raw == null || raw.isBlank() ? AiGatewayException.Category.EMPTY_OUTPUT
                            : AiGatewayException.Category.INVALID_OUTPUT, failure);
        }
    }
}
