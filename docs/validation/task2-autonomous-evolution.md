# Task 2 — Autonomous Evolution acceptance

Date: 2026-10-22. Branch: `feature/autonomous-agent-task2`.
Starting checkpoint: `c8fa9234e2e129424fdeb1551be7e04c63d2d659`.

## 2026-10-23 independent-review remediation

Remediation starts at `ddb5edda437fcca5c29a83f3da67414f58e74d58` on
`feature/autonomous-agent-task2`. The original independent **FAIL** report and its probes
remain unchanged at `data/t2r/task2-independent-review.md`. The sections below this
remediation record describe the 2026-10-22 implementation and its historical evidence.

Fresh before-fix reproduction used only a new `data/t2fix/before-race` Git/SQLite fixture.
The real Delivery overwrote the externally created ignored Added.java and returned
COMPLETED / Step SUCCEEDED / trusted C (`data/t2fix/before-race.log`). The independent
Vue deferred-response probe failed both Start and Cancel assertions, with its other six
tests passing (`data/t2fix/before-ui.log`). The permanent ignored-file regression also
failed against the old mutation, expecting RECOVERY_REQUIRED but observing COMPLETED
(`data/t2fix/regression-red-final.log`). Early command/fixture setup failures are retained
separately; they are not counted as reproductions.

### Corrections

F1 was caused by read-tree's worktree overwrite behavior after preflight. Official source
mutation now uses the narrow Workspace-native boundary described in
[ADR-0013](../decisions/0013-frozen-candidate-delivery.md): Windows canonical directory
handles exclude delete sharing; verified existing files are opened exclusively, additions
use CREATE_NEW, and deletion uses the verified handle. Linux x86-64 uses directory-relative
O_NOFOLLOW / O_EXCL and no-replace capture of old entries. Captured bytes and their path
manifest remain in Git metadata for inspection. No replace fallback writes official source.

Only changed B files are rendered in disposable Git metadata, retaining supported Git EOL
conversion and modes. Index bytes are checked in the same native boundary, then the frozen
patch is applied with --cached using Git's atomic index publication. No source checkout is
performed by Git. An incomplete marker survives partial source/index failure and prevents
automatic acceptance even if the visible tree later equals B. Only a proven no-write rejection
records MUTATION_NOT_STARTED; after the external conflict is resolved, explicit retry still
requires fresh authorization and a complete clean-A proof. Frozen Candidate, durable reservation,
parent(C)=A, tree(C)=B, reference CAS and atomic SQLite acceptance remain unchanged.

JNA 5.17.0 is the only new dependency, isolated to Infrastructure. Supported native mutation
targets are Windows and Linux x86-64 with the documented APIs; unavailable capabilities fail
closed. Source/index snapshots are bounded to 1 MiB per file. This does not claim a universal
transaction against hostile pre-existing descriptors, writable mappings or writers acting
after the handles close. No automatic reset, source cleanup or ambiguous-state repair is added.

F2 was caused by unguarded action continuations and global pending/error cleanup. EvolutionView
captures epoch plus Plan/Step/Run/Candidate identity for each action and checks it after awaits.
The existing epoch also protects history and status reads; an action owner scopes pending cleanup
when selection changes or a Candidate arrives in the background. Start, Cancel, Delivery, diff
and Step confirmation ignore obsolete UI results while backend work continues. Returning to a
Step reloads its saved truth. No global workflow/state framework is introduced.

### Permanent regressions

`CandidateDeliveryTest` retains its original 17 Git/SQLite cases and adds 12 executions:

| Test | Executions / obligation |
| --- | --- |
| fileAppearingAtMutationBoundaryIsNeverOverwrittenOrAccepted | 2: ignored/untracked Added.java injected after preflight; exact binary bytes, journal/Candidate/trust and explicit safe recovery |
| changedTrackedBytesAtWriteBoundaryArePreservedIncludingDeletion | 2: modification and deletion reject new tracked bytes |
| parentReplacedWithJunctionOrSymlinkCannotWriteOutsideOfficialCopy | 1: real Windows junction / Linux symlink at the final mutation boundary |
| changedIndexAtBoundaryIsPreservedWithoutAcceptingCandidate | 1: foreign index survives the native boundary |
| partialCreationRetainsMarkerEvenWhenForeignWorkLaterMakesTreeLookLikeB | 1: partial creation retains original index and prevents acceptance of a later B lookalike |
| rejectedForeignTreeEqualToBIsNotAcceptedAsOurCompletedMutation | 1: a recorded no-write conflict cannot be adopted as our successful mutation |
| indexFailureAfterSourceMutationKeepsForeignIndexAndIncompleteMarker | 1: source/index publication boundary failure remains recoverable and unaccepted |
| normalAdditionAndDeletionDeliverWithExactTreeAndParent | 1: nested addition and deletion reach valid C |
| additionOnlyCandidateDoesNotRequireAnyChangedBaselineFile | 1: a pure addition does not accidentally enumerate all baseline EOL records |
| renderedCandidatePreservesVersionedCrLfConversion | 1: supported versioned EOL conversion survives rendering |

