package com.ayywl.delveforge.infrastructure.workbench;

import com.ayywl.delveforge.application.workbench.ExecutionEnvironment;
import com.ayywl.delveforge.application.workbench.Workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

public final class DockerDevelopmentEnvironment implements ExecutionEnvironment, AutoCloseable {
    public record Settings(
            String owner,
            String endpoint,
            String container,
            String parent,
            int previewPort,
            int controlPort, String installation, String project, String containerId, String image,
            String startedAt, String stateVolume, Map<String, String> images, String previousOwner, String cacheContainer) {}

    private final ObjectMapper json;
    private final ProjectRuntime runtime;
    private final Map<String, Object> projectLocks = new ConcurrentHashMap<>();
    private volatile Consumer<String> onReady = project -> {};
    private final int timeout;
    private DockerClient docker;
    private String endpoint;

    public DockerDevelopmentEnvironment(Path root, ObjectMapper json, int timeout) {
        this(root, json, timeout, 2, 3072, 768);
    }

    public DockerDevelopmentEnvironment(Path root, ObjectMapper json, int timeout, int cpu, int memory, int pids) {
        this.json = json;
        this.timeout = timeout;
        runtime = new ProjectRuntime(root, json, cpu, memory, pids);
    }

    void onReady(Consumer<String> listener) {
        onReady = listener;
    }

    Path certificateDirectory(Settings settings) {
        return runtime.directory(settings.project()).resolve("tls-" + settings.owner()).resolve("client");
    }

    Settings registered(String project) {
        return runtime.registered(project);
    }

    private Settings ensure(String project) {
        synchronized (projectLocks.computeIfAbsent(project, ignored -> new Object())) {
            Settings settings = registered(project);
            if (settings == null || !downloadCacheReady(settings)
                    || client().listContainersCmd()
                    .withLabelFilter(Map.of("dev.delveforge.workbench", settings.owner())).exec().size() != 4) {
                runtime.provision(project, "Ensure");
                settings = registered(project);
            }
            onReady.accept(project);
            return settings;
        }
    }

    private boolean downloadCacheReady(Settings settings) {
        if (settings.cacheContainer() == null) {
            return !runtime.catalog().path("images").hasNonNull("cache");
        }
        try {
            var cache = client().inspectContainerCmd(settings.cacheContainer()).exec();
            return Boolean.TRUE.equals(cache.getState().getRunning())
                    && settings.installation().equals(cache.getConfig().getLabels().get("dev.delveforge.cache"))
                    && cache.getNetworkSettings().getNetworks().containsKey(settings.owner() + "-inside");
        } catch (NotFoundException missing) {
            return false;
        }
    }

    @Override
    public boolean maintenanceEnded(String project) {
        Path directory = runtime.directory(project);
        if (Files.notExists(directory.resolve("environment.json")) || Files.exists(directory.resolve("provisioning.json"))
                || Files.exists(directory.resolve("handover.json"))) {
            try {
                runtime.provision(project, Files.exists(directory.resolve("environment.json")) ? "Recover" : "Ensure");
            } catch (RuntimeException unresolved) {
                return false;
            }
        }
        try (var channel = java.nio.channels.FileChannel.open(directory.resolve("provision.lock"),
                java.nio.file.StandardOpenOption.WRITE); var lock = channel.tryLock()) {
            if (lock == null || registered(project) == null) {
                return false;
            }
            development(registered(project));
            return true;
        } catch (RuntimeException | IOException unavailable) {
            return false;
        }
    }

    @Override
    public void maintain(String project, String action) {
        synchronized (projectLocks.computeIfAbsent(project, ignored -> new Object())) {
            runtime.provision(project, action);
            development(registered(project));
            onReady.accept(project);
        }
    }

    private synchronized DockerClient client() {
        String registeredEndpoint = runtime.catalog().path("endpoint").asText();
        if (!registeredEndpoint.startsWith("npipe:////./pipe/")) {
            throw new IllegalStateException("需要本机 Docker Desktop Linux 引擎");
        }
        if (docker != null && registeredEndpoint.equals(endpoint)) {
            return docker;
        }
        if (docker != null) {
            close();
        }
        var config =
                DefaultDockerClientConfig.createDefaultConfigBuilder()
                        .withDockerHost(registeredEndpoint)
                        .withDockerTlsVerify(false)
                        .withApiVersion("1.44")
                        .build();
        var transport =
                new ApacheDockerHttpClient.Builder()
                        .dockerHost(config.getDockerHost())
                        .sslConfig(config.getSSLConfig())
                        .connectionTimeout(Duration.ofSeconds(5))
                        .responseTimeout(Duration.ofSeconds(timeout + 20L))
                        .build();
        docker = DockerClientImpl.getInstance(config, transport);
        endpoint = registeredEndpoint;
        return docker;
    }

