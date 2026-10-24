# Autonomous evolution: real Step and Docker bootstrap stabilization

Date: 2026-10-24, Asia/Shanghai. This record separates live execution, domain fixtures,
deterministic regressions, and installation verification. Evidence is fresh for this task.

## Workspace and implementation

- Starting DelveForge: `8c87114a76c9ca270dba1f5b94834c2b60cc71b5`, local `main`.
- Task branch: `codex/real-e2e-bootstrap`.
- Final implementation revision: `d45ea19aef461257e7588632ab0e162195871bc8`.
  The handoff also includes the documentation-only descendant containing this report; runtime code is unchanged.
- Isolated worktree: `C:\Users\28226\.codex\worktrees\real-e2e-bootstrap\DelveForge`.
- Git author checked: `ayywl288`, `249746591+ayywl288@users.noreply.github.com`.
- Main remained at its starting revision, ahead of origin by the same 13 commits. Existing
  `frontend/design/` and `grilling/` remained untracked in the original checkout. No merge or push.

The implementation changes the Windows launcher, image defaults and setup documentation.
`setup-agent-images.ps1` checks reachable Linux Docker API >=1.48, hashes trusted build-context
file names/bytes and the JDK 17/21 BASE_IMAGE arguments, and builds only absent source identities.
It checks actual installed `sandbox.py`, `proxy.py`, `offline-settings.xml`, JDK and protocol v1
using a container running the resolved image ID. Only that verified ID is passed to the backend.
The existing Java handshake remains mandatory before source initialization or model calls.

Repeated startup probes and reuses matching images. Changed inputs select new tags. An existing
incompatible identity is rejected rather than overwritten. Advanced `-AgentImage21/17` overrides
undergo the same probe. `-DisableAgent` explicitly permits discovery/planning startup without Docker.
Missing, unreachable, Windows or old Engines never silently enable Agent. Inherited application
overrides are still cleared; credentials reach only the backend through process environment.

No module dependency, provider strategy, persistence schema, Agent safety policy or Delivery
invariant changed. The existing trusted Dockerfile and tool scripts were not weakened or edited.
Regression coverage and opt-in live/independent verification scripts are committed with the change.

## Evidence location and runtime identity

All experiment records below are under ignored `data/real-e2e/` in the task worktree. Build tools
also use their normal ignored `target/` and frontend output directories. No real user database or
existing managed WorkingCopy was opened. Fresh databases ran Flyway V1–V25 normally.

The accepted live application was a separate `java -jar` process, not a test double or an in-process
replacement Agent. A short-lived Spring context first saved valid M1–M3 domain fixtures through
repository ports and registered the asset over HTTP, then closed before the production JAR opened
that isolated database. Preparation, explicit confirmation and Agent start used production HTTP APIs.
Delivery was discovered automatically; the harness did not POST a manually constructed Delivery.

- Artifact: `backend/delveforge-app/target/delveforge-app-0.1.0-SNAPSHOT.jar`.
- Accepted live JAR SHA-256: `d0d971a69c89023a31bd7fa21f056ff43d30b25be2e07846adf39d06b5062f1c`.
- Final repackaged JAR SHA-256: `a110ce6451bcce680a60f150e2a3c28d494f2cdfdebb4636584ad6cd4bbc0aa2`.
  Production source did not change between the live execution and final packaging. The latter artifact
  was built successfully after regression verification; an additional paid Step was not run on its exact bytes.
- Host Java: Oracle JDK `21.0.12.1+1-LTS-4`.
- Actual packaged libraries inspected: Spring AI DeepSeek/model/commons/retry **1.1.8**, JNA **5.17.0**.
- Model: configured real DeepSeek `deepseek-flash`, `https://api.deepseek.com`.
- Agent response limit: **8192**; no increase or new model retry was introduced.
- Agent limits: normal count telemetry defaults, wall 1800 seconds; check limit explicitly 600 seconds.
- Backend: loopback, port **58180** for accepted attempt 4; frontend regression uses the Vite toolchain.
- Per-attempt database/root: `attempt-<UUID>/application.db`, `working-copies/`, `runs/`, `logs/`.
- Experiment owner: `df-real-baseline-65df694b-6618-4025-942c-02d46f22e356`.
- Experiment-only Maven cache: `df-real-e2e-20261024-01a16c9c-cache`.

