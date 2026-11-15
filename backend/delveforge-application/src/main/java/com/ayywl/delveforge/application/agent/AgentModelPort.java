package com.ayywl.delveforge.application.agent;

import java.util.List;
import java.util.function.BooleanSupplier;

public interface AgentModelPort {
    AgentProtocol.Reply call(List<AgentProtocol.Message> history, List<AgentProtocol.ToolSpec> tools);

    /** 初次请求由调用方准入；带有网络重试的适配器在每次额外发送前重新取得额度。 */
    default AgentProtocol.Reply call(List<AgentProtocol.Message> history, List<AgentProtocol.ToolSpec> tools,
            BooleanSupplier admitRetry) {
        return call(history, tools);
    }

    default List<java.util.Map<String, Object>> drainDiagnostics() {
        return List.of();
    }
}
