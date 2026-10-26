package com.ayywl.delveforge.application.repositoryanalysis.secret;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 仅识别受支持源码中的运行时表达式；字符串值不凭熵或示例名称猜测是否安全。 */
final class SourceCredentialExpression {
    private static final Set<String> SOURCE_EXTENSIONS = Set.of("java", "js", "ts", "jsx", "tsx", "vue", "kt");
    private static final Pattern LOOKUP = Pattern.compile(
            "(?s).*(?:\\b(getItem|setItem|getHeader|getAttribute|getParameter|getProperty|getenv|get|path|getFirst))\\s*\\(\\s*$");

    private SourceCredentialExpression() {
    }

    static boolean runtime(String path, String source, int offset) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || !SOURCE_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT))) {
            return false;
        }
        StringBuilder expression = new StringBuilder();
        int depth = 0;
        for (int i = offset; i < source.length(); i++) {
            char value = source.charAt(i);
            if (depth == 0 && (value == ';' || value == ',' || value == '\n' || value == '\r' || value == '}')) {
                break;
            }
            if (value == '\'' || value == '"' || value == '`') {
                char quote = value;
                int begin = i;
                boolean closed = false;
                for (i++; i < source.length(); i++) {
                    char next = source.charAt(i);
                    if (next == 92) {
                        i++;
                    } else if (next == quote) {
                        closed = true;
                        break;
                    } else if (next == '\n' || next == '\r') {
                        return false;
                    }
                }
                if (!closed) {
                    return false;
                }
                String literal = source.substring(begin + 1, i);
                var lookup = LOOKUP.matcher(expression.subSequence(Math.max(0, expression.length() - 256), expression.length()));
                boolean lookupKey = lookup.matches();
                if (lookupKey && Set.of("get", "path", "getFirst").contains(lookup.group(1))) {
                    // 无类型信息的通用查找仅接受明确凭据字段名；任意字面量不推断为查找键。
                    lookupKey = literal.equalsIgnoreCase("Authorization") || literal.equalsIgnoreCase("jwt")
                            || literal.matches("(?i)[A-Za-z0-9_.-]*(?:credentials|credential|password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key)");
                }
                boolean empty = literal.isEmpty() && expression.toString().isBlank();
                boolean scheme = literal.equals("Bearer ") || literal.equals("Basic ");
                boolean template = quote == 96 && literal.matches(
                        "(?:Bearer |Basic )?[$][{][A-Za-z_$][A-Za-z0-9_$.]*(?:[(][)])?[}]");
                if (!lookupKey && !empty && !scheme && !template) {
                    return false;
                }
                expression.append("VALUE");
                continue;
            }
            if (value == '/' && i + 1 < source.length()
                    && (source.charAt(i + 1) == '/' || source.charAt(i + 1) == '*')) {
                return false;
            }
            if (value == '(' || value == '[' || value == '{') {
                depth++;
            } else if (value == ')' || value == ']' || value == '}') {
                if (--depth < 0) {
                    return false;
                }
            }
            expression.append(value);
        }
        String value = expression.toString().strip();
        // 未加引号的配置值仍受保护；源码只接受有明确标识符/调用或普通空初值的表达式。
        return depth == 0 && !value.isEmpty()
                && (value.equals("0") || value.matches("[A-Za-z_$][A-Za-z0-9_$\\s.()\\[\\],?:=!&|+<>*/%-]*")
                    || value.contains("=>") && value.matches("[A-Za-z0-9_$\\s.()\\[\\],?:=!&|+<>*/%{}-]*"));
    }
}
