package com.ayywl.delveforge.application.workbench;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentModelFailure;
import com.ayywl.delveforge.application.agent.AgentModelPort;
import com.ayywl.delveforge.application.agent.AgentProtocol;

import java.util.List;
import java.util.Optional;

/** 摘要与工具决策共用请求额度。只累加提供方实际报告的用量，包含能读取用量的失败响应。 */
final class ModelRequests {
    private ModelRequests() {}

    static Optional<AgentProtocol.Reply> call(
            Workbench store,
            AgentModelPort model,
            String run,
            List<AgentProtocol.Message> messages,
            List<AgentProtocol.ToolSpec> tools) {
        String failureDetail = null;
        try {
            var reply = model.call(messages, tools, () -> store.requestModel(run));
            Long reported = reply.usage() == null ? reply.tokens() : reply.usage().totalTokens();
            if (reported != null) {
                store.usage(run, reported);
            }
            return Optional.of(reply);
        } catch (AgentModelFailure failure) {
            failureDetail = describe(failure);
            if (failure.response() != null && failure.response().reportedTokens() != null) {
                store.usage(run, failure.response().reportedTokens());
            }
            throw failure;
        } catch (AgentException failure) {
            if ("REQUEST_NOT_ADMITTED".equals(failure.code())) {
                return Optional.empty();
            }
            throw failure;
        } finally {
            store.modelAttempts(run, tools.isEmpty(), model.drainDiagnostics(), failureDetail);
        }
    }

    /** 回复、工具参数及推理文本不得进入故障说明。只使用适配器归类后的枚举和数值。 */
    static String describe(AgentModelFailure failure) {
        String label = switch (failure.reason()) {
            case RESPONSE_TRUNCATED -> "模型输出被截断";
            case PROVIDER_CALL_FAILED -> "模型请求失败";
            default -> "模型回复未通过协议校验";
        };
        var response = failure.response();
        String facts = response == null ? "" : "，结束原因 " + response.finishReason()
                + "，单次输出上限 " + response.responseTokenLimit() + " tokens";
        return label + "（" + failure.reason() + facts + "）";
    }
}