    private InspectContainerResponse development(Settings s) {
        var c = client();
        var dev = c.inspectContainerCmd(s.container()).exec();
        var hc = dev.getHostConfig();
        var net = c.inspectNetworkCmd().withNetworkId(s.owner() + "-inside").exec();
        if (!Boolean.TRUE.equals(dev.getState().getRunning())
                || !dev.getId().equals(s.containerId()) || !dev.getImageId().equals(s.image())
                || !s.project().equals(dev.getConfig().getLabels().get("dev.delveforge.project"))
                || !s.installation().equals(dev.getConfig().getLabels().get("dev.delveforge.installation"))
                || !s.owner().equals(dev.getConfig().getLabels().get("dev.delveforge.workbench"))
                || !Boolean.TRUE.equals(net.getInternal())
                || !"isolated"
                        .equals(net.getOptions().get("com.docker.network.bridge.gateway_mode_ipv4"))
                || !dev.getNetworkSettings().getNetworks().keySet().equals(Set.of(net.getName()))
                || Boolean.TRUE.equals(hc.getPrivileged())
                || hc.getCapDrop() == null
                || java.util.Arrays.stream(hc.getCapDrop())
                        .noneMatch(cap -> cap.name().equals("ALL"))
                || hc.getCapAdd() != null
                        && java.util.Arrays.stream(hc.getCapAdd())
                                .anyMatch(
                                        cap ->
                                                !Set.of(
                                                                "CHOWN",
                                                                "DAC_OVERRIDE",
                                                                "FOWNER",
                                                                "SETUID",
                                                                "SETGID",
                                                                "SYS_CHROOT")
                                                        .contains(cap.name()))) {
            throw new IllegalStateException("开发容器未运行或隔离配置与登记不符");
        }
        boolean project = false;
        boolean state = false;
        for (var m : dev.getMounts()) {
            String dest = m.getDestination().getPath();
            if (dest.equals("/projects/" + s.project())
                    && Boolean.TRUE.equals(m.getRW())
                    && mountPath(m.getSource()).equalsIgnoreCase(mountPath(s.parent()))) {
                project = true;
            } else if (dest.equals("/state") && s.stateVolume().equals(m.getName()) && Boolean.TRUE.equals(m.getRW())) {
                state = true;
            } else {
                throw new IllegalStateException("开发容器包含未登记的挂载");
            }
        }
        if (!project || !state) {
            throw new IllegalStateException("工作副本或状态卷缺失");
        }
        return dev;
    }

    private static String mountPath(String value) {
        String p = value.replace('\\', '/');
        for (String prefix : List.of("/run/desktop/mnt/host/", "/host_mnt/")) {
            if (p.startsWith(prefix) && p.length() > prefix.length() + 1) {
                return p.charAt(prefix.length()) + ":" + p.substring(prefix.length() + 1);
            }
        }
        return p;
    }

    @Override
    public Prepared prepare(Workbench.Run run, Workbench.Call call, String capability) {
        Settings s = ensure(run.projectId());
        var dev = development(s);
        String home = "/state/projects/" + run.projectId() + "/home";
        String exec =
                client()
                        .execCreateCmd(dev.getId())
                        .withWorkingDir("/projects/" + run.projectId())
                        .withTty(false)
                        .withAttachStdout(true)
                        .withAttachStderr(true)
                        .withEnv(
                                List.of(
                                        "HOME=" + home,
                                        "DF_INVOCATION=" + capability,
                                        "DF_PROJECT_ID=" + run.projectId(),
                                        "MAVEN_USER_HOME=" + home + "/.m2"))
                        .withCmd(commandArguments(call))
                        .exec()
                        .getId();
        return new Prepared(dev.getId(), dev.getState().getStartedAt(), exec);
    }

    private static String[] commandArguments(Workbench.Call call) {
        var parts =
                new ArrayList<>(
                        List.of(
                                "python3",
                                "/opt/delveforge/invocation.py",
                                "run",
                                call.id(),
                                call.name()));
        // Linux 为单个 argv 项设置了独立上限。按 UTF-16 字符边界分段，再在客体内还原原始 JSON。
        String arguments = call.arguments();
        for (int start = 0; start < arguments.length(); ) {
            int end = Math.min(start + 16384, arguments.length());
            if (end < arguments.length() && Character.isHighSurrogate(arguments.charAt(end - 1))) {
                end--;
            }
            parts.add(arguments.substring(start, end));
            start = end;
        }
        return parts.toArray(String[]::new);
    }

    @Override
    public void execute(Workbench.Call prepared, BiConsumer<String, byte[]> capture) {
        attach(client(), prepared.execId(), timeout, capture);
    }

    /** 检查已登记容器中的监听者；只有唯一的原进程可自动恢复预览。 */
    public String previewListener(String project, int port) {
        return previewIdentity(registered(project), port).inode();
    }

    record PreviewIdentity(String inode, String owner) {}

