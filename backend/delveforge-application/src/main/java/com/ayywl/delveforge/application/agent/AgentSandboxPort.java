package com.ayywl.delveforge.application.agent;

import com.ayywl.delveforge.domain.agent.Candidate;

import java.util.function.BooleanSupplier;

/** 只接受已确认的 Step 与可信基线，正式副本只有只读快照能力。 */
public interface AgentSandboxPort {
    record Task(String runId, String planId, String stepId, String workingCopyId, String location,
            String originalLocation, String baseline, String goal, String scope, String changes, Integer targetJdk,
            ProductContext productContext) {
        public Task(String runId, String planId, String stepId, String workingCopyId, String location,
                String originalLocation, String baseline, String goal, String scope, String changes, Integer targetJdk) {
            this(runId, planId, stepId, workingCopyId, location, originalLocation, baseline, goal, scope, changes,
                    targetJdk, null);
        }
    }

    /** 只解释已保存的产品意图，不增加当前 Step 的授权。 */
    record ProductContext(String problem, String targetProduct, String differentiation) {
    }

    record Identity(String containerId, String imageId, int targetJdk, long memoryBytes, long nanoCpus,
            long pidsLimit, String networkMode, boolean readOnlyRoot) {
    }

    void prepare(Task task, AgentProtocol.Limits limits, BooleanSupplier cancelled);

    Identity identity(String runId);

    String tool(String runId, AgentProtocol.ToolCall call, BooleanSupplier cancelled);

    Candidate finish(String runId, BooleanSupplier cancelled);

    void stop(String runId);

    void cleanup(String runId);
}
