package com.ayywl.delveforge.application.quickstart;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.workspace.WorkspaceEntry;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QuickStartServiceTest {
    private final QuickStartStore store = mock(QuickStartStore.class);
    private final WorkspaceReadPort workspace = mock(WorkspaceReadPort.class);
    private final AiGateway ai = mock(AiGateway.class);
    private final QuickStartStore.Basis basis = new QuickStartStore.Basis("plan", "candidate", "a".repeat(40), "copy");
    private final QuickStartService service = new QuickStartService(store, workspace, ai,
            new DeterministicRepositorySecretPolicy());

    @Test
    void incompletePlanCannotRequestModelGeneration() {
        when(store.finalDelivery("plan")).thenReturn(Optional.empty());
        assertThrows(AgentException.class, () -> service.request("plan"));
        verifyNoInteractions(ai, workspace);
    }

    private void sources() {
        when(store.finalDelivery("plan")).thenReturn(Optional.of(basis));
        when(workspace.listEntries(any(), eq(basis.revision()), eq(""), eq(5)))
                .thenReturn(List.of(new WorkspaceEntry("README.md", false, 120),
                        new WorkspaceEntry("package.json", false, 40), new WorkspaceEntry(".env.local", false, 30)));
        when(workspace.readModelFile(any(), eq(basis.revision()), eq("README.md")))
                .thenReturn(Optional.of("Run npm run start; http://localhost:4567"));
        when(workspace.readModelFile(any(), eq(basis.revision()), eq("package.json")))
                .thenReturn(Optional.of("{\"scripts\":{\"start\":\"node server.js\"}}"));
    }

    @Test
    void guideUsesFinalCommittedFilesAndFailureIsIndependent() {
        sources();
        when(ai.generate(any())).thenAnswer(call -> {
            String input = call.getArgument(0).toString();
            assertTrue(input.contains("http://localhost:4567"));
            assertTrue(input.contains(basis.revision()));
            assertTrue(input.contains("未执行验证"));
            return "## 启动\n```sh\nnpm run start\n```";
        });
        service.generate(basis);
        verify(store).save(argThat(guide -> guide.status().equals("AVAILABLE")
                && guide.revision().equals(basis.revision()) && guide.markdown().contains("npm run start")));
        verify(workspace, never()).readModelFile(any(), anyString(), eq(".env.local"));
        verify(workspace, never()).headRevision(any(WorkspaceRef.class));
        doThrow(new RuntimeException("provider unavailable")).when(ai).generate(any());
        service.generate(basis);
        verify(store).save(argThat(guide -> guide.status().equals("UNAVAILABLE")
                && guide.reason().equals("QUICK_START_GENERATION_FAILED")));
    }

    @Test
    void revokedAuthorizationDuringGenerationDoesNotPublishGuide() {
        sources();
        when(ai.generate(any())).thenReturn("## 启动\nUse README");
        when(store.finalDelivery("plan")).thenReturn(Optional.of(basis), Optional.empty());
        service.generate(basis);
        verify(store).save(argThat(guide -> guide.status().equals("UNAVAILABLE") && guide.reason().equals("QUICK_START_STALE")));
    }
}
