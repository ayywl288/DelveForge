package com.ayywl.delveforge.infrastructure.workbench.model;

import com.ayywl.delveforge.application.agent.AgentContextBudget;
import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentModelFailure;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.workbench.WorkbenchModels;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 宿主的模型配置、能力与凭据解析。共享目录和连接池，模型选择由 Run 快照传入。 */
public final class SpringWorkbenchModels implements WorkbenchModels {
    private final WorkbenchCatalog metadata;
    private final ProviderCredentials credentials;
    private final JdbcTemplate jdbc;
    private final Choice initial;
    private final AgentContextBudget ceiling;
    private final ProviderChatModels models;
    private final OkHttpClient http;
    private final WorkbenchTokenEstimate counter = new WorkbenchTokenEstimate();
    private final int requestBytes;
    private final int responseBytes;

    public SpringWorkbenchModels(DataSource source, ObjectMapper json, Path root, Choice initial,
            AgentContextBudget ceiling, Duration timeout, int requestBytes, int responseBytes,
            Function<String, String> environment) {
        jdbc = new JdbcTemplate(source);
        credentials = new ProviderCredentials(jdbc, environment);
        metadata = new WorkbenchCatalog(root, json);
        this.initial = initial;
        this.ceiling = ceiling;
        this.requestBytes = requestBytes;
        this.responseBytes = responseBytes;
        models = new ProviderChatModels(timeout);
        http = ModelTransport.client(timeout);
    }

    @Override
    public Choice initialChoice() {
        return initial;
    }

    @Override
    public Catalog catalog() {
        metadata.refreshIfDue();
        var providers = new ArrayList<Provider>();
        var offered = new ArrayList<Model>();
        var refs = new java.util.HashMap<String, String>();
        for (var family : ProviderFamily.values()) {
            var ref = credentials.reference(family);
            refs.put(family.id(), ref.id());
            offered.addAll(metadata.models(family.catalogSource(ref.preset())));
            providers.add(new Provider(family.id(), family.label, family.endpoints.keySet().stream().sorted().toList(),
                    ref.preset(), ref.workspace(), !ref.id().isEmpty(), ref.saved()));
        }
        var checks = new java.util.HashMap<String, Map<String, Boolean>>();
        jdbc.query("SELECT credential_ref,model,effort,compatible FROM model_validation", rs -> {
            checks.computeIfAbsent(rs.getString(1) + "/" + rs.getString(2), k -> new java.util.HashMap<>())
                    .put(rs.getString(3), rs.getBoolean(4));
        });
        var entries = offered.stream().map(m -> {
            var checked = checks.getOrDefault(refs.get(m.provider()) + "/" + m.id(), Map.of());
            var rejected = m.efforts().stream().filter(e -> Boolean.FALSE.equals(checked.get(e))).toList();
            var live = m.efforts().stream().filter(e -> Boolean.TRUE.equals(checked.get(e))).toList();
            String status = rejected.size() == m.efforts().size() ? "incompatible" : live.isEmpty() ? "catalog" : "live";
            return new Model(m.provider(), m.id(), m.name(), m.efforts(), m.window(), m.input(), m.output(),
                    m.reasoning(), m.version(), status, live, rejected);
        }).sorted(Comparator.comparing(Model::provider).thenComparing(Model::name)).toList();
        return new Catalog(List.copyOf(providers), entries);
    }

    @Override
    public Frozen freeze(Choice choice) {
        var frozen = resolve(choice);
        if (jdbc.queryForObject("SELECT count(*) FROM model_validation WHERE credential_ref=? AND model=?"
                        + " AND effort=? AND compatible=0", Integer.class, frozen.credentialRef(),
                choice.model(), choice.effort()) > 0) {
            throw ProviderFamily.invalid("此组合未通过协议验证，请在提供方设置中重新验证");
        }
        return frozen;
    }

    private Frozen resolve(Choice choice) {
        if (choice == null || choice.model() == null || choice.effort() == null) {
            throw ProviderFamily.invalid("请选择模型");
        }
        var family = ProviderFamily.named(choice.provider());
        var ref = credentials.reference(family);
        var model = metadata.require(family.catalogSource(ref.preset()), choice.model());
        if (!model.efforts().contains(choice.effort())) {
            throw ProviderFamily.invalid("此模型不支持所选思考强度");
        }
        if (ref.id().isEmpty()) {
            throw ProviderFamily.invalid("请先配置该提供方的 API Key");
        }
        return new Frozen(choice, family.protocol, ref.preset(), ref.workspace(), ref.id(),
                budget(model), "spring-ai-2.1-RC2/" + model.version());
    }

    private AgentContextBudget budget(Model model) {
        int window = Math.min(ceiling.contextWindowTokens(), model.window());
        int output = Math.min(ceiling.responseTokens(), Math.min(model.output(), window / 2));
        window = (int) Math.min(window, (long) model.input() + output);
        return new AgentContextBudget(window, output, ceiling.compactionThreshold());
    }

