package com.ayywl.delveforge.application.workbench;

import com.ayywl.delveforge.application.agent.AgentContextBudget;
import com.ayywl.delveforge.application.agent.AgentModelPort;
import com.ayywl.delveforge.application.agent.AgentTokenCounter;

import java.util.List;

/** 工作台模型端口。选择由对话持有，端点、能力和凭据引用在入队时冻结。 */
public interface WorkbenchModels {
    record Choice(String provider, String model, String effort) {}

    record Frozen(Choice choice, String protocol, String preset, String workspace, String credentialRef,
            AgentContextBudget budget, String capabilityVersion) {}

    record Model(String provider, String id, String name, List<String> efforts, int window, int input, int output,
            boolean reasoning, String version, String compatibility, List<String> verifiedEfforts, List<String> rejectedEfforts) {}

    record Provider(String id, String name, List<String> presets, String preset, String workspace,
            boolean configured, boolean saved) {}

    record Catalog(List<Provider> providers, List<Model> models) {}

    record View(AgentModelPort.Identity model, int contextWindowTokens, int responseTokens) {}

    View preview(Choice choice);

    View describe(Frozen frozen);

    record Binding(AgentModelPort model, AgentTokenCounter counter) {}

    Catalog catalog();

    Choice initialChoice();

    Frozen freeze(Choice choice);

    Frozen legacyConfiguration();

    Binding bind(Frozen frozen);

    void save(String provider, String preset, String workspace, String key);

    void delete(String provider);

    record Connection(long latencyMs) {}

    Connection testConnection(String provider, String preset, String workspace, String key);

    record Validation(Long totalTokens) {}

    Validation validate(Choice choice);
}