No raw Provider response, prompt, tool argument patch or key was saved to the validation report.
Persisted activity contains the production safe metadata. Candidate Diff is retained only in the
isolated database/ignored audit directory. The source repository is public. A text-artifact scan
found no credential-shaped `sk-` values; this is a limited pattern check, not a universal secret scan.

## Docker auto-bootstrap

Actual Engine: Docker Desktop **4.69.0**, Linux/amd64 Engine **29.4.0**, API **1.54**.
Protocol handshake: `delveforge-sandbox`, integral version **1**.

Final default source identities, including BASE_IMAGE arguments:

| JDK | Verified fixed image ID |
| --- | --- |
| 21 | `sha256:2ed10cbaf65143324c401b3c1f172d2efe968ff04ce91eda10c9dcb850dfeaec` |
| 17 | `sha256:80e12e57cbf9f23698fe6629a1a7a4634a521bd424777eee6563d6ac7c3c1cbe` |

`final-bootstrap-cold.log` records both absent identities being built and probed;
`final-bootstrap-cached.log` records both being reused with identical verified IDs.
`final-images.json` contains the exact environment selections. Earlier live attempt 4 used the
same current tool contents, before the fingerprint was extended to include BASE_IMAGE arguments:
JDK 17 `sha256:a771c64cc0ee835a4887b2bfc98f7e862f097f66abba59d2e83a6139608a8716`;
its JDK 21 peer was `sha256:52859b31e2cedb6388890ae9b9e4e6faf759a21b85cf6dbb388093b08f2cf5dc`.
The actual SANDBOX_READY event proves the JAR received and used the selected JDK 17 fixed ID.

An additional fresh-clone-like probe copied the trusted context into an isolated directory and
added only an inert Dockerfile comment. Both derived tags were checked absent before building;
both then built and cached successfully, recorded in `fresh-bootstrap.json` and its cold/cached logs.
This also exercises changed build inputs against real Docker. Existing base-layer caches were
retained; this is not a claim that Docker itself was freshly installed or every base layer downloaded.
Existing user/legacy images, containers, networks and volumes were not pruned, retagged or overwritten.

PowerShell 5.1 initially rejected the new helper's text encoding. The helper now preserves the
repository's UTF-8 BOM convention; its probe avoids native-command embedded double-quote ambiguity.
Final launcher regressions: **46 passed on PowerShell 7.6.2 and 46 passed on Windows PowerShell
5.1.22621.5624**. Actual cached image probes also passed on 5.1. Tests cover cold/cached selection,
changed inputs, stale contents, incompatible protocol/Engine, absent or unreachable Docker,
build/probe failure, advanced overrides, disabled Agent, no services after bootstrap failure,
cleared inherited overrides, fixed-ID propagation and caller environment restoration.

## Public baseline and frozen acceptance contract

- Fresh origin: `https://github.com/spring-guides/gs-rest-service.git`.
- Code license inspected at A: **Apache-2.0**, `LICENSE.txt`. Writing has a separate license file.
- Immutable A: `3f4cef01152596c19bc4d37939409358812b1421`.
- Tree(A): `10269cc2a76402a811f11190a99d1e85999a1020`.
- Clean checkout before execution; `pinned-baseline.json` records origin, SHA, tree and status.
- Actual nested project: `complete/pom.xml`, Spring Boot **4.0.8**, Java **17**.
- Baseline controller formatted the raw name; it did not normalize internal whitespace.
- Baseline controlled Docker/Maven check: **2 executed tests, 0 failures/errors/skips**.
  `baseline.json` records the real network=none, read-only, non-root JDK 17 sandbox identity/check.
  Baseline-only verification used the adapter to check unchanged upstream, never to implement the feature.

