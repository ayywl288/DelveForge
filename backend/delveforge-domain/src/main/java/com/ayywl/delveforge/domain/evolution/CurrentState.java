package com.ayywl.delveforge.domain.evolution;
import java.util.List;

/** Planning-relevant projection, rather than a copy of the complete Repository Profile.
 * Selected facts retain their original wording so the service can check grounding.
 */
public record CurrentState(String summary, List<String> capabilities,
                           List<String> modules, List<String> limitations) {
    public CurrentState {
        summary = PlanningContent.text(summary, "currentState.summary");
        capabilities = PlanningContent.section(capabilities, "currentState.capabilities", false);
        modules = PlanningContent.section(modules, "currentState.modules", false);
        limitations = PlanningContent.section(limitations, "currentState.limitations", false);
    }
}
