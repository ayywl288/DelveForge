# Repository compatibility and automatic preparation

Status: **READY_FOR_FIRST_REAL_EVOLUTION** at implementation level. Both c0b48da review findings are repaired and verified; the new commit is ready for independent re-review.

Branch: `codex/repository-compatibility`; worktree: `E:\develop\DelveForge-worktrees\repository-compatibility`.
Base: `b174bf0996b190de5ca784309fec5185ef7b8ca0`. No merge/push or real user database migration.
All paid Provider gates remain closed. Preparation never confirms or starts a Step.

## Review remediation — 2026-10-24

Reviewed version: `c0b48daeb1fcb8396381eae5c64b74b29090108e`, verdict CHANGES_REQUIRED.
This repair changes only Infrastructure selection/preparation/provenance code, their two regression test
classes, and this report. No Domain, persistence, API/UI, Docker image contents, dependencies or authorization
semantics changed. The original 65-file list below is cumulative relative to main, not this repair's diff.

- **Controlled JDK fallback:** preserve the existing automatic preference while it is configured and meets
  both the Java language level and minimum build JVM. Otherwise select an available supported 17/21 image
  satisfying both requirements. Explicit incompatible choices still fail; no qualifying image still fails.
  Regressions cover default 17 with Java 18/19/20 and both images, explicit incompatible 17, a missing
  recommended 17 image with available 21, and no suitable configured image.
- **Literal directory ignore metadata:** query the actual generated directory with a trailing slash, rather
  than a dot-file probe. Escape Gitignore wildcard/bracket/backslash characters in anchored directory rules;
  the existing provenance verifier uses the same rule construction. Actual Git preparation regressions
  cover `**/.*`, `service[1]`, reversed brackets and a module name containing spaces/`!`/`#`. They check
  regenerated ordinary output is ignored, unrelated `service1/target/customer.json` is not ignored or
  changed, source snapshots/HEAD stay unchanged, and a recreated adapter accepts the recorded provenance.

Before changing production code, the nine new regression invocations produced four assertion failures,
four JDK selection errors and one expected no-suitable-image pass (`data/compatibility/review-fixes-red.log`).
The three Java 18/19/20 failures and both reported ignore scenarios were reproduced through the actual
selector and production Git preparation code. No old assertion was weakened or test disabled.

Fresh focused repair verification passed: 36 total, **34 executed / 2 skipped**, zero failures/errors
(`data/compatibility/review-fixes-focused.log`). This includes all ten MavenJdkSelectorTest invocations,
eighteen executed compatibility Git invocations (including all four new ignore cases), PreparedCandidateDeliveryTest
and five provisioning diagnostics cases. The two skipped compatibility invocations require the separate
FinTrack/public-clone properties; they were not enabled in this repair run.

The complete deterministic backend **`mvnw.cmd -o verify` passed** at 2026-10-24 22:52:53 +08:00,
12:56 elapsed, with all four modules successful and the application JAR repackaged
(`data/compatibility/review-fixes-verify.log`). All 159 XML reports were written after this invocation began;
these totals come from this single run, not combined targeted runs.

| Module | Total | Actually executed | Skipped | Failures / errors |
| --- | ---: | ---: | ---: | ---: |
| Domain | 205 | 205 | 0 | 0 / 0 |
| Application | 764 | 764 | 0 | 0 / 0 |
| Infrastructure | 439 | 411 | 28 | 0 / 0 |
| App | 209 | 206 | 3 | 0 / 0 |
| **Total** | **1617** | **1586** | **31** | **0 / 0** |

The 31 skipped invocations are not passing evidence:

- Four paid-model cases: CodingAgentLiveTest, SpringAiAgentModelLiveTest, AutonomousEvolutionLiveTest,
  RealRepositoryEvolutionLiveTest (one each).
- Twenty-four optional actual-Docker cases: DockerAgentSandboxTest (12), DockerMavenCompatibilityTest (2),
  DockerProxyIsolationTest (1), DockerRetentionRecoveryTest (4), DockerSandboxProtocolTest (2),
  PublicRepositoryBaselineLiveTest (1), RepositoryPreparationDockerTest (2).
- Three public-clone property cases: RepositoryCompatibilityIntegrationTest (2),
  WorkingCopyActivationApiIntegrationTest (1). These require separately configured FinTrack/public clone
  locations and were not enabled; the other normal API preparation/authorization/provenance tests ran.