One confirmed EvolutionStep requests trimming ASCII whitespace, collapsing internal ASCII whitespace
runs to one space, falling back to World for omitted/empty/whitespace-only names, preserving ordinary
and Unicode names, JSON id/content and incrementing IDs. Scope is only related source/tests under
`complete/`; no dependency or build changes. Independent expected HTTP cases were authored separately
from the model. Once execution began, Codex did not write target feature source or create its final commit.

## Every preparation/live attempt

| Attempt | Isolated directory / Run | Actual outcome |
| --- | --- | --- |
| 1 | `attempt-0485f085-e2fc-404e-9698-449458fc70f4` | Fixture rejected with CURRENT_LIMITATIONS_NOT_FROM_BASE_PROFILE; fixture text corrected. No Agent/model call. |
| 2 | `attempt-04276ee4-eff1-423a-a779-e70c033c17bd`; Run `fbef02a8-dee4-4bf0-b77d-3933da0d0cc7` | FAILED / RESOURCE_OWNERSHIP, 0 returned model calls / 0 tools. Harness used a new owner against this experiment's existing cache. Reused the experiment's correct owner; no relabel or guard change. |
| 3 | `attempt-417e055b-753e-4633-9c91-180eaf08250d`; Run `caae372d-1ec2-441f-874b-03e90a45c2c7` | Real Candidate frozen after 8 returned model calls / 11 tools / 112715 reported tokens; 10 tests passed. Harness dereferenced an initially absent asynchronous Delivery and stopped the backend. Step remained READY at A, no Delivery row. Not a full E2E pass. |
| 4 | `attempt-43d94913-9637-48fb-ae73-73bc85fdedab`; Run `cf5601ed-655f-4048-88e2-673dec9d30f3` | Complete real Agent + automatic Delivery + independent acceptance PASS. Entirely new DB, WorkingCopy, Run and Docker source volume. |

Attempt 4 ran from **15:35:31 to 15:41:09** for Agent coding; automatic Delivery completed afterward.
It returned **11 model calls**, performed **14 tool calls**, and recorded **159110 Provider-reported
tokens** for returned Replies. MODEL_REQUEST/tool identities and real exploration/patch events are
persisted. The existing bounded completion-format correction occurred; no new blind retry was added.

Its first verify produced COMPILATION_FAILED with zero tests. The Agent independently read and
patched its test code, then executed complete Maven verify: **7 tests, 0 failures/errors/skips**.
The final Candidate records the first snapshot as stale and the later complete passing snapshot
as current. `agent-maven-reports/TEST-com.example.restservice.GreetingControllerTests.xml` was copied
read-only from the actual Agent container before cleanup. No test result was counted from a skipped
or dependency-preparation build.

The first independent verifier invocation exposed host Python 3.10's lack of tar extraction filters.
The verifier now checks ordinary relative files/directories before extraction. Only the independent
verification was repeated; it did not rerun, alter or replace the completed Agent/Candidate/Delivery.

## Accepted A / D / B / C and mandatory assertions

- Candidate: `ba03768f-d4b6-4f70-8e27-a7e35062a2ab`.
- Diff SHA-256: `96a569993720c05a204f8c72c814d142c115b677b78e783fc9906ffc1428ef3c`.
- B: `d6dfd65589ca22d05d69beb88d6723349cbabb1b`.
- C: `3bd68ad95e96b5aa037a82b3b535b63ed59417b5`.
- WorkingCopy: `ab01e472-e7d2-4281-a2ab-d77e73c46a1a`.
- Plan: `4bcb5057-f46e-4444-9253-55a389fc0804`; Step: `b2ee0c7d-49a0-4556-b2a6-8afee88be509`.
- Final checked source snapshot: `1b0826da2d3d09f26a30c0fc89c6072ebb59ac2735c4caa87470a42c29cd690b`.
- Changed paths: GreetingController.java and GreetingControllerTests.java under `complete/src/`.