`frontend/tests/agent-workflow.test.ts` retains the six existing Start/cancel/SSE/history tests
and adds nine executions using deferred promises: Step-switch Start and return/readback,
Run-switch Cancel, stale error versus new pending/error, Run-switch Delivery, current Delivery
success/error, deferred diff-body failure, two confirmation-response variants, and background
Candidate arrival while Cancel is pending. Two additional cases restrict recovery retry to
proven untouched rejections. The complete frontend suite has 108 tests.

Fixture variants restore the production immutable-Candidate trigger before invoking Delivery.

### Executed verification

| Scope | Result | Local evidence |
| --- | --- | --- |
| Final complete Windows backend `mvnw.cmd verify`, finished 2026-10-23 01:18:03 +08:00 | 1,469 discovered; **1,447 executed, 0 failures/errors, 22 gated skips**; all four modules and application packaging succeeded | `data/t2fix/backend-verify-final-guarded.log`, `final-backend-results.json` |
| Actual Windows Delivery matrix | **29 passed**, including all 12 new executions and the original 17 Git/SQLite cases | `data/t2fix/final-key-reports/TEST-com.ayywl.delveforge.infrastructure.agent.CandidateDeliveryTest.xml` |
| Restart / HTTP / migration | **5 fresh-process recovery cases, 3 HTTP/SSE cases, 1 migration case passed**, included in the complete run | `data/t2fix/final-key-reports/` |
| Linux x86-64 supplementary Delivery matrix | **29 passed**, real Git/SQLite/native operations in a new disposable container | `data/t2fix/linux-matrix-final-guarded.log` |
| Fresh Task 1 Docker security / retention | **17 passed**: Sandbox 12, proxy isolation 1, retention/recovery 4 | `data/t2fix/backend-verify.log`, `data/t2fix/docker-reports/` |
| Final frontend | **108 passed across 12 files**; declared typecheck and production build succeeded | `data/t2fix/frontend-tests-final.log`, `frontend-typecheck-final.log`, `frontend-build-final.log` |

The final backend totals are Domain 203, Application 726, Infrastructure 338 (21 skipped),
and App 202 (1 skipped). The packaged application includes `BOOT-INF/lib/jna-5.17.0.jar`.
The 22 gates comprise 17 Docker cases, 2 optional public-repository compatibility cases,
and 3 live Provider cases. The 17 Docker cases were executed separately in the preceding
complete run; their Sandbox implementations are unchanged by the final Delivery index
refinement. The two optional compatibility and three live Provider cases were not rerun.
The preceding full run is retained as intermediate evidence, not substituted for the final
complete run. No paid Provider was used for this remediation.

The Linux supplement invokes the same permanent test methods with a fresh fixture for each
execution through `data/t2fix/DeliveryLinuxRunner.java`, using already compiled classes in a
network-disabled, read-only-root container and disposable `/tmp` Git/SQLite data. This is a
scoped Linux supplement; the complete Maven verification above ran on Windows.

The final matrix covers normal and duplicate A/D/B/C delivery, SQLite failure after Git
publication, frozen Candidate discovery, all five restart checkpoints, concurrent reservations,
external changes and ambiguous/partial states. Full Candidate diff/hash/tree/parent and trust
assertions remain active. Existing application databases, managed WorkingCopies, review evidence,
and unrelated `frontend/design/` / `grilling/` were not modified.

### Historical intermittent failure investigation

The original review's unrestricted matrix had 16/17 passing and one normal Delivery returning
RECOVERY_REQUIRED after 79.50 seconds, followed by temporary-directory cleanup failure. Its
isolated repeat passed. Those failures remain in `data/t2r/focused-verify-unrestricted.log`.

