package com.ayywl.delveforge.application.agent;

import java.util.List;

public interface AgentModelPort {
    AgentProtocol.Reply call(List<AgentProtocol.Message> history, List<AgentProtocol.ToolSpec> tools);
}