`tests/real-greeting-acceptance.py` cross-checks the stopped application's durable database with Git,
then clones only into independent audit metadata and replays the actual stored Diff from A using
an independent index. It proves the resulting tree is exactly B. It does not manufacture a commit.
The existing automatic Delivery created C, with parent A and tree B.

| Required fact | Fresh evidence |
| --- | --- |
| Genuine model tool calls, exploration and patch | MODEL_REQUEST and actual TOOL_STARTED/FINISHED identities; PATCH_APPLIED; 11 returned model calls / 14 tools |
| Modification inside controlled Docker | SANDBOX_READY fixed image/container, JDK 17, network=none, readOnlyRoot, 2 GiB / 2 CPUs / 256 PIDs |
| Positive tests, current final snapshot | Actual XML: 7 executed, 0 failures/errors/skips; frozen check stale=false and snapshot equals finalSnapshot |
| finish and exact Diff replay A→B | Immutable Candidate row, verified Diff digest, independent bare repository/index replay |
| Durable successful Run referencing Candidate | agent_run SUCCEEDED, candidate_id matches agent_candidate.id/run_id |
| Automatic frozen Candidate Delivery | Durable INTENT→APPLIED→COMMIT_READY→PUBLISHED→COMPLETED events; no harness Delivery write |
| C parent/tree | Git objects independently prove parent(C)=A and tree(C)=B |
| SQLite final state | candidate_delivery COMPLETED; evolution_step SUCCEEDED; baseline A and delivery_revision C |
| WorkingCopy trusted/current state | current_revision=last_verified_revision=HEAD=C, clean status |
| Original unchanged | Original HEAD=A, original tree matches recorded tree(A), clean status after execution and independent audit |
| Independent feature behavior | New offline Docker Maven verify: 7/0/0/0; delivered application launched and all 13 independent HTTP cases passed, including all six ASCII whitespace characters |

The isolated database's `PRAGMA integrity_check` returned `ok`. Detailed safe metadata, events,
actual report XML and independent audit are in attempt 4's evidence.json and independent-acceptance.json.
Run/Candidate success was never substituted for Step/Delivery success.

## Verification

- Backend: **1557 executed / 24 gated skips / 0 final failures or errors**, across 155 suite reports.
  This is combined evidence from unchanged-source suites and the corrected protocol/application run,
  **not one uninterrupted passing full invocation**. XML and exact provenance are retained in
  `backend-junit-final/` and `backend-coverage.json`.
- First full verification exited 1 after its Maven parent JVM failed a native 32744-byte allocation.
  Its Delivery matrix had passed 29 tests; the native diagnostic reported 16 GiB system memory with
  about 1097 MiB physical memory free. This interrupted invocation is not reported as a full pass.
  The user identified concurrent unrelated work and requested normal continuation without memory
  limits. The complete verification was rerun with unchanged memory settings; no other user
  process was stopped. Diagnostic retained as `backend-verify-native-memory-error.log`.
- The second full invocation finished Domain/Application/Infrastructure testing but failed one optional
  protocol-negative case: the supplied existing JDK 21 `task1` image actually supports v1. It was incorrect
  test configuration to expect that image to be rejected. No protocol assertion or product guard changed.
  Read-only inspection confirmed the existing JDK 17 image lacks the protocol tool; its fixed ID
  `sha256:3887ae8a8afbb02c9867b9216d499dc555f43f408193ea93788685531e3ac109` supplied the genuine negative case.
  Neither existing image was altered. The second invocation is retained as a failed invocation.
- Corrective verification used `mvnw.cmd verify -Dtest=<DockerSandboxProtocolTest and every app Test/Tests class>
  -Dsurefire.failIfNoSpecifiedTests=false`; the exact class list is in `corrected-verification-classes.txt`.
  Actual current-image acceptance and old-tool rejection: **12/12 passed, no skips**. All application suites:
  **203 executed / 2 gated skips / 0 failures/errors**. The reactor built and packaged **all modules successfully**.
  Previously passing unchanged suites were reused rather than unnecessarily rerunning the entire fault matrix.