The normal case passed in the final complete matrix in **13.235 seconds**. Three subsequent
independent wrapper invocations each executed that one case successfully: **13.466, 13.676,
13.729 seconds**, zero failures/errors, with successful cleanup. Exact results are retained in
`data/t2fix/normal-repeats.json` and `normal-repeat-1.log` through `normal-repeat-3.log`.
Inspection of the existing bounded process termination and occupation paths, plus these fresh
matrix/repeat runs, did not reproduce a concurrency or isolation defect. The historical cause
remains unidentified; the passing repeats do not establish its cause or erase its occurrence.

**Readiness: READY for the F1/F2 remediation described above.** The coherent local remediation
commit contains this record and the 13 scoped implementation/test/documentation files. No push,
merge or subsequent milestone work is included.

---

## Scope and implementation

The active flow is confirmed Step → isolated AgentRun → immutable Candidate → automatic deterministic Delivery.
Step success is recorded only when the official Git commit and SQLite acceptance agree. Delivery reuses the frozen
Candidate without a model, surviving removal of the coding sandbox. Later Steps still need explicit confirmation/start.
The old one-shot proposal, execution-preparation, Repair, RetryAuthorization and Rollback routes and implementations
are retired. M3 retains goal/scope/plannedChanges, ordering and evidence without mandatory verification strategies.

Application owns orchestration and ports; Infrastructure owns Git, Docker, Spring AI and SQLite; App provides REST/SSE.
No new dependency or module direction was introduced. See [ADR-0013](../decisions/0013-frozen-candidate-delivery.md)
and [operating instructions](../AUTONOMOUS_AGENT.md).

## Delivery and failure evidence

`CandidateDeliveryTest` uses actual independent Git clones and SQLite databases, not mocked Git.
The initial twelve cases passed after correcting legitimate Windows CRLF checkout handling. They cover:

- A/D/B replay, exact official tree, controlled commit, original repository unchanged and duplicate request idempotency.
- Saved INTENT; actual B before APPLIED journal; an unpublished orphan commit; published C before SQLite checkpoint.
- SQLite acceptance failure injected with an aborting trigger after Git publication, followed by reopening and recovery.
- Partial index and untracked artifacts, preserved external source changes, corrupted diff and mismatched expected tree.
- HEAD publication lock failure with retained C, durable reservation, competing native lock and concurrent reservations.

Additional final regressions cover invalid baseline, unresolved test failure, a conflicting Agent reservation,
changed journal identity, an unavailable official directory, and successful SQL commit followed by injected lost
acknowledgement. The last case performs the real acceptance transaction before throwing and proves durable readback
returns COMPLETED without a second commit.
`AcceptedDeliveryTest` verifies confirmation/order, later-Step baseline advancement and absence of partial domain mutation.
`DeliveryMigrationTest` actually migrates V23 → V25 and reopens it, retaining planning facts and frozen Candidates while
archiving and superseding legacy execution. V22/V23 checksums were not rewritten. No real user database was migrated.

`DeliveryProcessRecoveryTest`: **4 passed, 0 skipped** in the focused run (86.58 seconds).
Mechanism: construct deterministic real Git/SQLite interruption snapshots at INTENT, applied B before journal,
COMMIT_READY, and published C before publication journal; close the original context; launch a new JVM running the
real Spring application with startup reconciliation. This is fault-snapshot injection, not an OS kill during Git.
Each child runs without provider credentials and completes acceptance with exactly one original AgentRun.
Ambiguous external or partial changes remain occupied and blocked; the system does not reset/clean them.

## HTTP, SSE and frontend

`CodingAgentApiTest` exercises the real HTTP stream: replay saved facts, append a Delivery event during subscription,
disconnect, append another event, reconnect with Last-Event-ID and check the next exact sequence. Negative cursors fail.
Persisted queries are bounded; no separate replay/live handoff exists. Browser EventSource reconnects using IDs;
the Vue timeline deduplicates sequence, ignores stale requests and closes subscriptions when leaving the page.

Frontend final deterministic regression: **97 passed / 12 files**, no skipped tests; `pnpm build` includes Vue/TypeScript
checking and produced the application bundle. Browser checks are recorded separately below, not inferred from these tests.

## Public repository / real model run

Source: unmodified public `spring-guides/gs-rest-service`, pinned commit
`3f4cef01152596c19bc4d37939409358812b1421`. The ordinary nested `complete/pom.xml` project uses JDK 17;
no project-specific DelveForge modification was required. M1–M3 are explicitly fixture-backed domain facts;
registration, preparation, confirmation, Agent start, history and Delivery use production public HTTP paths.
Agent, provider, Docker, Maven checks, Git and SQLite are real.

