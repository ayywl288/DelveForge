package com.ayywl.delveforge.infrastructure.workbench;

import com.ayywl.delveforge.application.agent.AgentModelPort;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.workbench.ConversationAgent;
import com.ayywl.delveforge.application.workbench.ProjectFiles;
import com.ayywl.delveforge.application.workbench.Workbench;
import com.ayywl.delveforge.domain.workbench.RunState;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "DF_WORKBENCH_TEST_FIXTURE", matches = ".+")
class ParallelProjectDockerTest {
    @TempDir Path temp;
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Project(String id, String conversation, Path copy) {}

    private Project project(Workbench store, ProjectFiles files, String name) throws Exception {
        String id = store.beginProject(name, null);
        Path copy = Path.of(files.prepare(id, null));
        store.projectResult(id, copy.toString(), null);
        Files.writeString(copy.resolve("owner"), name);
        return new Project(id, store.conversation(id, name), copy);
    }

    private static void waitFor(java.util.function.BooleanSupplier done, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertTrue(done.getAsBoolean(), "等待验收条件超时");
    }

    private static AgentModelPort model() {
        return (messages, tools) -> {
            var last = messages.getLast();
            if ("tool".equals(last.role()) || !"执行项目夹具".equals(last.text())) {
                return WorkbenchIntegrationTest.finalText("本任务已完成");
            }
            return new AgentProtocol.Reply("", List.of(new AgentProtocol.ToolCall("bash", "Bash",
                    "{\"command\":\"bash acceptance.sh\"}")), 1L, null, "fixture");
        };
    }

    private static String response(URI url, boolean websocket) throws Exception {
        var args = new java.util.ArrayList<>(List.of("curl.exe","--noproxy","*","-sS","--max-time","8",
                "--resolve",url.getHost()+":"+url.getPort()+":127.0.0.1"));
        if (websocket) {
            args.addAll(List.of("-i","-H","Connection: Upgrade","-H","Upgrade: websocket",
                    "-H","Sec-WebSocket-Version: 13","-H","Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ=="));
        }
        args.add(url.toString());
        Process command = new ProcessBuilder(args).redirectErrorStream(true).start();
        String out = new String(command.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, command.waitFor(), out);
        return out;
    }

    private static final String SERVER = """
            import base64, hashlib, socket
            from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
            from pathlib import Path
            class Handler(BaseHTTPRequestHandler):
                def do_HEAD(self):
                    self.send_response(200)
                    self.end_headers()
                def do_GET(self):
                    owner = Path('owner').read_text().encode()
                    if self.headers.get('Upgrade','').lower() == 'websocket':
                        key = self.headers['Sec-WebSocket-Key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'
                        self.send_response(101)
                        self.send_header('Upgrade','websocket')
                        self.send_header('Connection','Upgrade')
                        self.send_header('Sec-WebSocket-Accept',base64.b64encode(hashlib.sha1(key.encode()).digest()).decode())
                        self.end_headers()
                        self.wfile.write(bytes([129,len(owner)]) + owner + bytes([136,0]))
                    else:
                        self.send_response(200)
                        self.end_headers()
                        self.wfile.write(owner)
            server = ThreadingHTTPServer(('0.0.0.0',8086), Handler)
            Path('listener').write_text(socket.gethostbyname(socket.gethostname()))
            server.serve_forever()
            """;