| Module | Executed | Gated skips | Final failures/errors |
| --- | ---: | ---: | ---: |
| Domain | 203 | 0 | 0 |
| Application | 764 | 0 | 0 |
| Infrastructure | 387 | 22 | 0 |
| App | 203 | 2 | 0 |

- Frontend: **244 tests passed** in 16 files; `pnpm typecheck` and `pnpm build` passed.
- PowerShell: **46/46 passed** on each of PowerShell 7 and 5.1.
- Python tools/protocol: **29 passed**, including actual Docker image tests; no skipped image tests in this invocation.
- Public baseline and actual packaged-JAR lifecycle: passed separately, with opt-in paid-provider gates.
- Independent Diff/Git/SQLite/HTTP verification: passed; it performed an additional 7-test offline verify.
- `git diff --cached --check`: passed for the complete implementation, including new files, before its commit.

The 24 normal regression skips are explicit Docker/compatibility/live-provider gates, not passing tests.
The paid packaged-JAR Step and public baseline checks above ran separately. No extra accepted fresh Step
is claimed. The retained evidence distinguishes the completed Step, the earlier frozen-only Candidate,
failed preparation attempts and the failed verification invocations.

M1–M3 are explicitly fixture-backed. This is not real user-discovery E2E, a whole multi-Step Plan
acceptance claim, or a frontend browser acceptance run. Native launcher terminal UI was tested through
its process boundary doubles; real Docker bootstrap and real spawned backend execution were tested separately.

## Reproduction and limits

Normal users install the documented JDK/Git/Node/pnpm prerequisites and a supported Docker installation,
start Docker's Linux Engine, then run `.\setup-delveforge.ps1`. No manual image build is a normal prerequisite.
If Docker is unavailable, explicitly choose `.\setup-delveforge.ps1 -DisableAgent` for discovery/planning.
Manual image overrides/builds remain advanced troubleshooting in AUTONOMOUS_AGENT.md.

For developers, clone the upstream repository freshly into ignored experiment storage, inspect its
license/build/source and pin A. Dot-source setup-delveforge.ps1 and call Initialize-DfAgentImages with
that checkout root and a hashtable; transfer its two fixed IDs into DELVEFORGE_AGENT_IMAGE_17/21.
Use matching experiment-only DELVEFORGE_AGENT_OWNER and DELVEFORGE_MAVEN_CACHE values for baseline
and Step tests. Set DELVEFORGE_REAL_SOURCE, DELVEFORGE_REAL_EVIDENCE, and the freshly built
DELVEFORGE_REAL_JAR. Enable DELVEFORGE_REAL_BASELINE for PublicRepositoryBaselineLiveTest first,
then DELVEFORGE_REAL_STEP for RealRepositoryEvolutionLiveTest with the real key only in process
environment. Each Step invocation creates a new root/DB/WorkingCopy and starts a separate JAR.
Run the independent verifier with that attempt directory, its fixed image ID and experiment cache.
Never enable the paid gate during ordinary full regression verification.

The observed success establishes this public Java/Maven repository and controlled Windows/Docker
configuration. Model output remains variable; one accepted Step is not a reliability rate. Central
dependency preparation observed transient SSLEOFError and completed using existing bounded retries.
Base-image names are versioned, but mutable registry tags do not promise bit-identical future base layers.
Existing filesystem, secret, dependency-network and Delivery restrictions remain in force. Unknown
Delivery outcomes still require retained evidence and explicit recovery rather than reset/clean.
New sandbox images and isolated evidence/cache are retained; no destructive cleanup was performed.

REAL_STEP_E2E_PASS

Fresh-user Docker auto-bootstrap: VERIFIED (real cold identities, cached reuse, contents/protocol
verification, deterministic launcher propagation and actual backend fixed-ID use).
