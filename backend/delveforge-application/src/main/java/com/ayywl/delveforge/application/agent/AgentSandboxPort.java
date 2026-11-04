package com.ayywl.delveforge.application.agent;

import com.ayywl.delveforge.domain.agent.Candidate;

import java.util.List;
import java.util.function.BooleanSupplier;

/** 只接受已确认的 Step 与可信基线，正式副本只有只读快照能力。 */
public interface AgentSandboxPort {
    record Task(String runId, String planId, String stepId, String workingCopyId, String location,
            String originalLocation, String baseline, String goal, String scope, String changes, Integer targetJdk,
            ProductContext productContext, List<String> checkDirectories, String acceptanceContext) {
        public Task {
            checkDirectories = checkDirectories == null ? List.of() : List.copyOf(checkDirectories);
        }

        public Task(String runId, String planId, String stepId, String workingCopyId, String location,
                String originalLocation, String baseline, String goal, String scope, String changes, Integer targetJdk,
                ProductContext productContext, List<String> checkDirectories) {
            this(runId, planId, stepId, workingCopyId, location, originalLocation, baseline, goal, scope, changes,
                    targetJdk, productContext, checkDirectories, null);
        }

        public Task(String runId, String planId, String stepId, String workingCopyId, String location,
                String originalLocation, String baseline, String goal, String scope, String changes, Integer targetJdk,
                ProductContext productContext) {
            this(runId, planId, stepId, workingCopyId, location, originalLocation, baseline, goal, scope, changes,
                    targetJdk, productContext, List.of());
        }
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

    /** 系统在 PREPARING 中执行真实基线探针；结果不授予修改权限，也不是最终源码检查证据。 */
    record Preflight(String outcome, String diagnostic) {
        public boolean ready() {
            return "READY".equals(outcome) || "READY_BASELINE_FAILURE".equals(outcome);
        }
    }

    Preflight preflight(String runId, BooleanSupplier cancelled);

    Identity identity(String runId);

    /** 只读的当前工程检查声明，不构成依赖已准备或验证通过的证据。 */
    default String checkCapabilities(String runId, BooleanSupplier cancelled) {
        return null;
    }

    String tool(String runId, AgentProtocol.ToolCall call, BooleanSupplier cancelled);

    /** 完成声明前只读核对已知检查义务；返回可操作诊断，不能授予验证成功。旧实现仍由 finish 拒绝。 */
    default String completionFeedback(String runId, BooleanSupplier cancelled) {
        return null;
    }

    Candidate finish(String runId, BooleanSupplier cancelled);

    void stop(String runId);

    void cleanup(String runId);
}
