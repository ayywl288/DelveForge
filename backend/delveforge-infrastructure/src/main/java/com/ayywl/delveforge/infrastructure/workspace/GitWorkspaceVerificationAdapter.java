package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.port.workspace.*;
import com.ayywl.delveforge.application.evolution.execution.EvolutionExecutionException;
import com.ayywl.delveforge.application.evolution.preparation.CodingProposal.Action;
import com.ayywl.delveforge.application.evolution.preparation.CodingProposal.VerificationAction;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** 固定 Candidate 的隔离验证运行目录避免构建产物污染演化副本；不是新的领域 WorkingCopy。 */
@Component
@EnableConfigurationProperties(VerificationProperties.class)
public class GitWorkspaceVerificationAdapter implements WorkspaceVerificationPort {
    private final WorkspaceReadPort read;
    private final WorkingCopyInspectionPort inspection;
    private final VerificationProperties budgets;

    public GitWorkspaceVerificationAdapter(WorkspaceReadPort read, WorkingCopyInspectionPort inspection, VerificationProperties budgets) {
        this.read = read;
        this.inspection = inspection;
        this.budgets = budgets;
    }

    @Override
    public void requireSupported(CandidateVerification request) {
        if (request.strategy().isEmpty() || request.strategy().size() > budgets.maxActions()) {
            throw new EvolutionExecutionException("Verification action budget exceeded");
        }
        List<WorkspaceEntry> entries = read.listEntries(request.workingCopy(), request.candidateRevision(), "", 3);
        Set<String> files = new HashSet<>();
        entries.forEach(entry -> files.add(entry.relativePath()));
        for (VerificationAction action : request.strategy()) {
            if (action.action() == Action.DIFF_CHECK) {
                if (action.target() != null && !action.target().isBlank()) {
                    throw new EvolutionExecutionException("DIFF_CHECK only supports the complete baseline to Candidate diff");
                }
            } else {
                String wrapper = windows() ? "mvnw.cmd" : "mvnw";
                if (!files.contains("pom.xml") || !files.contains(wrapper)
                        || !files.contains(".mvn/wrapper/maven-wrapper.properties")) {
                    throw new EvolutionExecutionException("Verification requires a supported Maven Wrapper project");
                }
                inspection.requireSafePaths(request.workingCopy(), List.of("pom.xml", wrapper, ".mvn/wrapper/maven-wrapper.properties"));
                if (action.action() == Action.FOCUSED_TEST) {
                    // Surefire 的单一类或类#方法选择；拒绝 Maven 参数、通配、shell 与属性扩展。
                    if (action.target() == null || !action.target().matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*(#[A-Za-z_][A-Za-z0-9_]*)?")) {
                        throw new EvolutionExecutionException("Unsupported focused test selector");
                    }
                } else if (action.target() != null && !action.target().isBlank()) {
                    throw new EvolutionExecutionException("BUILD and TEST only support the repository root");
                }
            }
        }
    }

    @Override
    public VerificationFacts verify(CandidateVerification request) {
        inspection.requireTrustedState(request.workingCopy(), request.originalAsset(), request.candidateRevision());
        requireSupported(request);
        Path copy = Path.of(request.workingCopy().value());
        Path run = null;
        long deadline = System.nanoTime() + budgets.invocationTimeout().toNanos();
        List<VerificationFacts.Check> checks = new ArrayList<>();
        try {
            // 位于 .git 内的本次私有目录不会进入 Candidate 或影响后续可信状态检查。
            run = Files.createTempDirectory(copy.resolve(".git"), "delveforge-verification-");
            boolean buildRequested = request.strategy().stream().anyMatch(action -> action.action() != Action.DIFF_CHECK);
            if (buildRequested) {
                requireSuccess(command(copy, List.of("git", "clone", "--no-local", "--no-checkout", "--template=", "--",
                        copy.toString(), run.toString()), deadline), "Candidate verification materialization");
                requireSuccess(command(run, List.of("git", "-c", "core.hooksPath=" + run.resolve(".git/disabled-hooks"),
                        "checkout", "--detach", request.candidateRevision()), deadline), "Candidate verification checkout");
            }
            for (VerificationAction action : request.strategy()) {
                WorkspaceCommandRunner.Result result;
                String evidence = "";
                boolean passed;
                if (action.action() == Action.DIFF_CHECK) {
                    // --check 检查真实 A..B 的冲突标记和空白错误，不读取 AI 摘要。
                    result = command(copy, List.of("git", "--no-optional-locks", "diff", "--no-ext-diff",
                            "--check", request.baselineRevision(), request.candidateRevision(), "--"), deadline);
                    passed = result.succeeded();
                } else {
                    requireMaterializedCandidate(run, request.candidateRevision(), deadline);
                    List<String> args = mavenCommand(action, windows());
                    long actionStarted = System.currentTimeMillis();
                    result = command(run, args, deadline);
                    requireMaterializedCandidate(run, request.candidateRevision(), deadline);
                    passed = result.succeeded();
                    if (passed && action.action() != Action.BUILD) {
                        TestEvidence tests = testEvidence(run, actionStarted);
                        passed = tests.executed() > 0 && tests.failures() == 0
                                && (action.action() != Action.FOCUSED_TEST || tests.includes(action.target()));
                        evidence = "; executed=" + tests.executed() + ", failures=" + tests.failures();
                    }
                }
                String outcome = result.output().contains("No tests matching pattern") ? "NO_MATCHING_TESTS"
                        : result.output().contains("No tests were executed") ? "NO_TESTS" : "COMPLETED";
                checks.add(new VerificationFacts.Check(action.action(), action.target(), passed,
                        action.action() + " " + outcome + " with exit " + result.exitCode() + evidence));
                if (!passed) {
                    // 保持每个预定动作的对应事实，剩余动作明确为未执行，不能推断成功。
                    for (int index = checks.size(); index < request.strategy().size(); index++) {
                        VerificationAction pending = request.strategy().get(index);
                        checks.add(new VerificationFacts.Check(pending.action(), pending.target(), false,
                                "Not run because an earlier check failed"));
                    }
                    break;
                }
            }
            inspection.requireTrustedState(request.workingCopy(), request.originalAsset(), request.candidateRevision());
            return new VerificationFacts(request.candidateRevision(), checks, Instant.now());
        } catch (IOException failure) {
            throw new WorkspaceException("Cannot create Candidate verification directory", failure);
        } finally {
            if (run != null) {
                removeRun(copy, run);
            }
        }
    }

    static List<String> mavenCommand(VerificationAction action, boolean windows) {
        List<String> args = new ArrayList<>(windows ? List.of("cmd.exe", "/d", "/c", "mvnw.cmd")
                : List.of("sh", "./mvnw"));
        args.addAll(List.of("--batch-mode", "--no-transfer-progress", "-o"));
        if (action.action() == Action.BUILD) {
            args.addAll(List.of("-DskipTests", "package"));
        } else {
            // 两个平台都显式启用测试；测试参数只能跟在 Wrapper 启动程序之后。
            args.addAll(List.of("-DskipTests=false", "-Dmaven.test.skip=false", "-DfailIfNoTests=true"));
            if (action.action() == Action.FOCUSED_TEST) {
                args.add("-Dtest=" + action.target());
                args.add("-Dsurefire.failIfNoSpecifiedTests=true");
            }
            args.add("test");
        }
        return List.copyOf(args);
    }

    private void requireMaterializedCandidate(Path run, String candidate, long deadline) {
        var head = command(run, List.of("git", "rev-parse", "HEAD"), deadline);
        var changes = command(run, List.of("git", "--no-optional-locks", "-c", "core.fsmonitor=false",
                "diff", "--no-ext-diff", "--name-only", "HEAD", "--"), deadline);
        var flags = command(run, List.of("git", "--no-optional-locks", "ls-files", "-v", "-z"), deadline);
        boolean hidden = Arrays.stream(flags.output().split("\0")).anyMatch(entry -> !entry.isEmpty()
                && (Character.isLowerCase(entry.charAt(0)) || entry.charAt(0) == 'S'));
        if (!head.succeeded() || !candidate.equals(head.output().trim()) || !changes.succeeded() || !changes.output().isBlank()) {
            throw new EvolutionExecutionException("Verification changed the Candidate source or revision");
        }
        if (!flags.succeeded() || hidden) {
            throw new EvolutionExecutionException("Verification index flags can hide source changes");
        }
    }

    record TestEvidence(long executed, long failures, Set<String> passedTests) {
        private static final String TYPE = "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*(?:\\[\\])*";
        private static final java.util.regex.Pattern METHOD = java.util.regex.Pattern.compile(
                "([A-Za-z_][A-Za-z0-9_]*)(?:\\((?:" + TYPE + "(?:, ?" + TYPE + ")*)?\\))?(?:\\[[1-9][0-9]*\\])?");

        boolean includes(String target) {
            if (executed == 0 || failures != 0) {
                return false;
            }
            String[] selector = target.split("#", -1);
            return passedTests.stream().anyMatch(test -> {
                String[] identity = test.split("#", 2);
                if (identity.length != 2) {
                    return false;
                }
                boolean sameClass = identity[0].equals(selector[0])
                        || (!selector[0].contains(".") && identity[0].endsWith("." + selector[0]));
                // 只剥离支持的 Java 参数签名与数字调用后缀，绝不做方法前缀匹配。
                var method = METHOD.matcher(identity[1]);
                return sameClass && method.matches() && (selector.length == 1 || method.group(1).equals(selector[1]));
            });
        }
    }

    TestEvidence testEvidence(Path run, long actionStarted) {
        long executed = 0;
        long failures = 0;
        long bytes = 0;
        int reports = 0;
        Set<String> passedTests = new HashSet<>();
        try (var paths = Files.walk(run)) {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            for (Path report : paths.filter(path -> path.getFileName().toString().startsWith("TEST-")
                    && path.getFileName().toString().endsWith(".xml")
                    && path.getParent().getFileName().toString().equals("surefire-reports")).limit(101).toList()) {
                if (Files.getLastModifiedTime(report, LinkOption.NOFOLLOW_LINKS).toMillis() <= actionStarted) {
                    continue;
                }
                bytes += Files.size(report);
                if (++reports > 100 || bytes > budgets.maxOutputBytes() || Files.isSymbolicLink(report)
                        || !report.toRealPath().startsWith(run)) {
                    throw new EvolutionExecutionException("Test evidence exceeds its bounded invocation or location");
                }
                var parser = factory.newDocumentBuilder();
                parser.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                    @Override public void fatalError(org.xml.sax.SAXParseException failure) throws org.xml.sax.SAXException {
                        throw failure;
                    }
                });
                var suite = parser.parse(report.toFile()).getDocumentElement();
                long tests = Long.parseLong(suite.getAttribute("tests"));
                long skipped = Long.parseLong(suite.getAttribute("skipped"));
                long failed = Long.parseLong(suite.getAttribute("failures")) + Long.parseLong(suite.getAttribute("errors"));
                if (!suite.getTagName().equals("testsuite") || tests < 0 || skipped < 0 || skipped > tests || failed < 0) {
                    throw new EvolutionExecutionException("Invalid Maven test evidence");
                }
                executed += tests - skipped;
                failures += failed;
                var cases = suite.getElementsByTagName("testcase");
                for (int index = 0; index < cases.getLength(); index++) {
                    var test = (org.w3c.dom.Element) cases.item(index);
                    if (test.getElementsByTagName("skipped").getLength() == 0
                            && test.getElementsByTagName("failure").getLength() == 0
                            && test.getElementsByTagName("error").getLength() == 0) {
                        passedTests.add(test.getAttribute("classname") + "#" + test.getAttribute("name"));
                    }
                }
            }
        } catch (IOException | javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException | NumberFormatException failure) {
            throw new WorkspaceException("Cannot read bounded Maven test evidence", failure);
        }
        return new TestEvidence(executed, failures, Set.copyOf(passedTests));
    }

    private WorkspaceCommandRunner.Result command(Path directory, List<String> command, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new EvolutionExecutionException("Verification invocation time budget exhausted");
        }
        Duration timeout = Duration.ofNanos(Math.min(remaining, budgets.actionTimeout().toNanos()));
        return WorkspaceCommandRunner.run(directory, command, timeout, budgets.maxOutputBytes());
    }

    private void requireSuccess(WorkspaceCommandRunner.Result result, String operation) {
        if (!result.succeeded()) {
            throw new EvolutionExecutionException(operation + " failed (exit " + result.exitCode() + ")");
        }
    }

    private static boolean windows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"); }

    private void removeRun(Path copy, Path run) {
        if (!run.toAbsolutePath().normalize().getParent().equals(copy.resolve(".git").toAbsolutePath().normalize())
                || !run.getFileName().toString().startsWith("delveforge-verification-")) {
            throw new WorkspaceException("Verification cleanup ownership mismatch");
        }
        try (var paths = Files.walk(run)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!Files.isSymbolicLink(path) && !path.toRealPath().startsWith(run)) {
                    throw new WorkspaceException("Verification cleanup cannot cross filesystem links");
                }
                // Windows Git pack 文件带只读属性；只处理本次目录内的普通文件，不跟随链接。
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && Files.getFileStore(path).supportsFileAttributeView("dos")) {
                    Files.setAttribute(path, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS);
                }
                Files.delete(path);
            }
        } catch (IOException failure) {
            throw new WorkspaceException("Cannot remove invocation verification artifacts", failure);
        }
    }
}