    private static final String BASH = """
            set -euo pipefail
            owner=$(cat owner)
            setsid -f python3 server.py </dev/null >server.log 2>&1
            for i in {1..300}; do test -f listener && break; sleep .1; done
            df-workbench preview 8086 >preview.json
            df-workbench history >history.json
            touch entered
            for i in {1..1800}; do test -f peer && break; sleep .1; done
            read -r foreign addr invocation <peer
            test ! -e "/projects/$foreign/owner"
            test ! -d "/projects/$foreign"
            test ! -e /var/run/docker.sock
            test -z "${DEEPSEEK_API_KEY:-}"
            test ! -e /state/control.db
            if curl --noproxy '*' -fsS --connect-timeout 2 "http://$addr:8086/" >/dev/null 2>&1; then exit 21; fi
            test "$(curl --proxy http://egress:3128 --noproxy '' -s --max-time 6 -o /dev/null -w '%{http_code}' http://$addr:80/)" = 403
            if df-workbench output "$invocation" >foreign-output 2>foreign-error; then exit 22; fi
            grep -q 404 foreign-error
            printf '%s' "$owner" > "$HOME/private-marker"
            printf '#!/bin/sh\\nprintf %s\\n' "$owner" > /usr/local/bin/project-private-tool
            chmod +x /usr/local/bin/project-private-tool
            test "$(project-private-tool)" = "$owner"
            mvn -B -f library/pom.xml install >maven-install.log 2>&1
            mvn -B -f consumer/pom.xml package >maven-consumer.log 2>&1
            base=/state/cache/maven/fixture/shared/1/shared-1.jar
            test "$(java -cp "consumer/target/classes:$base" Use)" = "$owner"
            sha256sum "$base" >artifact.sha
            printf '%s\\n' "$owner" >verified
            for i in {1..1200}; do test -f release && exit 0; sleep .1; done
            exit 23
            """;

    private static void fixture(Project p, String name) throws Exception {
        Files.writeString(p.copy.resolve("server.py"), SERVER);
        Files.writeString(p.copy.resolve("acceptance.sh"), BASH);
        mavenFiles(p.copy, name);
    }

    static void mavenFiles(Path copy, String name) throws Exception {
        String base = "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><version>1</version>"
                + "<properties><maven.compiler.release>21</maven.compiler.release></properties>";
        Files.createDirectories(copy.resolve("library/src/main/java"));
        Files.writeString(copy.resolve("library/pom.xml"),base+"<artifactId>shared</artifactId></project>");
        Files.writeString(copy.resolve("library/src/main/java/Shared.java"),
                "public class Shared { public static String owner(){return \""+name+"\";} }");
        Files.createDirectories(copy.resolve("consumer/src/main/java"));
        Files.writeString(copy.resolve("consumer/pom.xml"),base+"<artifactId>consumer</artifactId><dependencies>"
                +"<dependency><groupId>fixture</groupId><artifactId>shared</artifactId><version>1</version>"
                +"</dependency></dependencies></project>");
        Files.writeString(copy.resolve("consumer/src/main/java/Use.java"),
                "public class Use { public static void main(String[] a){System.out.print(Shared.owner());} }");
    }