The first diagnostic run accepted Step 1 as `9c7c7b368b43a206ce999f04a2047585b600dc25`, with 6 tests executed,
0 skipped and 9 model calls / 9 tool calls. Step 2 started from that accepted baseline but failed POLICY_REJECTED
without a Candidate. It remains recorded as a failed run; this is not counted as successful end-to-end acceptance.
The bounded test driver can explicitly start one fresh HTTP attempt per failed Step, preserving both Run identities;
the product itself never automatically retries coding.

The final real run passed (`AutonomousEvolutionLiveTest`: 1 test, 0 failures/errors/skips, 287.7 seconds).
Disposable root: `data/t2e/live/abafef29-1815-4622-b7ab-5e8d64cebaad`.
Reopenable evidence is `evidence.json` and `application.db`; these local data are intentionally not committed.
Plan: `6d91250f-8541-45fe-8be6-792389ba77c2`.

| Step | Run | Model / tool calls | Actual final tests | Accepted commit C | Frozen tree B |
| --- | --- | --- | --- | --- | --- |
| 1: blank-name fallback | `857d697c-2106-45a0-8bd0-aa1bac06fb6c` | 9 / 12 | 6, 0 skipped | `799ffe89e98cd4a7000baa3957fa873e21b81f32` | `7b243138990522035badffb2f7bc96bc6accf134` |
| 2: trim nonblank names | `21810fa1-397a-4912-9c5e-877de2771fd6` | 8 / 8 | 8, 0 skipped | `1a5d33668c22b057bba12beb8ad09b06beed3d4b` | `b27c09bcc1884118ee6efe310e7dc1e64d34c805` |

Both Candidates changed real Java controller/test code. For each, the test reloaded frozen data, proved HEAD parent A,
tree(C)=B, both SQLite revisions=C and Step SUCCEEDED, then repeated Delivery and received the same C.
Step 2's A is exactly Step 1's C. The original clone's HEAD and clean status remained unchanged.
Step 1's earlier baseline check is honestly retained as stale; final full checks are nonstale. No test result is invented.
Both final attempts succeeded without using the driver's optional fresh retry. A separate deterministic service test
explicitly fails one model call and starts a distinct successful attempt while preserving Step READY and trusted A.

Browser: observed the real Step 1 FINALIZING → frozen result → APPLIED → COMMIT_READY → PUBLISHED → COMPLETED
events, final two changed paths and accepted commit. Reloading the page while Step 2 was RUNNING restored the saved
plan, correct previous C baseline and persisted timeline, then continued receiving actual tools. The active view is
saved locally as `data/t2e/ui-active.png`. This is browser execution against the real disposable database.
The first application's shutdown waited on the still-open SSE browser and Surefire ended that fork after 30 seconds;
the test assertions and evidence had completed successfully. This exposed a lifecycle issue, repaired by closing
subscriptions on ContextClosedEvent before graceful web-server shutdown. The supplemental real HTTP shutdown test
below verifies it; the terminal's abrupt interrupt is not counted as graceful-shutdown evidence.

The first broad regression was **not green**: one compatibility test lacked the required pinned repository property;
four historical V18–V21 migration tests accidentally continued through V25 and asserted intentionally retired state;
one persistence test still expected the removed verification-criterion rows. Historical migration tests now target
their named version without weakening byte-preservation assertions; V25 retirement remains covered separately.
The persistence regression now proves goal/scope/plannedChanges survive without inventing old criteria. M1–M3
domain assertions were not relaxed. Subsequent full results are recorded below after execution.

Real Docker isolation/retention: **17 passed, 0 skipped** (12 sandbox + 1 proxy + 4 finalization retention/restart).
The proxy case actually injects invented Docker client proxy credentials, exercises cold dependency preparation and
checks every project-facing container. Core Docker implementation was unchanged afterward. Their actual XML/text
reports are retained under `data/task2-evidence/docker`; a later deterministic regression skips these already-run
gates and does not count the skips as fresh passes. The corrected compatibility run passed both tests, 0 skipped,
in 72.56 seconds: Java 8 release compilation on JDK 21, and the unchanged public `gs-multi-module` reactor at
`d88a2b721bda3798a6a934987157498e66da06c5` on JDK 17. That reactor ran 2 baseline tests, then two-module edits,
full recheck and Candidate capture. Reports are under `data/task2-evidence/compatibility`.

