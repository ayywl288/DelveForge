package com.ayywl.delveforge.infrastructure.workbench;

import com.ayywl.delveforge.application.workbench.ConversationAgent;
import com.ayywl.delveforge.application.workbench.Workbench;
import com.ayywl.delveforge.application.workbench.WorkbenchRejection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Windows 主动经 mTLS 拉取窄查询；开发网络无需直连后端 API。 */
public final class GatewayConnection implements AutoCloseable {
    private final Path root;
    private final DockerDevelopmentEnvironment environment;
    private final Workbench store;
    private final ObjectMapper json;
    private volatile boolean closed;
    private final Map<String, Thread> relays = new ConcurrentHashMap<>();

    public GatewayConnection(
            Path root,
            DockerDevelopmentEnvironment environment,
            Workbench store,
            ObjectMapper json) {
        this.root = root;
        this.environment = environment;
        this.store = store;
        this.json = json;
    }

    public void start() {
        environment.onReady(this::startRelay);
        for (var project : store.projects()) {
            String id = project.get("id").toString();
            if (Files.isRegularFile(root.resolve("environments").resolve(id).resolve("environment.json"))) {
                startRelay(id);
            }
        }
    }

    private void startRelay(String project) {
        if (!closed) {
            relays.computeIfAbsent(project, id -> Thread.ofVirtual().name("project-preview-" + id)
                    .start(() -> poll(id)));
        }
    }

    private HttpClient connect(DockerDevelopmentEnvironment.Settings settings) throws Exception {
        Path certs = environment.certificateDirectory(settings);
        var factory = CertificateFactory.getInstance("X.509");
        Certificate ca;
        Certificate client;
        try (var in = Files.newInputStream(certs.resolve("ca.crt"))) {
            ca = factory.generateCertificate(in);
        }
        try (var in = Files.newInputStream(certs.resolve("client.crt"))) {
            client = factory.generateCertificate(in);
        }
        String pem =
                Files.readString(certs.resolve("client.key"))
                        .replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                        .replaceAll("\\s+", "");
        var key =
                KeyFactory.getInstance("EC")
                        .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        var keys = KeyStore.getInstance("PKCS12");
        keys.load(null, null);
        keys.setKeyEntry("client", key, new char[0], new Certificate[] {client, ca});
        var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys, new char[0]);
        var trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("local", ca);
        var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(trust);
        var tls = SSLContext.getInstance("TLSv1.3");
        tls.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .sslContext(tls)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    private void poll(String project) {
        HttpClient client = null;
        String owner = null;
        try {
            while (!closed) {
                try {
                    var settings = environment.registered(project);
                    if (client == null || !settings.owner().equals(owner)) {
                        if (client != null) {
                            client.shutdownNow();
                        }
                        client = connect(settings);
                        owner = settings.owner();
                    }
                    URI base = URI.create("https://127.0.0.1:" + settings.controlPort());
                    var res =
                            client.send(
                                    HttpRequest.newBuilder(base.resolve("/relay/next"))
                                            .timeout(Duration.ofSeconds(5))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString());
                    if (res.statusCode() == 204) {
                        continue;
                    }
                    if (res.statusCode() != 200) {
                        throw new IOException("控制握手不可用");
                    }
                    var query = json.readTree(res.body());
                    process(client, base, settings, query);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception unavailable) {
                    if (client != null) {
                        client.shutdownNow();
                        client = null;
                    }
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        } finally {
            if (client != null) {
                client.shutdownNow();
            }
        }
    }

    private HttpResponse<String> post(HttpClient client, URI url, Object body)
            throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder(url)
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void process(
            HttpClient client,
            URI base,
            DockerDevelopmentEnvironment.Settings settings,
            JsonNode query)
            throws IOException, InterruptedException {
        String kind = query.path("Kind").asText();
        int status = 200;
        Object body;
        try {
            String ref = query.path("Ref").asText();
            if (kind.equals("revalidate")) {
                // 仅网关能发起复核；开发容器的查询入口不接受此操作。
                var identity = environment.previewIdentity(settings, Integer.parseInt(ref));
                body = Map.of("Inode", identity.inode(), "Owner", identity.owner());
            } else {
                var scope =
                        store.lookup(
                                settings.project(), ConversationAgent.hash(query.path("Token").asText()),
                                kind,
                                ref,
                                query.path("Offset").asInt(),
                                query.path("Limit").asInt());
                if (kind.equals("preview")) {
                    int port = Integer.parseInt(ref);
                    String project = scope.get("project").toString();
                    var identity = environment.previewIdentity(settings, port);
                    if (identity.inode().isEmpty()) {
                        throw new WorkbenchRejection(WorkbenchRejection.Kind.BUSY, "端口未监听或监听者未归属当前项目");
                    }
                    var response =
                            post(
                                    client,
                                    base.resolve("/routes"),
                                    Map.of(
                                            "Project",
                                            project,
                                            "Port",
                                            port,
                                            "Inode",
                                            identity.inode(),
                                            "Owner",
                                            identity.owner(),
                                            "WindowsPort",
                                            settings.previewPort()));
                    status = response.statusCode();
                    body =
                            status == 200
                                    ? json.readTree(response.body())
                                    : Map.of("detail", "当前监听不可预览或已被其他项目登记");
                } else {
                    body = scope;
                }
            }
        } catch (WorkbenchRejection e) {
            status =
                    switch (e.kind()) {
                        case MISSING -> 404;
                        case FORBIDDEN -> 403;
                        case BUSY -> 409;
                        case INVALID -> 400;
                    };
            body = Map.of("detail", e.getMessage());
        } catch (NumberFormatException e) {
            status = 400;
            body = Map.of("detail", "预览端口必须为整数");
        }
        String id = query.path("ID").asText();
        if (!id.matches("[0-9a-f]{32}")) {
            throw new IOException("无效的查询身份");
        }
        post(client, base.resolve("/relay/result/" + id), Map.of("Status", status, "Body", body));
    }

    @Override
    public void close() {
        closed = true;
        relays.values().forEach(Thread::interrupt);
    }
}