    private static void peer(Project me, Project other, String invocation) throws Exception {
        Files.writeString(me.copy.resolve("peer.tmp"), other.id+" "+Files.readString(other.copy.resolve("listener"))
                +" "+invocation+"\n");
        Files.move(me.copy.resolve("peer.tmp"),me.copy.resolve("peer"),java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static URI preview(Project p) throws Exception {
        return URI.create(JSON.readTree(p.copy.resolve("preview.json").toFile()).get("url").asText());
    }

    @Test
    void twoAgentsBuildAndPreviewTheSameCoordinatesAndPortInSeparateProjects() throws Exception {
        Path root=Path.of(System.getenv("DF_WORKBENCH_TEST_FIXTURE"));
        var store=WorkbenchDockerTest.store(temp.resolve("parallel.db"));
        var files=new ProjectWorkcopies(root.resolve("projects"));
        var a=project(store,files,"Alpha");
        var b=project(store,files,"Bravo");
        var c=project(store,files,"Charlie");
        fixture(a,"Alpha");
        fixture(b,"Bravo");
        assertFalse(Files.exists(root.resolve("environments").resolve(a.id)));
        try (var env=new DockerDevelopmentEnvironment(root,JSON,540);
                var relay=new GatewayConnection(root,env,store,JSON);
                var agent=WorkbenchDockerTest.agent(store,env,model())) {
            relay.start();
            var ra=agent.receive(a.conversation,"original","执行项目夹具");
            var rb=agent.receive(b.conversation,"original","执行项目夹具");
            var qc=agent.receive(c.conversation,"queue","独立的排队任务");
            var second=store.conversation(a.id,"同项目的第二个对话");
            var qa=agent.receive(second,"later","另一段对话");
            assertEquals(RunState.QUEUED,store.run(qc.runId()).state());
            assertEquals(RunState.QUEUED,store.run(qa.runId()).state());
            assertEquals(ra.runId(),agent.receive(a.conversation,"original","执行项目夹具").runId());
            waitFor(() -> Files.exists(a.copy.resolve("entered")) && Files.exists(b.copy.resolve("entered")),100);
            var ca=store.pending(ra.runId()).getFirst();
            var cb=store.pending(rb.runId()).getFirst();
            assertEquals("ADMITTED",ca.state());
            assertEquals("ADMITTED",cb.state());
            assertNotEquals(ca.container(),cb.container());
            assertNotEquals(env.registered(a.id).stateVolume(),env.registered(b.id).stateVolume());
            System.out.printf("PARALLEL A=%s container=%s B=%s container=%s samePort=8086%n",
                    a.id,ca.container(),b.id,cb.container());
            peer(a,b,cb.id());
            peer(b,a,ca.id());
            var ua=preview(a);
            var ub=preview(b);
            assertNotEquals(ua,ub);
            assertEquals("Alpha",response(ua,false));
            assertEquals("Bravo",response(ub,false));
            assertTrue(response(ua,true).contains("101 Switching Protocols"));
            assertTrue(response(ub,true).contains("Bravo"));
            waitFor(() -> Files.exists(a.copy.resolve("verified")) && Files.exists(b.copy.resolve("verified"))
                    || store.pending(ra.runId()).isEmpty() || store.pending(rb.runId()).isEmpty(),300);
            assertTrue(Files.exists(a.copy.resolve("verified")) && Files.exists(b.copy.resolve("verified")),
                    "请核对两个项目的实际命令回执和 Maven 日志");
            assertNotEquals(Files.readString(a.copy.resolve("artifact.sha")),Files.readString(b.copy.resolve("artifact.sha")));
            Files.writeString(a.copy.resolve("release"),"done");
            waitFor(() -> store.run(qc.runId()).state()==RunState.COMPLETED
                    && store.run(qa.runId()).state()==RunState.COMPLETED,30);
            assertEquals(RunState.RUNNING,store.run(rb.runId()).state());
            assertEquals(1,store.timeline(a.conversation).stream().filter(row -> "tool".equals(row.get("role"))).count());
            assertFalse(Files.exists(root.resolve("environments").resolve(c.id)));
            agent.maintain(a.id,"Restart");
            assertEquals("Bravo",response(ub,false));
            var previous=env.registered(a.id);
            var probe=WorkbenchDockerTest.execute(env,store.run(ra.runId()),"Bash", Map.of("command",
                    "test \"$(project-private-tool)\" = Alpha && test \"$(cat \"$HOME/private-marker\")\" = Alpha "
                            + "&& curl -fsS --max-time 25 -o /dev/null https://repo.maven.apache.org/maven2/org/apache/maven/maven-core/3.9.9/maven-core-3.9.9.pom"));
            assertEquals(0,probe.exit(),probe.err());
            agent.maintain(a.id,"Rebuild");
            var replacement=env.registered(a.id);
            assertNotEquals(previous.containerId(),replacement.containerId());
            assertEquals(previous.stateVolume(),replacement.stateVolume());
            var retained=WorkbenchDockerTest.execute(env,store.run(ra.runId()),"Bash",Map.of("command",
                    "test ! -e /usr/local/bin/project-private-tool && test \"$(cat \"$HOME/private-marker\")\" = Alpha && test -s artifact.sha"));
            assertEquals(0,retained.exit(),retained.err());
            assertEquals("Bravo",response(ub,false));
            Files.writeString(b.copy.resolve("release"),"done");
            waitFor(() -> store.run(rb.runId()).state()==RunState.COMPLETED,30);
        } finally {
            Files.writeString(a.copy.resolve("release"),"done");
            Files.writeString(b.copy.resolve("release"),"done");
        }
    }
}
