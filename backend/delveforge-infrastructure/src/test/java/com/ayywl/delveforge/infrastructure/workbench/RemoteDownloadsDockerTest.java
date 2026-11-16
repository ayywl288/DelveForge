package com.ayywl.delveforge.infrastructure.workbench;

import com.ayywl.delveforge.application.workbench.Workbench;
import com.ayywl.delveforge.domain.workbench.RunState;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 Docker、Maven、npm、pnpm 与 Central。通过缓存端的传输字节核对跨项目复用。 */
@EnabledIfEnvironmentVariable(named = "DF_WORKBENCH_CACHE_FIXTURE", matches = ".+")
class RemoteDownloadsDockerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String JAR = "/maven2/org/json/json/20240303/json-20240303.jar";
    private static final String RACE = "/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0-sources.jar";
    private static final String PROBE = """
            <project><modelVersion>4.0.0</modelVersion><groupId>sample</groupId><artifactId>probe</artifactId>
            <version>1</version><properties><maven.compiler.release>21</maven.compiler.release></properties>
            <dependencies><dependency><groupId>org.json</groupId><artifactId>json</artifactId>
            <version>20240303</version></dependency></dependencies></project>
            """;
    private static final String BUILD = """
            set -eu
            mvn -B -ntp -C -f library/pom.xml install > install.log 2>&1
            mvn -B -ntp -C -f consumer/pom.xml package > consumer.log 2>&1
            file=/state/cache/maven/fixture/shared/1/shared-1.jar
            test "$(java -cp "consumer/target/classes:$file" Use)" = "$(cat owner)"
            sha256sum "$file" > private.sha
            for manager in npm pnpm; do
                (cd "$manager-app"; "$manager" install >install.log 2>&1; "$manager" run build >build.log 2>&1
                 node -e "if(process.platform!=='linux'||require('./dist/app.cjs').owner!==require('fs').readFileSync('../owner','utf8'))process.exit(1)")
            done
            npm config get cache > npm-cache
            pnpm store path > pnpm-store
            """;

    private static final String PRIVATE_ORIGIN = """
            set -eu
            folder="$PWD/origin/org/delveforge/fixture/from-private/1"
            mkdir -p "$folder" origin-consumer
            jar --create --file "$folder/from-private-1.jar" owner
            cat > "$folder/from-private-1.pom" <<'POM'
            <project><modelVersion>4.0.0</modelVersion><groupId>org.delveforge.fixture</groupId>
            <artifactId>from-private</artifactId><version>1</version></project>
            POM
            for f in "$folder"/*.jar "$folder"/*.pom; do sha1sum "$f" | cut -d' ' -f1 > "$f.sha1"; done
            cat > origin-consumer/pom.xml <<POM
            <project><modelVersion>4.0.0</modelVersion><groupId>sample</groupId><artifactId>origin-check</artifactId><version>1</version>
            <repositories><repository><id>project-private</id><url>file://$PWD/origin</url></repository></repositories>
            <dependencies><dependency><groupId>org.delveforge.fixture</groupId><artifactId>from-private</artifactId><version>1</version></dependency></dependencies></project>
            POM
            mvn -B -ntp -C -f origin-consumer/pom.xml package >origin.log 2>&1
            local=/state/cache/maven/org/delveforge/fixture/from-private/1
            grep -Fq 'from-private-1.jar>project-private=' "$local/_remote.repositories"
            test "$(unzip -p "$local/from-private-1.jar" owner)" = "$(cat owner)"
            code=$(curl --noproxy '*' -s -o /dev/null -w '%{http_code}' http://maven-cache:8082/maven2/org/delveforge/fixture/from-private/1/from-private-1.jar)
            test "$code" = 404
            """;

    private record Project(Path copy, Workbench.Run run) {}

    private Project project(Path root, String owner) throws Exception {
        String id = UUID.randomUUID().toString();
        Path copy = Files.createDirectories(root.resolve("projects").resolve(id));
        Files.writeString(copy.resolve("owner"), owner);
        ParallelProjectDockerTest.mavenFiles(copy, owner);
        Files.createDirectories(copy.resolve("public/src/main/java"));
        Files.writeString(copy.resolve("public/pom.xml"), PROBE);
        Files.writeString(copy.resolve("public/src/main/java/PublicUse.java"),
                "public class PublicUse { org.json.JSONObject value = new org.json.JSONObject(); }");
        for (String manager : List.of("npm", "pnpm")) {
            Path dir = Files.createDirectories(copy.resolve(manager + "-app"));
            Files.writeString(dir.resolve("index.js"), "export const owner = '" + owner + "'");
            Files.writeString(dir.resolve("package.json"), """
                    {"name":"native-build-fixture","private":true,"scripts":{"build":"esbuild index.js --bundle --platform=node --format=cjs --outfile=dist/app.cjs"},"dependencies":{"esbuild":"0.25.5"}}
                    """);
        }
        Files.writeString(copy.resolve("pnpm-app/pnpm-workspace.yaml"), "allowBuilds:\n  esbuild: true\n");
        return new Project(copy, new Workbench.Run(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                id, RunState.RUNNING, 0, false));
    }

    private static String bash(DockerDevelopmentEnvironment env, Project project, String script) throws Exception {
        var outcome = WorkbenchDockerTest.execute(env, project.run(), "Bash", Map.of("command", script));
        assertEquals(0, outcome.exit(), outcome.err() + " " + outcome.out() + " " + project.copy());
        return outcome.out().strip();
    }

    private static String host(List<String> args) throws Exception {
        var process = new ProcessBuilder(args).redirectErrorStream(true).start();
        try (var io = Executors.newVirtualThreadPerTaskExecutor()) {
            var output = io.submit(() -> new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("宿主验收命令超时：" + args.getFirst());
            }
            String text = output.get(5, TimeUnit.SECONDS);
            assertEquals(0, process.exitValue(), text);
            return text;
        }
    }

    private static List<String> transfers(String cache, String path) throws Exception {
        return host(List.of("docker", "logs", cache)).lines().filter(line -> line.startsWith("GET " + path + " ")).toList();
    }

    @Test
    void customRepositoryIdentityAndBytesStayWithTheProject() throws Exception {
        Path root = Path.of(System.getenv("DF_WORKBENCH_CACHE_FIXTURE"));
        var a = project(root, "PrivateAlpha");
        var b = project(root, "PrivateBravo");
        try (var env = new DockerDevelopmentEnvironment(root, JSON, 540);
                var parallel = Executors.newVirtualThreadPerTaskExecutor()) {
            var originA = parallel.submit(() -> bash(env, a, PRIVATE_ORIGIN));
            var originB = parallel.submit(() -> bash(env, b, PRIVATE_ORIGIN));
            originA.get();
            originB.get();
        }
    }

    @Test
    void oneTrustedRemoteTransferServesIndependentPrivateBuildsAndSurvivesBadData() throws Exception {
        Path root = Path.of(System.getenv("DF_WORKBENCH_CACHE_FIXTURE"));
        var a = project(root, "Alpha");
        var b = project(root, "Bravo");
        try (var env = new DockerDevelopmentEnvironment(root, JSON, 540);
                var parallel = Executors.newVirtualThreadPerTaskExecutor()) {
            var workA = parallel.submit(() -> bash(env, a, BUILD));
            var workB = parallel.submit(() -> bash(env, b, BUILD));
            workA.get(9, TimeUnit.MINUTES);
            workB.get(9, TimeUnit.MINUTES);
            assertNotEquals(Files.readString(a.copy.resolve("private.sha")), Files.readString(b.copy.resolve("private.sha")));
            assertEquals("/state/cache/npm", Files.readString(a.copy.resolve("npm-cache")).strip());
            assertTrue(Files.readString(b.copy.resolve("pnpm-store")).strip().startsWith("/state/"));
            var identity = JSON.readTree(root.resolve("download-cache/cache.json").toFile());
            String cache = identity.path("id").asText();
            String buildPublic = "mvn -B -ntp -C -f public/pom.xml package >public.log 2>&1 && "
                    + "sha256sum /state/cache/maven/org/json/json/20240303/json-20240303.jar | cut -d' ' -f1";
            int initialRequests = transfers(cache, JAR).size();
            String publicHash = bash(env, a, buildPublic);
            var first = transfers(cache, JAR).stream().skip(initialRequests).toList();
            assertTrue(first.stream().anyMatch(s -> s.matches(".* 200 MISS upstream=[1-9][0-9]*")), first.toString());
            assertEquals(publicHash, bash(env, b, buildPublic));
            var second = transfers(cache, JAR).stream().skip(initialRequests).toList();
            assertTrue(second.stream().skip(first.size()).anyMatch(s -> s.endsWith(" 200 HIT upstream=-")), second.toString());
            assertTrue(second.stream().skip(first.size()).noneMatch(s -> s.matches(".*upstream=[1-9][0-9]*")));
            String concurrent = "curl --noproxy '*' -fsS http://maven-cache:8082" + RACE + " -o /tmp/public-race "
                    + "&& sha256sum /tmp/public-race | cut -d' ' -f1";
            int initialRace = transfers(cache, RACE).size();
            var raceA = parallel.submit(() -> bash(env, a, concurrent));
            var raceB = parallel.submit(() -> bash(env, b, concurrent));
            assertEquals(raceA.get(), raceB.get());
            var raced = transfers(cache, RACE).stream().skip(initialRace).toList();
            assertEquals(1, raced.stream().filter(line -> line.contains(" MISS ")).count(), raced.toString());
            assertEquals(1, raced.stream().filter(line -> line.contains(" HIT ")).count(), raced.toString());
            String boundary = """
                    set -eu
                    u=http://maven-cache:8082/maven2
                    probe() { curl --noproxy '*' -s -o /dev/null -w '%{http_code}' "$@"; }
                    test "$(probe -X PUT "$u/fixture/shared/1/shared-1.jar")" = 405
                    test "$(probe -H 'Authorization: Test fixture' "$u/fixture/shared/1/shared-1.jar")" = 403
                    for i in 1 2; do
                        test "$(probe "$u/fixture/shared/1/shared-1.jar")" = 404
                        curl --noproxy '*' -fsS -D metadata-headers "$u/org/json/json/maven-metadata.xml" -o metadata.xml
                        grep -qi 'X-DelveForge-Cache: BYPASS' metadata-headers
                        curl --noproxy '*' -s -D snapshot-headers "$u/fixture/shared/1-SNAPSHOT/shared-1-SNAPSHOT.jar" -o /dev/null
                        grep -qi 'X-DelveForge-Cache: BYPASS' snapshot-headers
                    done
                    """;
            bash(env, b, boundary);
            assertTrue(transfers(cache, "/maven2/fixture/shared/1/shared-1.jar").stream().noneMatch(line -> line.contains(" HIT ")));
            String damage = """
                    set -eu
                    key=$(printf %s "https://repo.maven.apache.org$1" | md5sum | cut -d' ' -f1)
                    file=$(find /cache/central -type f -name "$key")
                    test -f "$file"
                    size=$(stat -c %s "$file")
                    printf Q | dd of="$file" bs=1 seek=$((size-1)) conv=notrunc 2>/dev/null
                    """;
            host(List.of("docker", "exec", cache, "sh", "-c", damage, "fixture", JAR));
            bash(env, b, """
                    set -eu
                    jar=/state/cache/maven/org/json/json/20240303/json-20240303.jar
                    mv "$jar" known-good.jar
                    if mvn -B -ntp -C -f public/pom.xml package >integrity.log 2>&1; then exit 1; fi
                    test ! -e "$jar"
                    grep -Ei 'checksum (validation )?failed|checksum failure' integrity.log
                    """);
            Path repo = Path.of("").toAbsolutePath();
            while (repo != null && !Files.isRegularFile(repo.resolve("runtime/workbench/Reset-DownloadCache.ps1"))) {
                repo = repo.getParent();
            }
            assertNotNull(repo);
            host(List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                    repo.resolve("runtime/workbench/Reset-DownloadCache.ps1").toString(),
                    "-DataDirectory", root.toString(), "-Action", "Clear"));
            assertEquals("Alpha", Files.readString(a.copy.resolve("owner")));
            assertEquals("Bravo", Files.readString(b.copy.resolve("owner")));
            bash(env, a, "mvn -o -B -ntp -C -f public/pom.xml package >offline.log 2>&1");
            bash(env, b, "mvn -o -B -ntp -C -f consumer/pom.xml package >offline.log 2>&1");
            assertEquals(publicHash, bash(env, b, "mvn -B -ntp -C -U -f public/pom.xml package >repaired.log 2>&1 && "
                    + "sha256sum /state/cache/maven/org/json/json/20240303/json-20240303.jar | cut -d' ' -f1"));
            bash(env, a, """
                    printf '\\n<!-- private fixture setting -->\\n' >> "$HOME/.m2/settings.xml"
                    sha256sum "$HOME/.m2/settings.xml" > private-settings.sha
                    """);
            env.maintain(a.run.projectId(), "Restart");
            env.maintain(a.run.projectId(), "Rebuild");
            bash(env, a, "sha256sum -c private-settings.sha && mvn -o -B -ntp -f consumer/pom.xml package >offline-private.log 2>&1 && "
                    + "test \"$(java -cp consumer/target/classes:/state/cache/maven/fixture/shared/1/shared-1.jar Use)\" = Alpha");
            var registration = env.registered(a.run.projectId());
            host(List.of("docker", "network", "disconnect", registration.owner() + "-inside", cache));
            bash(env, a, "curl --noproxy '*' -fsS http://maven-cache:8082/health");
            System.out.println("CENTRAL cache=" + cache + " A=" + a.run.projectId() + " B=" + b.run.projectId()
                    + " sha256=" + publicHash + " downloads=" + second + " simultaneous=" + raced);
            Map<String, Object> evidence = Map.of(
                    "cache", cache, "A", a.run.projectId(), "B", b.run.projectId(), "sha256", publicHash,
                    "requests", second, "concurrent", raced,
                    "npm", Files.readString(a.copy.resolve("npm-cache")).strip(),
                    "pnpm", Files.readString(b.copy.resolve("pnpm-store")).strip());
            Files.writeString(root.resolve("transfer-evidence.json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        }
    }
}