    @Override
    public View preview(Choice choice) {
        var family = ProviderFamily.named(choice.provider());
        var ref = credentials.reference(family);
        return describe(new Frozen(choice, family.protocol, ref.preset(), ref.workspace(), "",
                budget(metadata.require(family.catalogSource(ref.preset()), choice.model())), ""));
    }

    @Override
    public View describe(Frozen frozen) {
        var family = ProviderFamily.named(frozen.choice().provider());
        var endpoint = java.net.URI.create(family.endpoint(frozen.preset(), frozen.workspace()));
        var identity = new com.ayywl.delveforge.application.agent.AgentModelPort.Identity(family.label,
                endpoint.getScheme() + "://" + endpoint.getAuthority(), frozen.choice().model(), frozen.choice().effort());
        return new View(identity, frozen.budget().contextWindowTokens(), frozen.budget().responseTokens());
    }

    @Override
    public Frozen legacyConfiguration() {
        var family = ProviderFamily.DEEPSEEK;
        var ref = credentials.environmentReference(family);
        return new Frozen(initial, family.protocol, ref.preset(), ref.workspace(), ref.id(), ceiling, "pre-V8");
    }

    @Override
    public Binding bind(Frozen frozen) {
        var family = ProviderFamily.named(frozen.choice().provider());
        if (!family.protocol.equals(frozen.protocol())) {
            throw ProviderFamily.invalid("历史任务的模型协议不可用");
        }
        return new Binding(new WorkbenchChatModel(frozen, family.endpoint(frozen.preset(), frozen.workspace()),
                () -> credentials.resolve(frozen), models, http, requestBytes, responseBytes), counter);
    }

    @Override
    public void save(String provider, String preset, String workspace, String key) {
        credentials.save(ProviderFamily.named(provider), preset, workspace == null ? "" : workspace, key);
    }

    @Override
    public void delete(String provider) {
        credentials.delete(ProviderFamily.named(provider));
    }

    @Override
    public Validation validate(Choice choice) {
        var config = resolve(choice);
        int output = Math.min(2048, config.budget().responseTokens());
        var test = new Frozen(choice, config.protocol(), config.preset(), config.workspace(), config.credentialRef(),
                new AgentContextBudget(config.budget().contextWindowTokens(), output,
                        config.budget().compactionThreshold()), config.capabilityVersion());
        var model = bind(test).model();
        var messages = new ArrayList<AgentProtocol.Message>();
        messages.add(AgentProtocol.Message.text("user", "请调用一次 Ping，无需参数。收到回执后仅回复 OK。"));
        var probe = List.of(new AgentProtocol.ToolSpec("Ping", "测试工具协议",
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"));
        try {
            var first = model.call(messages, probe);
            if (first.calls().size() != 1 || !"Ping".equals(first.calls().getFirst().name())
                    || !first.calls().getFirst().arguments().matches("\\s*\\{\\s*}\\s*")) {
                validation(config, false);
                throw ProviderFamily.invalid("已连接，但模型未返回要求的测试工具调用");
            }
            messages.add(first.assistantMessage(first.text()));
            messages.add(new AgentProtocol.Message("tool", "{\"ok\":true}", List.of(), first.calls().getFirst().id(), "Ping"));
            var last = model.call(messages, probe);
            if (!last.calls().isEmpty() || last.text().isBlank()) {
                validation(config, false);
                throw ProviderFamily.invalid("模型未完成测试工具回执的重放");
            }
            validation(config, true);
            return new Validation(first.tokens() == null || last.tokens() == null ? null : first.tokens() + last.tokens());
        } catch (AgentException e) {
            if (e instanceof AgentModelFailure f && f.reason() == AgentModelFailure.Reason.RESPONSE_TRUNCATED) {
                throw ProviderFamily.invalid("测试输出达到上限，未能验证完整回合");
            }
            if ("MODEL_NOT_AVAILABLE".equals(e.code()) || "MODEL_OPTIONS_REJECTED".equals(e.code())) {
                validation(config, false);
            }
            throw ProviderFamily.invalid(switch (e.code()) {
                case "MODEL_AUTHORIZATION" -> "请检查 API Key 及模型权限";
                case "MODEL_NOT_AVAILABLE" -> "当前账户或端点没有该模型";
                case "MODEL_BILLING" -> "请检查提供方账户余额与付款权限";
                case "MODEL_RATE_LIMIT" -> "已限流，请检查额度后重试";
                case "MODEL_OPTIONS_REJECTED" -> "提供方拒绝该模型或思考设置";
                case "MODEL_CREDENTIAL_CHANGED" -> "凭据已变更，请重新验证";
                default -> "模型未通过完整工具回合验证（" + e.code() + "）";
            });
        } finally {
            model.drainDiagnostics();
        }
    }

    private void validation(Frozen config, boolean compatible) {
        jdbc.update("INSERT INTO model_validation(credential_ref,model,effort,compatible) VALUES(?,?,?,?)"
                        + " ON CONFLICT(credential_ref,model,effort) DO UPDATE SET compatible=excluded.compatible",
                config.credentialRef(), config.choice().model(), config.choice().effort(), compatible);
    }
}
