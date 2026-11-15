package com.ayywl.delveforge.application.agent;

import java.util.List;
import java.util.function.BooleanSupplier;

public interface AgentModelPort {
    /** 当前适配器实际使用的公开配置；服务端部署的权重不在本地可验证范围。 */
    record Identity(String adapter, String endpoint, String requestedModel, String thinking) {}

    default Identity identity() {
        return new Identity("未提供", "未提供", "未提供", "未提供");
    }

    /** 当前工具决策协议能否原样重放这条消息，例如切换思考模式前保存的助手回复。 */
    default boolean canReplayWithTools(AgentProtocol.Message message) {
        return true;
    }

    AgentProtocol.Reply call(List<AgentProtocol.Message> history, List<AgentProtocol.ToolSpec> tools);

    /** 在每次实际发送前取得额度，包括首次发送和重试。 */
    default AgentProtocol.Reply call(List<AgentProtocol.Message> history, List<AgentProtocol.ToolSpec> tools,
            BooleanSupplier admitRequest) {
        if (!admitRequest.getAsBoolean()) {
            throw new AgentException("REQUEST_NOT_ADMITTED", "模型请求未获准入");
        }
        return call(history, tools);
    }

    default List<java.util.Map<String, Object>> drainDiagnostics() {
        return List.of();
    }
}