Expanding the new-process matrix to frozen-only discovery exposed a test-probe error: it queried for a journal row
before asynchronous discovery had inserted one. The other four snapshots passed. The probe now waits for a completed
row count, including the valid initial zero-row state; all later Git, trust and exactly-one-Run assertions remain.
Production behavior was not bypassed. The final full regression follows that correction.

## Final result — READY

`mvnw.cmd verify`: **BUILD SUCCESS**, 2026-10-22 18:26:18, 6 minutes 32 seconds.
Actual final complete-run counts (not combined with prior runs):

| Module | Executed | Skipped | Failures / errors |
| --- | ---: | ---: | ---: |
| Domain | 203 | 0 | 0 / 0 |
| Application | 726 | 0 | 0 / 0 |
| Infrastructure | 305 | 21 | 0 / 0 |
| App | 200 | 1 | 0 / 0 |
| Total | **1,434** | **22** | **0 / 0** |

The 22 skips are 19 already-executed Docker/compatibility gates and 3 provider gates. The Task 2 provider test passed
separately as documented above; the two older Task 1 provider gates were not rerun. Skips are not counted as passes.
The final 17-case Delivery matrix passed, and **all 5 new-process snapshots passed** (95.33 seconds), including
frozen-only startup discovery. Full-run log: `data/task2-verify-final.log`; counts: `data/task2-final-results.json`.

After that complete run, one supplemental HTTP regression was added to prove active-SSE shutdown directly.
`mvnw.cmd verify -Dtest=CodingAgentApiTest -Dsurefire.failIfNoSpecifiedTests=false` passed all **3 actual HTTP tests**
and repackaged the application, finishing 18:30:47. It opens a real subscription in an independently configured
application, receives its persisted event, closes the application and asserts completion under 5 seconds rather than
the default 30-second graceful timeout. This scoped run is not reported as another complete regression.

Browser observation also reproduced a stale 502 notice after reconnection. Status-read errors now clear on successful
resynchronization while action errors remain separate; the new frontend regression raised the final count to 97.
The final package reopens the real two-Step database without provider credentials. The actual browser reconnected,
displayed accepted commit `1a5d33668c22b057bba12beb8ad09b06beed3d4b`, and retained exactly **37 Step 2 events** after
refresh/replay, without the stale error. Frozen diff and the three real changed paths were read through the UI.
Screenshot: [final packaged UI](../images/autonomous-evolution.png).

All mandatory Task 2 evidence is satisfied within the stated compatibility limits. No provider or filesystem safety
boundary was disabled to obtain a passing demonstration. No push, merge or release was performed.

The real-model run preceded the final journal-identity check, unavailable-directory classification and SSE shutdown
refinements. Their current deterministic regressions exercise those additions; the real public Run/Candidate identities
match the tightened contract. The final packaged browser readback uses the same saved public results, without a model.

## Reproduction

Normal backend regression uses the repository wrapper, with Docker/provider gates off unless explicitly requested.
For the real Docker matrix, prepare the trusted images documented in AUTONOMOUS_AGENT.md, then set
`DELVEFORGE_AGENT_DOCKER=true`. The optional compatibility matrix also needs
`DELVEFORGE_AGENT_COMPATIBILITY=true` and
`-Ddelveforge.compatibility.repository=<absolute clean gs-multi-module pinned copy>`.

For the two-Step provider test, use an unmodified clean local `gs-rest-service` clone and an externally supplied
temporary `DEEPSEEK_API_KEY`; set `DELVEFORGE_TASK2_PUBLIC_REPOSITORY` to that clone,
`DELVEFORGE_TASK2_ROOT` to a new disposable parent and `DELVEFORGE_TASK2_E2E=true`, then run:

```powershell
.\mvnw.cmd test '-Dtest=AutonomousEvolutionLiveTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

The driver always adds a new UUID directory, uses production HTTP execution and preserves failed attempts. A failed
attempt permits at most one explicit fresh retry per Step. No successful Run is resumed or recoded.

## Limits

Delivery acceptance proves frozen source/Git/SQLite consistency, not arbitrary business correctness. NOT_TESTED remains
NOT_TESTED; known unresolved check failures block acceptance. Public Maven Central, supported paths/attributes and
JDK 17/21 remain the Task 1 compatibility boundary. No private dependency credentials, external service integration,
automatic ambiguous-state repair, push, release or automatic later-Step execution is claimed.
All development runs use temporary databases or new `data/t2e` roots; existing application databases, historical
WorkingCopies and unrelated `frontend/design/` / `grilling/` drafts are preserved. Temporary provider credentials
are supplied through a hidden child-process prompt, never written to repository files or container environments.
