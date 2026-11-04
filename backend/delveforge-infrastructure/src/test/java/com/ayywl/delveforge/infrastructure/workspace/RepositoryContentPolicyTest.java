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
    void preservationDisclosureExecutionAndMutationAreIndependent() {
        var safe = classify(".env.sample", "API_URL=https://example.com/api\nAPI_TOKEN=${API_TOKEN}\n");
        assertTrue(safe.preserved());
        assertFalse(safe.modelVisible());
        assertTrue(safe.executionAvailable());
        assertFalse(safe.mutable());
        var source = classify("src/Main.java", "class Main {}\n");
        assertTrue(source.modelVisible());
        assertTrue(source.executionAvailable());
        assertTrue(source.mutable());
    }

    @Test
    void ternaryActionLabelsAreExecutableButCredentialFieldsInTestsRemainHostOnly() {
        var action = classify("src/Action.java",
                "record.setAction(temporary ? \"generated-password\" : \"provided-password\");");
        assertEquals(RepositoryContentPolicy.Kind.SOURCE, action.kind());
        assertTrue(action.executionAvailable());
        var credentials = classify("src/test/Auth.test.ts", "const login = {password: 'fixture-password'};");
        assertEquals(RepositoryContentPolicy.Kind.PRESERVED_ONLY, credentials.kind());
        assertFalse(credentials.executionAvailable());
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
                "const password = '${TOKEN:private}';", "const password = '${getSecret()}';",
                "options.credentials = 'include'; // " + "sk-" + "a".repeat(32))) {
            assertEquals(RepositoryContentPolicy.Kind.PRESERVED_ONLY, classify("src/service.js", source).kind());
        }
        assertEquals(RepositoryContentPolicy.Kind.PRESERVED_ONLY,
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
        assertEquals(RepositoryContentPolicy.Kind.PRESERVED_ONLY,
                classify("src/Config.java", "class Config { String password = \"private-value\"; }").kind());
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
            assertFalse(capability.modelVisible());
            assertFalse(capability.mutable());
        }
    }
}
