package com.ayywl.delveforge.application.repositoryanalysis.scout;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiJsonObjectReader;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryFileReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把 AI 返回的原始文本严格解析为 {@link AiRepositoryScoutProposal}。
 *
 * <p>{@code AiGateway} 的契约是「返回未经解析的原始内容，由 Application 完成解析与校验」
 * （RULE-DOM-003），本类承担其中的解析部分。Provider 响应信封的解析不在这里——
 * 那属于 Infrastructure，本类的输入已经是模型返回的 content 本身。
 *
 * <h2>缺失不等于「没有」</h2>
 *
 * <p>与 Repository Analysis 的解析器同一个口径：字段缺失只说明模型没有回答那一部分，
 * 把它当成空值或默认值，系统就会把模型的沉默记成一条它从未做过的判断。因此这里一律拒绝，
 * 不做任何补全、不做任何规范化。
 *
 * <h2>只校验与模型约定的部分</h2>
 *
 * <p>本类检查的是**形状**：区域数量、标签非空、引用组非空、引用格式、区域内不重复。
 * 它不检查引用是否真的存在于本次 Map 中——那需要本次调用的输入，属于
 * {@link RepositoryScoutProposalResolver}。两步分开的理由与 Product Direction Discovery
 * 一致：解析只需要回答「模型有没有按要求作答」，引用校验回答「它指的是不是我们给过的东西」。
 *
 * <p>约定之外的字段被忽略：模型多给一个字段不会让整次侦察失败，系统只读取契约内的字段。
 * 这与其它两个解析器一致。
 *
 * <p>不满足契约意味着模型没有按要求作答，属于外部 AI 能力的失败，
 * 因此统一抛 {@link AiGatewayException}，与调用失败走同一条失败路径。
 */
public final class RepositoryScoutProposalParser {

    private static final String FIELD_FOCUS_AREAS = "focusAreas";
    private static final String FIELD_LABEL = "label";
    private static final String FIELD_FILE_REFS = "fileRefs";

    /**
     * 引用格式：{@code RF-} 加一个正整数。
     *
     * <p>这条格式知识没有放到 {@link RepositoryFileReference} 上：那个类型是**调用内**的
     * 短名句柄，它只需要保证「非空」，格式严格性属于接受模型输出的那个边界。
     * 前置零（{@code RF-01}）也拒绝——同一个编号有两种写法会让「是否重复」变得可疑。
     */
    private static final Pattern REFERENCE_PATTERN = Pattern.compile("RF-[1-9][0-9]*");

    private final AiJsonObjectReader reader;

    public RepositoryScoutProposalParser(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException(
                    "RepositoryScoutProposalParser 必须指定 objectMapper");
        }
        this.reader = new AiJsonObjectReader(objectMapper);
    }

    /**
     * @param rawAiOutput AI Gateway 返回的原始内容
     * @return 解析后的侦察提议，区域与引用的顺序与模型给出的一致
     * @throws AiGatewayException 内容为空、不是合法 json 对象，或结构与约定不符
     */
    public AiRepositoryScoutProposal parse(String rawAiOutput) {
        JsonNode focusAreas = reader.read(rawAiOutput).get(FIELD_FOCUS_AREAS);
        if (focusAreas == null || focusAreas.isNull()) {
            throw new AiGatewayException("AI 返回缺少字段 " + FIELD_FOCUS_AREAS);
        }
        if (!focusAreas.isArray()) {
            throw new AiGatewayException("AI 返回的 " + FIELD_FOCUS_AREAS + " 不是数组");
        }
        if (focusAreas.size() < AiRepositoryScoutProposal.MIN_FOCUS_AREAS
                || focusAreas.size() > AiRepositoryScoutProposal.MAX_FOCUS_AREAS) {
            throw new AiGatewayException(
                    "一次 Scout 需要 " + AiRepositoryScoutProposal.MIN_FOCUS_AREAS + " 到 "
                            + AiRepositoryScoutProposal.MAX_FOCUS_AREAS + " 个查看区域，实际为: "
                            + focusAreas.size());
        }

        List<AiRepositoryScoutFocusArea> areas = new ArrayList<>(focusAreas.size());
        for (JsonNode area : focusAreas) {
            areas.add(focusArea(area));
        }
        return new AiRepositoryScoutProposal(areas);
    }

    private static AiRepositoryScoutFocusArea focusArea(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new AiGatewayException(
                    "AI 返回的 " + FIELD_FOCUS_AREAS + " 含非对象元素");
        }
        return new AiRepositoryScoutFocusArea(
                requiredLabel(node), requiredFileRefs(node));
    }

    private static String requiredLabel(JsonNode node) {
        JsonNode value = node.get(FIELD_LABEL);
        if (value == null || value.isNull()) {
            throw new AiGatewayException("AI 返回的查看区域缺少字段 " + FIELD_LABEL);
        }
        if (!value.isTextual()) {
            throw new AiGatewayException("AI 返回的 " + FIELD_LABEL + " 不是字符串");
        }
        if (value.asText().isBlank()) {
            throw new AiGatewayException("AI 返回的 " + FIELD_LABEL + " 为空");
        }
        return value.asText();
    }

    /**
     * 读取一个必须出现、必须非空、且区域内不得重复的引用数组。
     *
     * <p>「区域内不重复」是契约的一部分：同一个编号在一个区域里出现两次，既不表达任何额外
     * 优先级，也不表达任何额外范围，只说明模型没有认真作答。静默去重会把这种情形藏起来，
     * 因此这里拒绝整次侦察，而不是替它清理。
     *
     * <p>跨区域重复是允许的：同一个文件确实可以同时属于几个不同的查看角度。
     */
    private static List<RepositoryFileReference> requiredFileRefs(JsonNode node) {
        JsonNode value = node.get(FIELD_FILE_REFS);
        if (value == null || value.isNull()) {
            throw new AiGatewayException("AI 返回的查看区域缺少字段 " + FIELD_FILE_REFS);
        }
        if (!value.isArray()) {
            throw new AiGatewayException("AI 返回的 " + FIELD_FILE_REFS + " 不是数组");
        }
        if (value.isEmpty()) {
            throw new AiGatewayException("AI 返回的 " + FIELD_FILE_REFS + " 为空");
        }

        List<RepositoryFileReference> references = new ArrayList<>(value.size());
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode element : value) {
            if (element == null || !element.isTextual() || element.asText().isBlank()) {
                throw new AiGatewayException("AI 返回的 " + FIELD_FILE_REFS + " 含空值");
            }
            String text = element.asText();
            if (!REFERENCE_PATTERN.matcher(text).matches()) {
                throw new AiGatewayException(
                        "AI 返回的文件引用格式不正确: " + text
                                + "（期望形如 RF-1 的编号，而不是路径）");
            }
            if (!seen.add(text)) {
                throw new AiGatewayException(
                        "AI 返回的同一个查看区域内重复引用了 " + text);
            }
            references.add(new RepositoryFileReference(text));
        }
        return List.copyOf(references);
    }
}
