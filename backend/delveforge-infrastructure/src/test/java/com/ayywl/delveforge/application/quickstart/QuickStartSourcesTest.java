package com.ayywl.delveforge.application.quickstart;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.workspace.WorkspaceEntry;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;
import com.ayywl.delveforge.infrastructure.workspace.GitTestRepositories;
import com.ayywl.delveforge.infrastructure.workspace.GitWorkspaceAdapter;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuickStartSourcesTest {
    private final DeterministicRepositorySecretPolicy secrets = new DeterministicRepositorySecretPolicy();

    @Test
    void realGitDeliverySourcesAreSanitizedBeforeSingleModelRequest() throws Exception {
        GitTestRepositories repositories = new GitTestRepositories();
        try {
            Map<String, String> files = new LinkedHashMap<>();
            files.put("README.md", "# 纸上书店\n[开发资料](document/本地使用.md)\n"
                    + "[不能读](.env)\n[外部](https://example.com/setup.md)\n"
                    + "DB_PASSWORD=readmeSecret123\nJWT_SECRET=jwtSecret123\n"
                    + "## 安装\ncd backend/bookstore\n./mvnw spring-boot:run\n");
            files.put("document/本地使用.md", "# 本地环境\nDocker 启动数据库\npassword: guideSecret123\n");
            files.put("document/开发指南.md", "# 开发\ndocker compose -f docker-compose.dev.yml up -d\n");
            files.put("backend/bookstore/pom.xml", "<project><name>纸上书店</name><java.version>17</java.version><password>xmlSecret123</password></project>");
            files.put("backend/bookstore/mvnw", "#!/bin/sh\nexec mvn \"$@\"\n");
            files.put("backend/bookstore/mvnw.cmd", "@echo off\nmvn %*\n");
            files.put("backend/bookstore/.mvn/wrapper/maven-wrapper.properties", "distributionUrl=https://repo.maven.apache.org/maven.zip\n");
            files.put("backend/bookstore/.env.example", "DB_USERNAME=real-user\nDB_PASSWORD=exampleSecret123\n"
                    + "JWT_SECRET=exampleJwt123\nSERVICE_URL=https://secret-host.invalid\n# hiddenToken123\n");
            files.put("docker-compose.dev.yml", "services:\n  mysql:\n    image: mysql:8\n    ports:\n"
                    + "      - '3307:3306'\n    environment:\n      MYSQL_ROOT_PASSWORD: composeSecret123\n"
                    + "  redis:\n    image: redis:7\n    ports:\n      - '6380:6379'\n");
            files.put("frontend/package.json", "{\"name\":\"bookstore\",\"scripts\":{\"dev\":\"vite\"}}");
            files.put("scripts/start.sh", "#!/bin/sh\nnpm run dev\n");
            files.put(".env", "PASSWORD=actualEnvSecret123");
            files.put(".env.local", "PASSWORD=localEnvSecret123");
            files.put("private.key", "privateKeySecret123");
            Path repository = repositories.createCommitted("quick-start", files);
            GitWorkspaceAdapter workspace = new GitWorkspaceAdapter();
            WorkspaceRef ref = new WorkspaceRef(repository.toString());
            String revision = workspace.headRevision(ref);
            GitTestRepositories.writeFile(repository, "README.md", "# 最新功能名\nMOVING_HEAD_ONLY\n");
            GitTestRepositories.runGit(repository, "add", "README.md");
            GitTestRepositories.commit(repository, "moving head");
            GitTestRepositories.writeFile(repository, "document/本地使用.md", "UNCOMMITTED_ONLY");
            QuickStartStore.Basis basis = new QuickStartStore.Basis("plan", "candidate", revision, repository.toString());
            QuickStartStore store = mock(QuickStartStore.class);
            when(store.finalDelivery("plan")).thenReturn(Optional.of(basis));
            AiGateway ai = mock(AiGateway.class);
            when(ai.generate(any())).thenAnswer(call -> {
                String request = call.getArgument(0).toString();
                for (String forbidden : List.of("readmeSecret123", "jwtSecret123", "guideSecret123", "real-user",
                        "exampleSecret123", "exampleJwt123", "secret-host.invalid", "hiddenToken123",
                        "xmlSecret123", "composeSecret123", "actualEnvSecret123", "localEnvSecret123", "privateKeySecret123",
                        "MOVING_HEAD_ONLY", "UNCOMMITTED_ONLY", "最新功能名")) {
                    assertFalse(request.contains(forbidden), forbidden);
                }
                for (String required : List.of("纸上书店", revision, "document/本地使用.md", "document/开发指南.md",
                        "backend/bookstore/mvnw", "maven-wrapper.properties", "DB_PASSWORD=<自行配置>",
                        "mysql:8", "3307:3306", "redis:7", "6380:6379", "package.json", "scripts/start.sh")) {
                    assertTrue(request.contains(required), required);
                }
                assertTrue(request.contains("不编造端口"));
                assertTrue(request.contains("只在末尾用一条说明汇总"));
                assertTrue(request.contains("推导"));
                return "## 前置条件\n- Java 17\n## 4. 启动应用\n```sh\ncd backend/bookstore\n./mvnw spring-boot:run\n```";
            });
            new QuickStartService(store, workspace, ai, secrets).generate(basis);
            verify(ai, times(1)).generate(any());
            verify(store).save(argThat(guide -> guide.status().equals("AVAILABLE")
                    && guide.markdown().startsWith("# 纸上书店\n") && guide.markdown().contains("## 生成依据")
                    && guide.markdown().contains(revision)));
            assertTrue(workspace.readModelFile(ref, revision, "README.md").isEmpty(), "通用整文件策略不变");
            assertTrue(workspace.readModelFile(ref, revision, "backend/bookstore/.env.example").isEmpty());
            assertTrue(secrets.excludes(".env.example"), "全局路径排除不变");
        } finally {
            repositories.deleteAll();
        }
    }

    @Test
    void boundsSelectionAndSkipsUnsafeFilesWithoutTruncatingSecretsFirst() {
        WorkspaceReadPort workspace = mock(WorkspaceReadPort.class);
        List<WorkspaceEntry> entries = new java.util.ArrayList<>(IntStream.range(0, 80)
                .mapToObj(index -> new WorkspaceEntry("module" + index + "/package.json", false, 15000)).toList());
        entries.addAll(List.of(new WorkspaceEntry("README.md", false, 65000),
                new WorkspaceEntry("docs/deployment.md", false, 100), new WorkspaceEntry("docs/setup.md", false, 100),
                new WorkspaceEntry("docs/install.md", false, 100), new WorkspaceEntry("huge/README.md", false, 100000)));
        when(workspace.listEntries(any(), anyString(), anyString(), anyInt())).thenReturn(entries);
        when(workspace.readFile(any(), anyString(), anyString())).thenAnswer(call -> switch ((String) call.getArgument(2)) {
            case "README.md" -> "# Safe\n" + "useful instructions\n".repeat(700) + "password=lateSecret123\n";
            case "docs/deployment.md" -> "password: |\n  multilineSecret123\n";
            case "docs/setup.md" -> "-----BEGIN PRIVATE KEY-----\nkeySecret123\n-----END PRIVATE KEY-----";
            case "docs/install.md" -> "mysql -prootSecret123";
            default -> "{\"name\":\"module\"}\n".repeat(700);
        });
        QuickStartSources.Material material = new QuickStartSources(workspace, secrets)
                .collect(new QuickStartStore.Basis("plan", "candidate", "a".repeat(40), "repo"));
        assertTrue(material.paths().size() <= 32);
        assertTrue(material.text().length() <= 48000);
        assertFalse(material.paths().contains("huge/README.md"));
        assertFalse(material.text().contains("Secret123"));
        assertEquals("Safe", material.projectName());
        assertTrue(material.text().contains("材料已截断"));
    }
    @Test
    void considersNativeManifestsAndFallsBackToOriginalRepositoryName() {
        WorkspaceReadPort workspace = mock(WorkspaceReadPort.class);
        List<String> paths = List.of("server/gradlew", "server/gradle/wrapper/gradle-wrapper.properties",
                "server/build.gradle.kts", "go.mod", "pyproject.toml", "Cargo.toml", "Makefile", "start.ps1");
        when(workspace.listEntries(any(), anyString(), anyString(), anyInt())).thenReturn(paths.stream()
                .map(path -> new WorkspaceEntry(path, false, 20)).toList());
        when(workspace.readFile(any(), anyString(), anyString())).thenReturn("native startup material");
        QuickStartSources.Material material = new QuickStartSources(workspace, secrets).collect(
                new QuickStartStore.Basis("plan", "candidate", "a".repeat(40), "copies/random-id", "assets/Bookstore"));
        assertEquals("Bookstore", material.projectName());
        assertTrue(material.paths().containsAll(paths));
    }

    @Test
    void skipsCredentialBlocksWhoseContinuationCannotBeSanitizedLineByLine() {
        for (String content : List.of("credentials: |\n  secretContinuation", "\"password\": >-\n  secretContinuation",
                "password:\n  secretContinuation", "password: \"first\n  secretContinuation\"")) {
            WorkspaceReadPort workspace = mock(WorkspaceReadPort.class);
            when(workspace.listEntries(any(), anyString(), anyString(), anyInt()))
                    .thenReturn(List.of(new WorkspaceEntry("application.yml", false, 200)));
            when(workspace.readFile(any(), anyString(), anyString())).thenReturn(content);
            QuickStartSources.Material material = new QuickStartSources(workspace, secrets)
                    .collect(new QuickStartStore.Basis("plan", "candidate", "a".repeat(40), "repo"));
            assertTrue(material.paths().isEmpty());
            assertFalse(material.text().contains("secretContinuation"));
        }
    }

}