Paid gates DELVEFORGE_AGENT_E2E, DELVEFORGE_AGENT_LIVE, DELVEFORGE_TASK2_E2E, DELVEFORGE_REAL_STEP and Docker gates
DELVEFORGE_AGENT_DOCKER, DELVEFORGE_REPOSITORY_COMPATIBILITY_DOCKER, DELVEFORGE_AGENT_COMPATIBILITY,
DELVEFORGE_REAL_BASELINE were explicitly false; DELVEFORGE_SANDBOX_PROTOCOL_IMAGE and
DELVEFORGE_SANDBOX_LEGACY_IMAGE were unset. No public-clone system properties were supplied.
The fresh XML archive is `data/compatibility/review-fixes-verification-xml/` and its separate totals are in
`review-fixes-verification-summary.json`; original acceptance logs and JSON records are retained.

The retained c0b48da frontend/Python/PowerShell/actual-Docker evidence below is historical; this repair does
not claim those suites or the real FinTrack Docker baseline were rerun. No task-specific unresolved defect
is known after these checks. Independent re-review remains the requested next step; no merge, push, paid
Provider call, real database migration or original-repository modification was performed.

Focused reproduction with the above gates closed (no public-clone properties):

```powershell
.\mvnw.cmd -o -pl backend/delveforge-infrastructure -am "-Dtest=MavenJdkSelectorTest,RepositoryCompatibilityIntegrationTest,PreparedCandidateDeliveryTest,WorkingCopyProvisioningDiagnosticsTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -o verify
```

## Restriction audit and disposition

| Area inspected at base | Restriction / responsibility | Disposition in this change |
| --- | --- | --- |
| Analysis extraction and deterministic secret policy (ADR-0006) | Analysis reads committed Git evidence; bounded text selection is not a complete executable source bundle | Preserve analysis limits and secret policy. Compatibility independently inspects the same pinned A. Binary resources are not sent as model text. |
| WorkingCopy provisioning and activation policy | All initial revisions were required to equal analyzed A | SAFE_TO_AUTOMATE: Domain distinguishes original A and trusted prepared P; preserve INV-W09 source=A. Activation persists provenance atomically. |
| Agent baseline / sandbox.py | Blanket tracked `target` rejection, per-edit-file limit applied to every baseline blob, 16 MiB total | SUPPORT_WITH_BOUNDED_ADAPTATION: prove each generated entry; preserve immutable resources up to 16 MiB/file, 128 MiB/tree, 10000 files. Editable text still 256 KiB. |
| Git paths / modes / attributes | Unsafe paths, case collisions, symlinks, gitlinks, external content transformations | GENUINELY_UNSUPPORTED under current Workspace/Delivery policy; report before allocation. Preserve 100644/100755, CRLF/LF, exact blob identities. Protected credential paths remain blocked. |
| Maven roots / JDK | Root/nested discovery and language/JVM inference could diverge; target-named modules were rejected | SUPPORT_WITH_BOUNDED_ADAPTATION: one shared static reactor parser; explicit module edges, local parent properties, supported language 8–21 on controlled JVM 17/21. Root/nested entry is automatic. |
| Maven prerequisites | Arbitrary Wrapper, private credentials, external services/native code/toolchains cannot be assumed available | REQUIRES_EXPLICIT_USER_DECISION: ambiguous roots/profiles, extensions/toolchains, local systemPath and entry/JDK-altering Maven config. Additional repository declarations receive a diagnostic; network stays Central-only. Service/native prerequisites may first become visible in actual checks. |
| Generated artifacts | A directory called target does not prove all contents disposable | SAFE_TO_AUTOMATE only for recognized class ownership + tracked Java source, byte-identical copied resources, compiler lists tied to tracked inputs/outputs, or test reports tied to tracked test classes. Ambiguous target content is preserved and blocks preparation. |
| Credentials in business configuration | FinTrack has a credential-bearing application.properties | Preserve byte-for-byte and mark immutable. Existing sanitizer applies to model-visible reads/events/results. Never rewrite JWT settings or infer license/usage permission. |
| Candidate / Delivery | Frozen diff/tree/snapshot integrity, native no-replace boundary, Git/SQLite acceptance | Preserve deterministic replay and write boundary. First Step baseline is P; final C has parent P and tree B. Legacy identity preparation remains A=P. |
| API/UI and tests | Compatibility must be part of normal preparation, not a hidden cleanup command | Thin read API and Plan preparation panel expose pinned status/actions/constraints/A/P. Normal Prepare performs policy automatically and leaves every Step pending. |
| Resource/time budgets | Small tree can hide a large Git history; clone must not run without bounds | 512 MiB Git object storage budget, no alternate stores, bounded Git output and 45-second commands; single-branch independent clone. Timed-out process trees use existing owned-process termination. |

