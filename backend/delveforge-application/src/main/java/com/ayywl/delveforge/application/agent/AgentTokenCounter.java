package com.ayywl.delveforge.application.agent;

import java.util.List;

/** 估计本次完整模型输入的 token 数，不代表提供者账单。 */
@FunctionalInterface
public interface AgentTokenCounter {
    long countPromptTokens(List<AgentProtocol.Message> messages, List<AgentProtocol.ToolSpec> tools);
}
