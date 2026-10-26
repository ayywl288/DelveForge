package com.ayywl.delveforge.application.repositoryanalysis.secret;

import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositorySourceFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialMutationPolicyTest {
    private final RepositorySecretPolicy policy = new DeterministicRepositorySecretPolicy();

    @ParameterizedTest
    @ValueSource(strings = {
            "const token = localStorage.getItem('token');",
            "const token = this.getToken();",
            "const token = response.get('access_token').asText();",
            "const token = response.path('token').asText();",
            "const token = headers.getFirst('Authorization');",
            "getToken: () => localStorage.getItem('token'),",
            "setToken: token => localStorage.setItem('token', token),",
            "String token = service.generateToken(user);",
            "String password = java.util.UUID.randomUUID().toString();",
            "String secret = System.getenv(\"DB_SECRET\");",
            "const accessToken = response.token;",
            "int token = 0;",
            "let token = null;",
            "let token = '';",
            "const token = 'Bearer ' + runtimeValue;",
            "const token = `Bearer ${runtimeValue}`;"
    })
    void ordinarySourceExpressionsRemainEditableWhileOutboundPolicyRemainsConservative(String source) {
        assertEquals(List.of(), policy.mutationProtectedLines("src/Auth.js", source));
        assertFalse(policy.sanitize(List.of(new RepositorySourceFile("src/Auth.js", source)))
                .material().getFirst().content().equals(source));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "const token = 'embedded-private-value';",
            "String secret = \"placeholder\";",
            "const token = `embedded-private-value`;",
            "String key = \"ghp_0123456789abcdefghijklmnopqrstuvwx\";"
    })
    void directCredentialLiteralsAndKnownFormatsStayProtected(String source) {
        assertFalse(policy.mutationProtectedLines("src/Auth.java", source).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "// token=example-without-a-known-prefix",
            "String sample = \"token=example-without-a-known-prefix\";",
            "const token = cached || 'unmarked-value';",
            "const getToken = () => 'unmarked-value';",
            "String password = createUser(\"unmarked-value\");",
            "const token = `Bearer ${runtimeValue}suffix`;"
    })
    void mvpDoesNotClaimToClassifyUnmarkedValuesOrParseSourceSemantics(String source) {
        assertEquals(List.of(), policy.mutationProtectedLines("Auth.java", source));
    }

    @Test
    void knownCredentialFormatsRemainProtectedInCommentsAndStrings() {
        String key = "ghp_0123456789abcdefghijklmnopqrstuvwx";
        assertEquals(List.of(1), policy.mutationProtectedLines("Auth.java", "// token=" + key));
        assertEquals(List.of(1), policy.mutationProtectedLines("Auth.java", "String value=\"" + key + "\";"));
    }

    @Test
    void configurationDoesNotAcquireCodeExpressionExemptions() {
        assertEquals(List.of(2), policy.mutationProtectedLines("config.yml", "ordinary: ok\npassword: runtimeValue\n"));
        assertEquals(List.of(2), policy.mutationProtectedLines("Config.java", "ordinary();\nString password=\"value\";\n"));
        String block = "ordinary\n-----BEGIN PRIVATE KEY-----\nsynthetic-private-material\n-----END PRIVATE KEY-----\n";
        assertEquals(List.of(2, 3, 4), policy.mutationProtectedLines("Config.java", block));
        assertTrue(policy.excludes("keys/private.pem"));
        assertTrue(policy.excludes(".env"));
    }
}
