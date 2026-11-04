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
            "String password = \"case-\" + java.util.UUID.randomUUID().toString();",
            "const token = 'case-' + crypto.randomUUID();",
            "val password = \"case-\"   + UUID.randomUUID().toString()"
    })
    void runtimeGeneratedPasswordWithALiteralPrefixIsNotACompleteCredentialLiteral(String source) {
        assertEquals(List.of(), policy.mutationProtectedLines("src/test/java/AccountTest.java", source));
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
            "record.setAction(temporary ? \"generated-password\" : \"provided-password\");",
            "String action = generated ? \"generated-token\" : \"supplied-token\";",
            "record.setAction(generated?\"generated-password\":\"provided-password\");"
    })
    void ternaryStringOperandsAreNotCredentialPropertyAssignments(String source) {
        assertEquals(List.of(), policy.mutationProtectedLines("src/Action.java", source));
        assertEquals(source, policy.sanitizeSource("src/Action.java", source));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "const settings = temporary ? {password: 'embedded-value'} : {};",
            "// question? \"password\": \"embedded-value\"",
            "/* question? \"password\": \"embedded-value\" */",
            "\\u002f\\u002f question? \"password\": \"embedded-value\"",
            "String text = \"\"\"\nquestion? \"password\": \"embedded-value\"\n\"\"\";",
            "String action = generated ? \"password\" : \"ghp_0123456789abcdefghijklmnopqrstuvwx\";"
    })
    void ternaryRecognitionDoesNotExemptObjectPropertiesCommentsTextBlocksOrKnownTokens(String source) {
        assertFalse(policy.mutationProtectedLines("src/Action.java", source).isEmpty());
    }

    @Test
    void javaTernaryRecognitionDoesNotInterpretMarkupOrOtherLanguageCommentRules() {
        for (String path : List.of("src/Action.html", "src/Action.vue", "src/Action.kt", "src/Action.js")) {
            assertFalse(policy.mutationProtectedLines(path,
                    "<!-- question? \"password\": \"embedded-value\" -->").isEmpty());
        }
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
    void concatenationDoesNotHideKnownCredentialFormatsOrExemptConfiguration() {
        String token = "ghp_0123456789abcdefghijklmnopqrstuvwx";
        assertEquals(List.of(1), policy.mutationProtectedLines("Auth.java",
                "String password = \"" + token + "\" + runtimeValue;"));
        assertEquals(List.of(1), policy.mutationProtectedLines("application.properties",
                "password=case- + runtimeValue"));
    }

    @Test
    void knownCredentialFormatsRemainProtectedInCommentsAndStrings() {
        String key = "ghp_0123456789abcdefghijklmnopqrstuvwx";
        assertEquals(List.of(1), policy.mutationProtectedLines("Auth.java", "// token=" + key));
        assertEquals(List.of(1), policy.mutationProtectedLines("Auth.java", "String value=\"" + key + "\";"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "spring.datasource.password=${DB_PASSWORD}\n",
            "spring.datasource.password=${DB_PASSWORD:}\r\n",
            "security.password=${security.password}\n",
            "jwt.secret=\"${JWT_SECRET}\"\n",
            "password: '${DB_PASSWORD:}'\n"
    })
    void configurationReferencesDoNotContainCredentialValues(String configuration) {
        for (String path : List.of("src/main/resources/application.properties", "src/test/resources/application.yml")) {
            assertEquals(List.of(), policy.mutationProtectedLines(path, configuration), path);
            assertEquals(configuration, policy.sanitizeSource(path, configuration));
        }
        assertFalse(policy.sanitize(List.of(new RepositorySourceFile("application.properties", configuration)))
                .material().getFirst().content().equals(configuration));
    }

    @Test
    void emptyYamlScalarsAndStandaloneReferencesWithCommentsContainNoSecrets() {
        String configuration = "password: \"\"\nsecret: ''\n"
                + "apiKey: ${API_KEY} # injected at runtime\n";
        assertEquals(List.of(), policy.mutationProtectedLines("application.yaml", configuration));
    }

    @Test
    void referenceSyntaxDoesNotHideKnownTokenFormatsOrProtectPropertyQuoteBytesAsEmpty() {
        String token = "ghp_0123456789abcdefghijklmnopqrstuvwx";
        assertEquals(List.of(1), policy.mutationProtectedLines("application.yml", "password: ${" + token + "}\n"));
        assertEquals(List.of(1), policy.mutationProtectedLines("application.properties", "password=\"\"\n"));
        assertEquals(List.of(1), policy.mutationProtectedLines("sample.txt", "password=${DB_PASSWORD}\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "password=${DB_PASSWORD:embedded-private-value}\n",
            "secret=${JWT_SECRET}literal-suffix\n",
            "password=embedded-private-value\n",
            "password: \"embedded-private-value\"\n",
            "password=${OUTER:${INNER}}\n"
    })
    void literalDefaultsAndUnsupportedMixedValuesRemainProtected(String configuration) {
        for (String path : List.of("src/main/resources/application.properties", "src/test/resources/application.yml")) {
            assertEquals(List.of(1), policy.mutationProtectedLines(path, configuration), path);
        }
        assertEquals(List.of(1), policy.mutationProtectedLines("Auth.java", "String secret=\"${JWT_SECRET}\";"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<label for=\"password\">Password:</label>",
            "<script>const csrfToken = document.querySelector('meta').content;</script>",
            "<label>Password: <input type=\"password\" th:field=\"*{password}\"></label>"
    })
    void htmlFormLabelsAndRuntimeCodeDoNotContainCredentialValues(String source) {
        assertEquals(List.of(), policy.mutationProtectedLines("src/main/resources/templates/login.html", source));
        assertEquals(source, policy.sanitizeSource("src/main/resources/templates/login.html", source));
    }

    @Test
    void directHtmlCredentialAttributesAndKnownTokensRemainProtected() {
        assertEquals(List.of(1), policy.mutationProtectedLines("login.html",
                "<div data-password=\"synthetic-private-value\"></div>"));
        assertEquals(List.of(1), policy.mutationProtectedLines("login.html",
                "<script>const token = 'synthetic-private-value';</script>"));
        assertEquals(List.of(1), policy.mutationProtectedLines("login.html",
                "<!-- ghp_0123456789abcdefghijklmnopqrstuvwx -->"));
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