    PreviewIdentity previewIdentity(Settings settings, int port) {
        if (port < 1 || port > 65535 || !settings.equals(registered(settings.project()))) {
            return new PreviewIdentity("", "");
        }
        var dev = development(settings);
        var c = client();
        String exec =
                c.execCreateCmd(dev.getId())
                        .withTty(false)
                        .withAttachStdout(true)
                        .withCmd(
                                "python3",
                                "/opt/delveforge/listener.py",
                                settings.project(),
                                String.valueOf(port),
                                "--identity")
                        .exec()
                        .getId();
        var out = new ByteArrayOutputStream();
        attach(
                c,
                exec,
                4,
                (channel, bytes) -> {
                    if (out.size() + bytes.length < 128) {
                        out.writeBytes(bytes);
                    }
                });
        String[] identity = out.toString(StandardCharsets.US_ASCII).strip().split(" ");
        if (!identity[0].matches("[1-9][0-9]*")) {
            return new PreviewIdentity("", "");
        }
        // 旧镜像或共享监听仍可登记，但无法证明替换监听与原进程相同。
        String owner = identity.length == 2 && identity[1].matches("[1-9][0-9]*:[0-9]+")
                ? dev.getId() + "/" + dev.getState().getStartedAt() + "/" + identity[1] : "";
        return new PreviewIdentity(identity[0], owner);
    }

    private static void attach(
            DockerClient docker, String exec, int seconds, BiConsumer<String, byte[]> receiver) {
        var failed = new AtomicReference<RuntimeException>();
        try (var callback =
                new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        try {
                            receiver.accept(
                                    frame.getStreamType() == StreamType.STDERR
                                            ? "stderr"
                                            : "stdout",
                                    frame.getPayload());
                        } catch (RuntimeException e) {
                            failed.compareAndSet(null, e);
                            onError(e);
                        }
                    }
                }) {
            if (!docker.execStartCmd(exec)
                    .withTty(false)
                    .exec(callback)
                    .awaitCompletion(seconds, TimeUnit.SECONDS)) {
                throw new ExecutionEnvironment.TimedOut();
            }
            if (failed.get() != null) {
                throw failed.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("前台采集已中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("执行输出连接已中断", e);
        }
    }

    @Override
    public End reconcile(Workbench.Call call, boolean stop) {
        var client = client();
        InspectContainerResponse dev;
        try {
            dev = client.inspectContainerCmd(call.container()).exec();
        } catch (NotFoundException e) {
            return new End(true, null, "原容器已不存在，历史结果未知");
        }
        var labels = dev.getConfig().getLabels();
        String installation = runtime.catalog().path("owner").asText();
        boolean legacy = installation.equals(labels.get("dev.delveforge.workbench"))
                && dev.getName().equals("/" + installation + "-dev");
        if (!legacy && (!installation.equals(labels.get("dev.delveforge.installation"))
                || call.projectId() != null && !call.projectId().equals(labels.get("dev.delveforge.project")))) {
            return new End(false, null, "容器归属与已记录的执行不符");
        }
        if (!Boolean.TRUE.equals(dev.getState().getRunning())
                || !call.generation().equals(dev.getState().getStartedAt())) {
            return new End(true, null, "原容器实例已停止或重启，历史结果未知");
        }
        String fence =
                client.execCreateCmd(call.container())
                        .withTty(false)
                        .withAttachStdout(true)
                        .withAttachStderr(true)
                        .withCmd(
                                "python3",
                                "/opt/delveforge/invocation.py",
                                "fence",
                                call.id(),
                                stop ? "stop" : "inspect")
                        .exec()
                        .getId();
        var out = new ByteArrayOutputStream();
        attach(
                client,
                fence,
                8,
                (channel, bytes) -> {
                    if (channel.equals("stdout") && out.size() + bytes.length < 8192) {
                        out.writeBytes(bytes);
                    }
                });
        try {
            var proof = json.readTree(out.toString(StandardCharsets.UTF_8));
            if (proof == null
                    || !proof.path("ended").isBoolean()
                    || !proof.path("ended").asBoolean()) {
                return new End(false, null, "原前台进程组仍存活或缺少证据");
            }
            Integer exit = null;
            try {
                var inspect = client.inspectExecCmd(call.execId()).exec();
                if (Boolean.TRUE.equals(inspect.isRunning())) {
                    return new End(false, null, "Docker 仍观察到原执行存活");
                }
                if (proof.path("started").asBoolean()
                        && Boolean.FALSE.equals(inspect.isRunning())
                        && inspect.getExitCodeLong() != null) {
                    exit = inspect.getExitCodeLong().intValue();
                }
            } catch (NotFoundException ignored) {
                // 已封住迟到的启动并确认组已结束；Docker 可能已回收退出码。
            }
            return new End(true, exit, proof.path("detail").asText());
        } catch (IOException e) {
            throw new IllegalStateException("无法核对前台执行", e);
        }
    }

    @Override
    public synchronized void close() {
        if (docker != null) {
            try {
                docker.close();
            } catch (IOException e) {
                throw new IllegalStateException("关闭 Docker 连接失败", e);
            }
            docker = null;
        }
    }
}