These categories describe current bounded support, not universal GitHub compatibility. Unknown Maven/plugin prerequisites are not silently declared build-ready.

## A / P / B / C contract

- A is `RepositoryProfile.analyzedRevision` and `WorkingCopy.sourceRevision`. Profile/Plan evidence remains A.
- No preparation: P=A, policy IDENTITY, empty changed paths. Otherwise policy `maven-generated-v1` creates deterministic P with sole parent A in a newly owned independent clone.
- Only proven generated entries are deleted. If needed, append anchored `/<actual-module>/target/` patterns to root `.gitignore`, preserving existing bytes/EOL and mode. Ignore decisions disable external/global excludes and use fixed Git case semantics. A module named target and fixture POM/target resources remain source.
- Domain records immutable original/prepared revisions, policy version, exact removed paths and metadata paths. READY starts with current=lastVerified=P. This means trusted preparation, not a successful build/test result.
- SQLite V26 adds nullable prepared_revision/preparation_json and immutable consistency triggers. Null/null legacy rows mean P=A. No UPDATE/backfill touches occupied or historical rows; V22–V25 checksums are retained.
- Normal lifecycle commit inserts READY metadata and activates the Plan in one transaction with existing authorization/Profile/CAS checks. Failure compensates only this invocation's owned clone; unknown/pre-existing directories are never adopted. Retries use new identities; concurrent allocations remain independent.
- Confirmation, execution preparation and Delivery reload provenance and check the Git receipt, sole parent, exact deletion set, allowed metadata suffix, current tree and isolation. Receipt absence/mismatch does not fall back to identity.
- Agent mutates only its Docker sandbox. Runtime explicitly checked Maven roots may extend build roles, but baseline paths remain source regardless of a directory role change. Only real module target report files outside the baseline are cleared/counted. Capture returns hash/mode for unchanged files and bounded content for changed text. Baseline plus frozen Diff reconstructs B exactly, including opaque resources. First Delivery creates C with parent P/tree B and advances Git/SQLite through existing INTENT/CAS/native mutation safeguards.

Design ownership, alternatives and migration rationale are recorded in [ADR-0014](../decisions/0014-repository-compatibility-and-prepared-baseline.md).

## Fresh FinTrack acceptance

