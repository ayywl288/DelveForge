package com.ayywl.delveforge.application.repositoryanalysis.workflow;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiMessage;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 同时扮演 Region Scout、File Scout 与最终分析的替身，供两条 Scout 路径的测试复用。
 *
 * <p>按请求内容分派：
 *
 * <pre>
 * 用户消息含 regionCatalog  → Region Scout，按 regionScript 选择目录前缀
 * 用户消息含 fileCatalog    → File Scout，按 fileScript 给出区域与路径
 * 其余                      → 最终分析，按顺序返回 analysisResponses
 * </pre>
 *
 * <p>两种 Scout 的响应都用**本次请求里真实存在的编号**拼出来。区域引用的作用域是每次调用
 * 新生成的，替身不猜它，而是从请求里读——因此真实的解析与引用校验都照常执行，
 * 替身只是提供了一个模型输出。
 *
 * <p>它在每次调用之前记下「当时已经读过几个文件」，用来证明 Scout 阶段不读源码。
 */
final class ScoutPathGateway implements AiGateway {

    /** 一个格式合法、但绝不属于本次目录的引用：用于构造「引用本次没有提供的编号」。 */
    private static final String UNKNOWN_REGION_REFERENCE =
            "RR-00000000000000000000000000000000-1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Supplier<Integer> readsSoFar;

    private final List<AiRequest> requests = new ArrayList<>();

    private final List<Integer> readsBeforeCall = new ArrayList<>();

    private final List<String> analysisResponses = new ArrayList<>();

    private List<List<String>> regionScript = List.of();

    private List<List<List<String>>> fileScript = List.of();

    private boolean unknownRegionReference;

    private boolean unknownRegionReferenceOnce;

    private int regionCalls;

    private int regionScriptIndex;

    private int fileCalls;

    ScoutPathGateway(Supplier<Integer> readsSoFar) {
        this.readsSoFar = readsSoFar;
    }

    /**
     * 编排接下来的调用。
     *
     * @param regionScript      每次 Region Scout 调用选择哪些目录前缀（按建议顺序）
     * @param fileScript        每次 File Scout 调用返回哪些区域、每个区域含哪些路径
     * @param analysisResponses 非 Scout 调用（最终分析）按顺序返回的内容
     */
    void respondAll(List<List<String>> regionScript,
                    List<List<List<String>>> fileScript,
                    String... analysisResponses) {
        this.regionScript = regionScript;
        this.fileScript = fileScript;
        this.analysisResponses.clear();
        this.analysisResponses.addAll(List.of(analysisResponses));
        this.unknownRegionReference = false;
        this.unknownRegionReferenceOnce = false;
        this.regionCalls = 0;
        this.regionScriptIndex = 0;
        this.fileCalls = 0;
        this.requests.clear();
        this.readsBeforeCall.clear();
    }

    /** 让下一次 Region Scout 回一个本次目录里不存在的编号。 */
    void answerRegionWithUnknownReference() {
        this.unknownRegionReference = true;
    }

    /**
     * 让**接下来那一次** Region Scout 回一个不存在的编号，之后恢复正常。
     *
     * <p>对应真实 Smoke 里的情形：第一次违反输出契约，重试时守约。
     */
    void answerRegionWithUnknownReferenceOnce() {
        this.unknownRegionReferenceOnce = true;
    }

    List<AiRequest> requests() {
        return List.copyOf(requests);
    }

    List<Integer> readsBeforeCall() {
        return List.copyOf(readsBeforeCall);
    }

    int regionCalls() {
        return regionCalls;
    }

    int fileCalls() {
        return fileCalls;
    }

    int scoutCalls() {
        return regionCalls + fileCalls;
    }

    @Override
    public String generate(AiRequest request) {
        readsBeforeCall.add(readsSoFar.get());
        requests.add(request);
        JsonNode payload = readPayload(request);

        if (payload.has("regionCatalog")) {
            return regionResponse(payload);
        }
        if (payload.has("fileCatalog")) {
            return fileResponse(payload);
        }
        if (analysisResponses.isEmpty()) {
            throw new AiGatewayException("替身没有更多预设的分析响应");
        }
        return analysisResponses.remove(0);
    }

    private String regionResponse(JsonNode payload) {
        regionCalls++;
        // 「一直引用不存在的编号」是**持续**的模型行为：它不消耗脚本，
        // 因此契约违反后的那一次重试同样会拿到不合法答案——这正是那些用例要断言的失败。
        // 「只错一次」则相反：它模拟真实 Smoke 里的情形——第一次不合法，重试守约。
        if (unknownRegionReference || unknownRegionReferenceOnce) {
            unknownRegionReferenceOnce = false;
            return "{\"regionRefs\":[\"" + UNKNOWN_REGION_REFERENCE + "\"]}";
        }
        // 脚本按**被消费的次数**推进，不按调用序号：一次不消耗脚本的契约违反
        // （上面那条）不该把后面的调用错位地推到下一个脚本项。
        if (regionScriptIndex >= regionScript.size()) {
            throw new AssertionError("第 " + (regionScriptIndex + 1)
                    + " 次 Region Scout 调用超出脚本（共 " + regionScript.size() + " 次）");
        }
        List<String> selected = regionScript.get(regionScriptIndex++);

        Map<String, String> byPathPrefix = new LinkedHashMap<>();
        for (JsonNode region : payload.get("regionCatalog")) {
            byPathPrefix.put(region.get("pathPrefix").asText(),
                    region.get("reference").asText());
        }
        StringBuilder json = new StringBuilder("{\"regionRefs\":[");
        for (int index = 0; index < selected.size(); index++) {
            String reference = byPathPrefix.get(selected.get(index));
            if (reference == null) {
                throw new AssertionError("脚本里的目录前缀不在本次 Region 目录中: "
                        + selected.get(index));
            }
            json.append(index == 0 ? "" : ",").append('"').append(reference).append('"');
        }
        return json.append("]}").toString();
    }

    private String fileResponse(JsonNode payload) {
        if (fileCalls >= fileScript.size()) {
            throw new AssertionError("第 " + (fileCalls + 1)
                    + " 次 File Scout 调用超出脚本（共 " + fileScript.size() + " 次）");
        }
        List<List<String>> areas = fileScript.get(fileCalls++);

        Map<String, String> byPath = new LinkedHashMap<>();
        for (JsonNode entry : payload.get("fileCatalog")) {
            byPath.put(entry.get("path").asText(), entry.get("reference").asText());
        }
        StringBuilder json = new StringBuilder("{\"focusAreas\":[");
        int area = 0;
        for (List<String> paths : areas) {
            json.append(area == 0 ? "" : ",").append("{\"label\":\"区域").append(++area)
                    .append("\",\"fileRefs\":[");
            for (int index = 0; index < paths.size(); index++) {
                String reference = byPath.get(paths.get(index));
                if (reference == null) {
                    throw new AssertionError("脚本里的路径不在本次分支目录中: " + paths.get(index));
                }
                json.append(index == 0 ? "" : ",").append('"').append(reference).append('"');
            }
            json.append("]}");
        }
        return json.append("]}").toString();
    }

    private static JsonNode readPayload(AiRequest request) {
        try {
            List<AiMessage> messages = request.messages();
            return MAPPER.readTree(messages.get(messages.size() - 1).content());
        } catch (Exception exception) {
            throw new AssertionError("无法从请求里读出载荷", exception);
        }
    }
}
