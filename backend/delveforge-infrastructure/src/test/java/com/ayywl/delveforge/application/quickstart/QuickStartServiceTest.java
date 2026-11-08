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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    void guideRequestSeparatesDocumentedDatabasePathsAndSuppressesIrrelevantNames() {
        for (String instructions : List.of("首选宿主机 MySQL，localhost:3306；Compose 只启动其他基础服务。",
                "推荐 Docker Compose 启动 MySQL，无需另装宿主机 MySQL。",
                "MySQL 需自行安装在宿主机。启动其他服务：docker compose -f docker-compose.dev.yml up -d")) {
            when(store.finalDelivery("plan")).thenReturn(Optional.of(basis));
            when(workspace.listEntries(any(), eq(basis.revision()), eq(""), eq(8)))
                    .thenReturn(List.of(new WorkspaceEntry("README.md", false, 200),
                            new WorkspaceEntry("docker-compose.dev.yml", false, 200),
                            new WorkspaceEntry("application.properties", false, 100)));
            when(workspace.readFile(any(), eq(basis.revision()), eq("README.md")))
                    .thenReturn("# 纸上书店\n" + instructions);
            when(workspace.readFile(any(), eq(basis.revision()), eq("docker-compose.dev.yml")))
                    .thenReturn("name: mybookstore-dev\nservices:\n  mysql:\n    image: mysql:8\n    ports:\n      - '3306:3306'\n");
            when(workspace.readFile(any(), eq(basis.revision()), eq("application.properties")))
                    .thenReturn("spring.datasource.url=${DB_URL}");
            doAnswer(call -> {
                String request = call.getArgument(0).toString();
                assertTrue(request.contains(instructions));
                for (String policy : List.of("除非影响实际启动参数，否则不解释这些命名差异", "不能要求二者同时启动",
                        "分列互斥路径", "端口冲突和二选一", "未知映射不猜测端口", "不编造端口")) {
                    assertTrue(request.contains(policy), policy);
                }
                assertTrue(request.contains("3306:3306"));
                assertTrue(request.contains("spring.datasource.url=${DB_URL}"));
                assertFalse(request.contains("名称冲突时说明差异"));
                return "## 启动基础服务\n按文档选择数据库启动路径。";
            }).when(ai).generate(any());
            service.generate(basis);
        }
        verify(ai, times(3)).generate(any());
    }

    @Test
    void incompletePlanCannotRequestModelGeneration() {
        when(store.finalDelivery("plan")).thenReturn(Optional.empty());
        assertThrows(AgentException.class, () -> service.request("plan"));
        verifyNoInteractions(ai, workspace);
    }

    private void sources() {
        when(store.finalDelivery("plan")).thenReturn(Optional.of(basis));
        when(workspace.listEntries(any(), eq(basis.revision()), eq(""), eq(8)))
                .thenReturn(List.of(new WorkspaceEntry("README.md", false, 120),
                        new WorkspaceEntry("package.json", false, 40), new WorkspaceEntry(".env.local", false, 30)));
        when(workspace.readFile(any(), eq(basis.revision()), eq("README.md")))
                .thenReturn("Run npm run start; http://localhost:4567");
        when(workspace.readFile(any(), eq(basis.revision()), eq("package.json")))
                .thenReturn("{\"scripts\":{\"start\":\"node server.js\"}}");
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
        verify(workspace, never()).readFile(any(), anyString(), eq(".env.local"));
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

    @Test
    void unavailableGuideCanBeRegeneratedWithoutChangingDelivery() {
        sources();
        when(ai.generate(any())).thenThrow(new RuntimeException("provider unavailable"))
                .thenReturn("## 启动\n```sh\n# 启动应用\nnpm run start\n```\n说明：仓库未说明数据库配置。");
        service.generate(basis);
        verify(store).save(argThat(guide -> guide.status().equals("UNAVAILABLE")));
        clearInvocations(store);
        service.generate(basis);
        verify(store).save(argThat(guide -> guide.status().equals("AVAILABLE")
                && guide.markdown().contains("# 启动应用\nnpm run start")
                && guide.markdown().contains("仓库未说明数据库配置")
                && guide.candidateId().equals(basis.candidateId())));
    }

    @Test
    void emptyMaterialsAndOversizedResponseFailIndependently() {
        when(store.finalDelivery("plan")).thenReturn(Optional.of(basis));
        when(workspace.listEntries(any(), eq(basis.revision()), eq(""), eq(8))).thenReturn(List.of());
        service.generate(basis);
        verifyNoInteractions(ai);
        verify(store).save(argThat(guide -> "QUICK_START_NO_SOURCES".equals(guide.reason())));
        sources();
        when(ai.generate(any())).thenReturn("x".repeat(6001));
        service.generate(basis);
        verify(store).save(argThat(guide -> "QUICK_START_INVALID".equals(guide.reason())));
    }
}
