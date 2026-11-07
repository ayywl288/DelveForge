package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryContentPolicyTest {
    private final RepositoryContentPolicy policy = new RepositoryContentPolicy(new DeterministicRepositorySecretPolicy());

    private RepositoryContentPolicy.Capability classify(String path, String text) {
        return policy.classify(path, text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void unchangedRepositoryWrapperIsExecutionSourceWhileARealShellCredentialIsWithheld() throws Exception {
        byte[] wrapper = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("../../mvnw"));
        assertEquals(RepositoryContentPolicy.Kind.SOURCE, policy.classify("mvnw", wrapper).kind());
        assertEquals(RepositoryContentPolicy.Kind.PRESERVED_ONLY,
                classify("bootstrap", "#!/bin/sh\nPASSWORD='private-value'\n").kind());
    }

    @Test
    void preservationDisclosureExecutionAndMutationAreIndependent() {
        var safe = classify(".env.sample", "API_URL=https://example.com/api\nAPI_TOKEN=${API_TOKEN}\n");
        assertTrue(safe.preserved());
        assertTrue(safe.modelVisible());
        assertTrue(safe.executionAvailable());
        assertFalse(safe.mutable());
        var source = classify("src/Main.java", "class Main {}\n");
        assertTrue(source.modelVisible());
        assertTrue(source.executionAvailable());
        assertTrue(source.mutable());
    }

    @Test
    void publicApplicationTitlesRemainReadOnlyExecutionResourcesWithoutOpeningCredentialFields() {
        var publicEnvironment = classify("frontend/.env.development",
                "VITE_API_BASE_URL=/api\nVITE_APP_TITLE=校园实验室设备预约系统\n");
        assertEquals(RepositoryContentPolicy.Kind.PUBLIC_RESOURCE, publicEnvironment.kind());
        assertTrue(publicEnvironment.modelVisible());
        assertFalse(publicEnvironment.mutable());
        for (String content : List.of("VITE_APP_TITLE=" + "sk-" + "a".repeat(32),
                "PASSWORD_TITLE=opaque-value", "VITE_APP_TITLE=$(execute)",
                "VITE_APP_TITLE=" + "a".repeat(129))) {
            assertEquals(RepositoryContentPolicy.Kind.PRESERVED_ONLY, classify(".env.development", content).kind());
        }
    }

    @Test
    void ordinarySourceAndFixturesExecuteWithoutGrantingUnsanitizedModelVisibility() {
        var action = classify("src/Action.java",
                "record.setAction(temporary ? \"generated-password\" : \"provided-password\");");
        assertEquals(RepositoryContentPolicy.Kind.SOURCE, action.kind());
        assertTrue(action.executionAvailable());
        var credentials = classify("src/test/Auth.test.ts", "const login = {password: 'fixture-password'};");
        assertEquals(RepositoryContentPolicy.Kind.SOURCE, credentials.kind());
        assertTrue(credentials.executionAvailable());
        assertFalse(new DeterministicRepositorySecretPolicy().sanitizeSource("src/test/Auth.test.ts",
                "const login = {password: 'fixture-password'};").contains("fixture-password"));
        assertTrue(RepositoryContentPolicy.buildInput("src/test/Auth.test.ts"));
    }

    @Test
    void standardFetchModesAndCompletePlaceholderReferencesAreNotSecretValues() {
        for (String source : List.of("options.credentials = 'include';", "const options = { credentials: 'omit' };",
                "const options = { credentials: 'same-origin' };",
                "const headers = { Authorization: `MediaBrowser Token=\"${this.item.apikey}\"` };")) {
            assertEquals(RepositoryContentPolicy.Kind.SOURCE, classify("src/service.js", source).kind());
        }
        for (String source : List.of("options.password = 'include';", "options.credentials = 'private-value';",
                "const password = '${TOKEN}';", "const password = '${this.item.apikey}';",
                "const password = '${TOKEN:private}';", "const password = '${getSecret()}';")) {
            assertEquals(RepositoryContentPolicy.Kind.SOURCE, classify("src/service.js", source).kind());
        }
        assertEquals(RepositoryContentPolicy.Kind.SOURCE,
                classify("Auth.java", "String secret=\"${JWT_SECRET}\";").kind());
    }

    @Test
    void templateNamesNeverAuthorizeSecretsOrUnknownConfiguration() {
        for (String text : List.of("DB_PASSWORD=real-value\n", "API_TOKEN=" + "sk-" + "a".repeat(32),
                "API_URL=https://user:password@example.com\n", "API_URL=https://example.com?token=private\n",
                "API_URL=https://example.com/#private\n", "DB_PASSWORD=${DB_PASSWORD:private}\n",
                "API_URL=localhost\n", "API_URL=//example.com/path\n",
                "export KEY=$(execute)\n", "UNKNOWN=random-value\n", "PORT=1\nPORT=2\n",
                "# " + "sk-" + "b".repeat(32) + "\nPORT=8080\n")) {
            var capability = classify(".env.example", text);
            assertEquals(RepositoryContentPolicy.Kind.PRESERVED_ONLY, capability.kind());
            assertTrue(capability.preserved());
            assertFalse(capability.executionAvailable());
            assertFalse(capability.modelVisible());
            assertFalse(capability.mutable());
        }
        assertEquals(RepositoryContentPolicy.Kind.PRESERVED_ONLY,
                classify("application.properties", "db.password=private-value\n").kind());
        assertEquals(RepositoryContentPolicy.Kind.SOURCE,
                classify("src/Config.java", "class Config { String password = \"private-value\"; }").kind());
    }

    @Test
    void ordinarySourceExtensionsKeepAuthenticationFixturesWhileKnownCredentialsStayHostOnly() {
        for (String extension : List.of("java", "kt", "js", "mjs", "cjs", "ts", "tsx", "jsx", "vue", "html")) {
            String path = "src/Auth.test." + extension;
            assertEquals(RepositoryContentPolicy.Kind.SOURCE,
                    classify(path, "const paths = {password: '/users/password'}; token='fixture-token';").kind());
            for (String content : List.of("sk-" + "a".repeat(32), "ghp_" + "b".repeat(36),
                    "-----BEGIN PRIVATE KEY-----\nfixture-key\n-----END PRIVATE KEY-----",
                    "Authorization: Bearer opaque-value", "https://user:private@example.com")) {
                assertEquals(RepositoryContentPolicy.Kind.PRESERVED_ONLY, classify(path, content).kind());
            }
        }
        assertEquals(RepositoryContentPolicy.Kind.SOURCE,
                classify("src/Authorization.java", "// Bearer token validation\nString prefix = \"Bearer \";").kind());
        assertEquals(RepositoryContentPolicy.Kind.SOURCE,
                classify("src/Info.vue", "<h2>Basic information</h2>").kind());
    }

    @Test
    void unscannableSourceDoesNotGainExecutionEligibilityFromAnAbsentTextView() {
        assertEquals(RepositoryContentPolicy.Kind.PRESERVED_ONLY,
                classify("src/Large.java", " ".repeat(262144) + "sk-" + "a".repeat(32)).kind());
        assertEquals(RepositoryContentPolicy.Kind.PRESERVED_ONLY,
                policy.classify("src/Invalid.java", new byte[]{(byte) 0xff, (byte) 0xfe}).kind());
    }

    @Test
    void publicRegistrySettingsAreTypedDataAndNeverGrantHooksOrCredentials() {
        var safe = classify(".npmrc", "registry=https://registry.npmjs.org/\nignore-scripts=true\nshamefully-hoist=true\n");
        assertEquals(RepositoryContentPolicy.Kind.PUBLIC_RESOURCE, safe.kind());
        assertEquals("true", safe.settings().get("shamefully-hoist"));
        for (String text : List.of("//registry.npmjs.org/:_authToken=private", "registry=https://private.example",
                "registry=http://registry.npmjs.org", "ignore-scripts=false", "strict-ssl=false",
                "verify-store-integrity=false", "onload-script=evil.js", "registry=${PRIVATE_REGISTRY}",
                "shamefully-hoist=echo bad", "unknown=true", "registry=https://registry.npmjs.org/\nregistry=other")) {
            assertEquals(RepositoryContentPolicy.Kind.PRESERVED_ONLY, classify(".npmrc", text).kind());
        }
    }

    @Test
    void invalidCertificatesPrivateKeysAndOpaqueStoresStayOutOfExecution() {
        for (String path : List.of("test/server.key", "test/server.p12", "test/server.pfx", "test/cert.pem")) {
            assertEquals(RepositoryContentPolicy.Kind.PRESERVED_ONLY, classify(path,
                    "-----BEGIN PRIVATE KEY-----\nprivate-material\n-----END PRIVATE KEY-----\n").kind());
        }
        assertEquals(RepositoryContentPolicy.Kind.PRESERVED_ONLY,
                classify("test/cert.pem", "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n").kind());
    }

    @Test
    void genuinePublicCertificateIsExecutableReadOnlyButNeverModelMaterial() throws Exception {
        try (var input = getClass().getResourceAsStream("/admission/public-certificate.pem")) {
            var capability = policy.classify("test/server.pem", input.readAllBytes());
            assertEquals(RepositoryContentPolicy.Kind.PUBLIC_RESOURCE, capability.kind());
            assertTrue(capability.executionAvailable());
            assertTrue(capability.modelVisible());
            assertFalse(capability.mutable());
        }
    }
}