Source: [sinchana14/personal-finance-tracker](https://github.com/sinchana14/personal-finance-tracker), untouched normal clone in `data/compatibility/fintrack-source`.

| Fact | Evidence |
| --- | --- |
| A | `0e7f0bce854dee6f4d43582f8315bb45cec369eb` |
| Deterministic P | `bc3b9f1fff6fb0591243f86f08cbc1dd49c640f0`, sole parent A |
| Preparation | 53 individually proven tracked backend target entries removed; 49 entries retained; only necessary root `.gitignore` metadata added |
| Source/config integrity | Remaining paths retain exact Git blob/mode identities; credential-bearing backend application.properties is immutable; original HEAD, files, Git bytes and worktree state unchanged |
| Build discovery | nested entry `backend`, controlled JDK 17; no host Wrapper/Maven/project execution |
| Actual Docker/Maven baseline | `BUILD_PASSED_NOT_TESTED`, exit 0, **0 executed tests**. No positive test or business-function claim. |
| Docker identity | JDK17 image `sha256:50a8f5da035453cd9c28eabfb694325f7d79c58ed73c595aa942a2d95685d73f`; JDK21 `sha256:f48ef939224c4d3ddf7a048f1554e1a4e74660fa81d53e4058b4eda824bf1c03` |
| Isolation observed | network none, read-only root, non-root 11000:11000, 2 GiB, 2 CPUs, 256 PIDs; online dependency preparation remains on existing controlled Central proxy path |
| Model calls | **0**; user performs the first real product evolution later |

Static/preparation evidence: `data/compatibility/fintrack-preparation.json`. Actual baseline: `fintrack-docker-baseline.json`.
Normal MockMvc → Application/Domain → real Git → real SQLite activation passed in `compatibility-integration-7.log`;
its disposable fixture explicitly grants validation permission, never alters or infers real asset licensing.
It checks API status, P parent, persisted/reloaded A/P/provenance, Profile A, pending Step and no additional AI calls.
The API evidence file is `fintrack-api-provenance.json` and matches the actual stored/reloaded values.

FinTrack entered production sandbox preparation without the former generated-output rejection. Its zero-test repository still needs appropriate tests and business acceptance during the user's actual evolution.

## Compatibility matrix

| Case | Concrete coverage / current result |
| --- | --- |
| Clean nested Maven | Identity P=A; nested entry, binary resource bytes preserved, real Git provisioning |
| Reactor / module called target | Static module graph accepted, language/JVM compatibility; declared target module remains source |
| Binary/large resources | Real Docker fixture carries three 6 MiB opaque files (18 MiB total), changes only Counter.java and executes one real JUnit test; Candidate baseline P and unchanged resource tree identities checked |
| P→B→C | Real Git/SQLite prepared Candidate delivery, service re-creation and idempotent repeat; actual Docker Candidate delivery also passed in the final full invocation |
| Existing tests / zero tests | Synthetic Counter JUnit positive result distinct from real FinTrack BUILD_PASSED_NOT_TESTED |
| CRLF / modes | Exact CRLF resource preservation; executable 100755 Git blob/mode identity; existing native Delivery/EOL regressions retained |
| Ambiguous target / report lookalike | Business JSON and `<testsuite/>` lookalike block without deletion; only actual test-source-linked report qualifies |
| Unsafe entries / credentials | Symbolic mode 120000, submodule 160000, case-conflicting directories, protected credential paths and immutable credential-bearing text |
| Budgets | Single file above 16 MiB, tree above 128 MiB and >10000 entries rejected from Git metadata before materialization; unchanged resources above former 16 MiB total allowed |
| Failure / restart / retry / concurrency | Existing activation failure/CAS compensation, missing preparation receipt rejection after service re-creation, refusal to adopt existing directory, two concurrent independent prepares with same deterministic P |
| Historical persistence | V25→V26 occupied legacy copy unchanged, P=A fallback, immutable provenance trigger; existing frozen Candidate, Delivery, crash-cut and SQLite integrity regressions |
| Petclinic public clone | `spring-projects/spring-petclinic@500158f732419217507c7656904b8e6aa1bcc0d6`: COMPATIBLE static report, root Maven/JDK17, 132 files/1,405,783 bytes/13 opaque resources; no host build or actual Maven acceptance claimed |
| Multi-module public tutorial | `spring-guides/gs-multi-module@d88a2b721bda3798a6a934987157498e66da06c5`: BLOCKED with MAVEN_ROOT_AMBIGUOUS (initial/complete independent projects); no arbitrary root chosen |

Additional public-clone reports: `data/compatibility/public-repository-matrix.json`. These are independent untouched clones; no per-repository source special cases exist.

## Original implementation verification (c0b48da; historical)

The final **single complete** Maven Wrapper invocation passed at 2026-10-24 21:59:50 +08:00
(`backend-verify-complete.log`, 27:09). All four modules passed and the final application JAR was repackaged.
The 159 Surefire XML reports were all freshly written during this invocation; earlier failed XML was not
combined into these totals.

| Module | XML reports | Total | Actually executed | Skipped | Failures / errors |
| --- | ---: | ---: | ---: | ---: | ---: |
| Domain | 12 | 205 | 205 | 0 | 0 / 0 |
| Application | 66 | 764 | 764 | 0 | 0 / 0 |
| Infrastructure | 49 | 430 | 425 | 5 | 0 / 0 |
| App | 32 | 209 | 207 | 2 | 0 / 0 |
| **Total** | **159** | **1608** | **1601** | **7** | **0 / 0** |

The seven skips are explicit gates, not passing tests: CodingAgentLiveTest (one),
SpringAiAgentModelLiveTest (one), AutonomousEvolutionLiveTest (one), RealRepositoryEvolutionLiveTest (one),
DockerMavenCompatibilityTest (two), and PublicRepositoryBaselineLiveTest (one). The four paid-model gates
were explicitly false; the three older optional Docker/public-baseline cases were not enabled. The new
RepositoryPreparationDockerTest (two), DockerAgentSandboxTest (twelve), DockerSandboxProtocolTest (twelve,
including the actual preserved v1 negative case), Docker proxy isolation, retention/recovery, all sixteen
Git compatibility cases and normal FinTrack API activation **did execute** in this same full invocation.
The public clone compatibility matrix is static inspection; it is not the skipped public-baseline build.

The actual Docker opaque-resource Candidate acceptance also completed formal Git/SQLite Delivery:

```text
A = 8a0d33c5f34ce3e49fa007ee9c266fe2a9614e27
P = 59e06d1af0aecb94f9465ff24fc9bb473f30419e
B = cb994f50cca98dba449c1073dfb3eba8ebd2872f  (Git tree)
C = 723369e3d368a3e15b3096ed01c6fb5d412580dd
parent(P) = A; parent(C) = P; tree(C) = B
```

`opaque-candidate-evidence.json` records 18,874,368 unchanged opaque resource bytes, one actual positive
JUnit test and a Diff changing only `src/main/java/example/Counter.java`. This is a deterministic fixture
using actual Docker, Maven, Git, SQLite and Delivery; its Step authorization is fixture data and it makes
no paid-model or full FinTrack business-evolution claim.

Already executed successfully:

- Frontend final source: 247/247 tests, `pnpm typecheck`, `pnpm build`; no lint script exists. Logs `frontend-tests-final.log`, `frontend-typecheck-final.log`, `frontend-build-final.log`.
- Python final policy + actual current-image protocol matrix: 34/34, no skips (`python-protocol-complete.log`).
- Launcher: PowerShell 7 and 5.1 each 46/46 (`launcher-pwsh.log`, `launcher-ps51.log`).
- Actual Docker preparation/baseline/Candidate/Delivery: both expanded RepositoryPreparationDockerTest cases passed in the final full invocation. The earlier `docker-prepared-3.log` predates formal Delivery coverage and is retained only as development history.
- Focused corrected process-diagnostics matrix: 5/5 (`diagnostics-5.log`).
- Final focused integration: 40/40 (28 Infrastructure + 12 App), including real Docker directory/report behavior, all 16 compatibility Git cases, JDK metadata budget, normal API/SQLite preparation with untouched FinTrack, and structured 409 rejection (`compatibility-integration-7.log`).

Earlier attempts are not successes: first frontend run 246/247 (component selector collision); Python case fixture errored after reactor resolver addition; initial Docker run failed test evidence serialization/Git fixture EOL assumptions; next Docker run exposed Windows quoted JSON environment transport and failed before initialization. Module metadata now uses Base64 JSON. The corrected Docker third run passed. `backend-verify-1.log` failed five legacy mocked-process diagnostics cases; corrected focused checks retained all failure/cleanup/secret assertions. `compatibility-integration-4.log` passed the new Git/public-repo/delivery/migration tests but still failed two old success-path process mocks before `diagnostics-5.log` fixed their builder/config fixtures. The next broad run `backend-verify-final.log` executed all three lower modules but failed one Docker case: an explicitly checked newly added independent Maven directory's generated output was wrongly counted as source mutation; App was not run. It also freshly passed the actual Docker Candidate→Delivery A/P/B/C test, protocol compatibility, proxy isolation and restart/retention cases. The new-directory defect was fixed, with baseline file identities carried independently of module roles. Static report fixtures are now neither deleted nor counted as executed tests. The focused final integration and then the final complete backend invocation passed after those fixes. Earlier failed invocations remain failures; their counts are not included in the final full-pass totals.

Reproduction from this worktree (PowerShell; no paid gates). The retained evidence directory contains
untouched clones `fintrack-source`, `spring-petclinic` and `spring-multi-module` at the revisions above.
For a fresh checkout, clone the three linked public repositories into those directory names and select
those pinned revisions before running the tests. This is validation-fixture setup, not product-user Git
cleanup or a source preparation commit. Install frontend dependencies using the existing lockfile and
use the repository's Python/PowerShell test instructions for those separate suites. The `-o` backend
invocation assumes the DelveForge Maven dependencies are already cached; an initial dependency restore
for DelveForge itself may require Maven network access.

```powershell
. ./setup-agent-images.ps1
$configuration = @{}
Initialize-DfAgentImages -Root $PWD.Path -Configuration $configuration
$env:DELVEFORGE_AGENT_IMAGE_17 = $configuration.DELVEFORGE_AGENT_IMAGE_17
$env:DELVEFORGE_AGENT_IMAGE_21 = $configuration.DELVEFORGE_AGENT_IMAGE_21
$env:DELVEFORGE_SANDBOX_PROTOCOL_IMAGE = $configuration.DELVEFORGE_AGENT_IMAGE_21
$env:DELVEFORGE_SANDBOX_LEGACY_IMAGE = 'sha256:80e12e57cbf9f23698fe6629a1a7a4634a521bd424777eee6563d6ac7c3c1cbe'
$env:DELVEFORGE_AGENT_DOCKER = 'true'
$env:DELVEFORGE_REPOSITORY_COMPATIBILITY_DOCKER = 'true'
$env:DELVEFORGE_AGENT_E2E = 'false'
$env:DELVEFORGE_AGENT_LIVE = 'false'
$env:DELVEFORGE_TASK2_E2E = 'false'
$env:DELVEFORGE_REAL_STEP = 'false'
.\mvnw.cmd -o "-Ddelveforge.compatibility.fintrack=$PWD/data/compatibility/fintrack-source" "-Ddelveforge.compatibility.evidence=$PWD/data/compatibility" verify
```

Images must come from current launcher fingerprint/content checks, not an overwritten historical tag. The legacy-protocol negative gate also needs an explicitly selected old image; full verification uses the preserved v1 image ID recorded in its command/log. A missing gate is skipped evidence, not a pass.

## Remaining limits and migration considerations

- Unsupported Git modes/unsafe paths/attributes, protected credential-path files, alternate object stores and over-budget repositories remain blocked. The existing strict `.env*` path boundary is retained.
- Ambiguous Maven roots/profiles, extensions/toolchains, local dependencies or entry/JDK-changing config require an explicit build/environment decision; this change does not add a general build configurator.
- Private repositories, credentials, native tools and external services are not automatically provisioned. Extra repository declarations do not expand egress; actual Maven results may expose further prerequisites.
- Static compatibility never promises compilation, positive tests or business correctness. Test selectors, missing/stale/malformed reports and NOT_TESTED retain existing evidence semantics. No test skipping, dependency rewrite, widened network or security policy relaxation was introduced.
- Legacy non-Maven Workspace provisioning remains identity-compatible for existing generic lifecycle callers; the Coding Agent still requires Maven and does not gain arbitrary build support.
- Legacy preparation means P=A and retains all historical meanings. A prepared copy cannot be silently adopted from arbitrary disk state. Interrupted/uncommitted clone directories remain untrusted; there is no new recovery coordinator that deletes or adopts them.
- V26 is tested only on disposable databases. Before upgrading actual data, follow AUTONOMOUS_AGENT.md backup/quiescence instructions. Real database/WorkingCopy paths, main's untracked design/grilling files, prior evidence archives, existing Docker images/resources and credentials were preserved.
- Runtime protocol is now v2 because final snapshots use unchanged-file hash/mode metadata. Existing v1 images are retained and rejected by the new consumer; launcher automatically builds/verifies current fingerprints.

## Exact changed files

65 files across the four existing backend modules, frontend, launcher/tests and documentation; no new dependency or Maven module.

- `README.md`
- `backend/delveforge-app/src/main/java/com/ayywl/delveforge/app/api/evolutionplan/WorkingCopyController.java`
- `backend/delveforge-app/src/main/java/com/ayywl/delveforge/app/api/project/ProjectWorkspaceResponse.java`
- `backend/delveforge-app/src/main/java/com/ayywl/delveforge/app/api/softwareasset/RepositoryCompatibilityController.java`
- `backend/delveforge-app/src/main/java/com/ayywl/delveforge/app/config/RepositoryCompatibilityConfiguration.java`
- `backend/delveforge-app/src/main/java/com/ayywl/delveforge/app/error/ApiExceptionHandler.java`
- `backend/delveforge-app/src/main/resources/application.yml`
- `backend/delveforge-app/src/test/java/com/ayywl/delveforge/app/api/evolutionplan/EvolutionPlanningApiIntegrationTest.java`
- `backend/delveforge-app/src/test/java/com/ayywl/delveforge/app/api/evolutionplan/WorkingCopyActivationApiIntegrationTest.java`
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/agent/AgentPolicyRejection.java`
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/evolution/preparation/ExecutionPreparationState.java`
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/evolution/provisioning/PrepareEvolutionPlanUseCase.java`
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/port/workspace/PreparedWorkspace.java`
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/port/workspace/RepositoryCompatibility.java`
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/port/workspace/RepositoryCompatibilityPort.java`
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/port/workspace/WorkingCopyInspectionPort.java`
- `backend/delveforge-application/src/main/java/com/ayywl/delveforge/application/repositoryanalysis/compatibility/GetRepositoryCompatibilityUseCase.java`
- `backend/delveforge-domain/src/main/java/com/ayywl/delveforge/domain/evolution/PlanActivationPolicy.java`
- `backend/delveforge-domain/src/main/java/com/ayywl/delveforge/domain/evolution/PreparationProvenance.java`
- `backend/delveforge-domain/src/main/java/com/ayywl/delveforge/domain/evolution/WorkingCopy.java`
- `backend/delveforge-domain/src/test/java/com/ayywl/delveforge/domain/evolution/PreparedBaselineTest.java`
- `backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/agent/DockerAgentSandbox.java`
- `backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/agent/MavenJdkSelector.java`
- `backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/persistence/evolutionplan/SqliteEvolutionLifecycleCommitAdapter.java`
- `backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/persistence/workingcopy/PreparationProvenanceJson.java`
- `backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/persistence/workingcopy/SqliteWorkingCopyRepository.java`
- `backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/persistence/workingcopy/WorkingCopyDO.java`
- `backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/workspace/CompatibilityGit.java`
- `backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/workspace/GitRepositoryCompatibility.java`
- `backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/workspace/GitRepositoryPreparation.java`
- `backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/workspace/GitWorkspaceAdapter.java`
- `backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/workspace/MavenRepositoryLayout.java`
- `backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/workspace/WorkspaceCommandRunner.java`
- `backend/delveforge-infrastructure/src/main/resources/agent/sandbox.py`
- `backend/delveforge-infrastructure/src/main/resources/db/migration/V26__working_copy_preparation_provenance.sql`
- `backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/agent/CandidateDeliveryTest.java`
- `backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/agent/CodingAgentServiceTest.java`
- `backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/agent/DeliveryMigrationTest.java`
- `backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/agent/DockerAgentSandboxTest.java`
- `backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/agent/DockerRetentionRecoveryTest.java`
- `backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/agent/DockerSandboxProtocolTest.java`
- `backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/agent/MavenJdkSelectorTest.java`
- `backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/agent/PreparedCandidateDeliveryTest.java`
- `backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/agent/RepositoryPreparationDockerTest.java`
- `backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/agent/SqliteAgentStoreTest.java`
- `backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/persistence/project/EvolutionFlowScopeMigrationTest.java`
- `backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/persistence/project/ProjectContextPersistenceTest.java`
- `backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/workspace/RepositoryCompatibilityIntegrationTest.java`
- `backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/workspace/WorkingCopyProvisioningDiagnosticsTest.java`
- `backend/delveforge-infrastructure/src/test/python/test_sandbox_image_protocol.py`
- `backend/delveforge-infrastructure/src/test/python/test_sandbox_policy.py`
- `docs/ARCHITECTURE.md`
- `docs/AUTONOMOUS_AGENT.md`
- `docs/DOMAIN_MODEL.md`
- `docs/PRODUCT.md`
- `docs/ROADMAP.md`
- `docs/decisions/0014-repository-compatibility-and-prepared-baseline.md`
- `docs/validation/repository-compatibility-and-preparation.md`
- `frontend/src/api/client.ts`
- `frontend/src/api/types.ts`
- `frontend/src/components/RepositoryCompatibility.vue`
- `frontend/src/views/PlanView.vue`
- `frontend/tests/repository-compatibility.test.ts`
- `setup-agent-images.ps1`
- `tests/setup-delveforge.Tests.ps1`
