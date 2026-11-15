package com.ayywl.delveforge.application.workbench;

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
        if (!store.requestModel(run)) {
            return Optional.empty();
        }
        try {
            var reply = model.call(messages, tools, () -> store.requestModel(run));
            Long reported = reply.usage() == null ? reply.tokens() : reply.usage().totalTokens();
            if (reported != null) {
                store.usage(run, reported);
            }
            return Optional.of(reply);
        } catch (AgentModelFailure failure) {
            if (failure.response() != null && failure.response().reportedTokens() != null) {
                store.usage(run, failure.response().reportedTokens());
            }
            throw failure;
        }
    }
}
