package com.ayywl.delveforge.infrastructure.workbench.model;

import com.ayywl.delveforge.application.workbench.WorkbenchRejection;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 只接受厂商官方预设；工作空间 ID 不能提供主机、端口或路径。 */
enum ProviderFamily {
    OPENAI("OpenAI", "responses", "OPENAI_API_KEY", "openai", Map.of("global", "https://api.openai.com/v1")),
    ANTHROPIC("Anthropic", "anthropic", "ANTHROPIC_API_KEY", "anthropic", Map.of("global", "https://api.anthropic.com")),
    GOOGLE("Google Gemini", "genai", "GEMINI_API_KEY", "google", Map.of("global", "https://generativelanguage.googleapis.com")),
    DEEPSEEK("DeepSeek", "deepseek", "DEEPSEEK_API_KEY", "deepseek", Map.of("global", "https://api.deepseek.com")),
    ZAI("智谱 GLM / Z.ai", "chat-completions", "ZAI_API_KEY", "zai", Map.of(
            "global", "https://api.z.ai/api/paas/v4", "global-coding", "https://api.z.ai/api/coding/paas/v4",
            "china", "https://open.bigmodel.cn/api/paas/v4", "china-coding", "https://open.bigmodel.cn/api/coding/paas/v4")),
    QWEN("阿里云百炼 / Qwen", "chat-completions", "DASHSCOPE_API_KEY", "alibaba", Map.of(
            "singapore", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
            "beijing", "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "singapore-workspace", "https://%s.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1",
            "beijing-workspace", "https://%s.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
            "virginia-workspace", "https://%s.us-east-1.maas.aliyuncs.com/compatible-mode/v1",
            "hongkong-workspace", "https://%s.cn-hongkong.maas.aliyuncs.com/compatible-mode/v1",
            "tokyo-workspace", "https://%s.ap-northeast-1.maas.aliyuncs.com/compatible-mode/v1")),
    KIMI("Moonshot Kimi", "chat-completions", "MOONSHOT_API_KEY", "moonshotai", Map.of(
            "global", "https://api.moonshot.ai/v1", "china", "https://api.moonshot.cn/v1")),
    OPENROUTER("OpenRouter", "chat-completions", "OPENROUTER_API_KEY", "openrouter",
            Map.of("global", "https://openrouter.ai/api/v1"));

    final String label;
    final String protocol;
    final String environment;
    final String catalogId;
    final Map<String, String> endpoints;

    ProviderFamily(String label, String protocol, String environment, String catalogId, Map<String, String> endpoints) {
        this.label = label;
        this.protocol = protocol;
        this.environment = environment;
        this.catalogId = catalogId;
        this.endpoints = endpoints;
    }

    String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    String catalogSource(String preset) {
        if (this == ZAI) {
            return switch (preset) {
                case "china" -> "zhipuai";
                case "china-coding" -> "zhipuai-coding-plan";
                case "global-coding" -> "zai-coding-plan";
                default -> catalogId;
            };
        }
        if (this == QWEN && preset.startsWith("beijing")) {
            return "alibaba-cn";
        }
        if (this == KIMI && "china".equals(preset)) {
            return "moonshotai-cn";
        }
        return catalogId;
    }

    String defaultPreset() {
        return this == QWEN ? "singapore" : "global";
    }

    String endpoint(String preset, String workspace) {
        String url = preset == null ? null : endpoints.get(preset);
        if (url == null) {
            throw invalid("不支持的提供方端点");
        }
        if (url.contains("%s")) {
            if (workspace == null || !workspace.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
                throw invalid("请输入有效的百炼工作空间 ID");
            }
            return url.formatted(workspace);
        }
        if (workspace != null && !workspace.isEmpty()) {
            throw invalid("此端点无需工作空间 ID");
        }
        return url;
    }

    static ProviderFamily named(String id) {
        for (var family : values()) {
            if (family.id().equals(id)) {
                return family;
            }
        }
        throw invalid("未知模型提供方");
    }

    static WorkbenchRejection invalid(String message) {
        return new WorkbenchRejection(WorkbenchRejection.Kind.INVALID, message);
    }

    /** 能力未知的模型只提供厂商默认；额外强度须有对应模型的协议依据。 */
    List<String> efforts(String model, boolean reasoning) {
        if (!reasoning) {
            return List.of("default");
        }
        return switch (this) {
            case OPENAI -> switch (model) {
                case "gpt-6-astra", "gpt-6.1-sol" -> List.of("default", "low", "medium", "high", "xhigh", "max");
                case "gpt-6-sol", "gpt-6-luna", "gpt-6.1-terra" ->
                        List.of("default", "off", "low", "medium", "high", "xhigh", "max");
                default -> model.matches("gpt-5\\.4(-mini|-nano)?(-[0-9-]+)?")
                    ? List.of("default", "off", "low", "medium", "high", "xhigh") : List.of("default");
            };
            case ANTHROPIC -> switch (model) {
                case "claude-opus-4-6", "claude-sonnet-4-6" -> List.of("default", "off", "low", "medium", "high", "max");
                case "claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-2" ->
                        List.of("default", "low", "medium", "high", "xhigh", "max");
                default -> List.of("default");
            };
            case GOOGLE -> switch (model) {
                case "gemini-3-flash-preview", "gemini-3.1-flash-lite", "gemini-3.5-flash-lite",
                        "gemini-3.6-flash" -> List.of("default", "minimal", "low", "medium", "high");
                case "gemini-3.1-pro-preview", "gemini-3.8-flash" -> List.of("default", "low", "medium", "high");
                case "gemini-2.5-flash" -> List.of("default", "off");
                default -> List.of("default");
            };
            case DEEPSEEK -> List.of("deepseek-flash", "deepseek-v4-pro", "deepseek-v4-flash").contains(model)
                    ? List.of("default", "off", "low", "high", "max") : List.of("default");
            case ZAI -> List.of("glm-4.7", "glm-4.7-flash", "glm-5", "glm-5.1", "glm-5.2", "glm-5-turbo").contains(model)
                    ? List.of("default", "off", "on") : List.of("default");
            case QWEN -> model.matches("qwen3\\.[5-8]-(plus|flash)(-[0-9-]+)?")
                    ? List.of("default", "off", "on") : List.of("default");
            case KIMI -> switch (model) {
                case "kimi-k3", "kimi-k3.1-pro", "kimi-k3.1-pro-highspeed" -> List.of("default", "low", "high", "max");
                case "kimi-k2.5", "kimi-k2.6" -> List.of("default", "off", "on");
                default -> List.of("default");
            };
            case OPENROUTER -> model.equals("openai/gpt-5.4")
                    ? List.of("default", "off", "low", "medium", "high", "xhigh") : List.of("default");
        };
    }
}
