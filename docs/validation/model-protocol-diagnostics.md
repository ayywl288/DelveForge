# MODEL_PROTOCOL investigation and focused corrections — 2026-10-24

## Status and workspace

**Historical failure: still unconfirmed at the exact response branch.** Investigation narrows it to the
model adapter before the eleventh Reply returned. This change fixes reproduced diagnostic, logging and
presentation defects; it does not claim successful repair of that real Step or end-to-end Agent success.

Verified repository main baseline: `e04a81e63b3ce9c5c9b745543d80f1f833678b48`.
Implementation branch: `codex/model-protocol-diagnostics`, in a newly isolated worktree.
Main was not switched or edited; existing frontend/design/ and grilling/ were preserved. No real application
restart, paid Provider request, Docker resource mutation, user database migration/write, WorkingCopy,
Candidate or Delivery mutation was performed. Tests use loopback HTTP fixtures and temporary databases/Git.

## Historical evidence and what it establishes

Read-only `mode=ro` / `query_only=ON` inspection identified Run
`92feeb60-95df-4133-b82e-96798368d6cf`, Beijing 2026-10-24 12:17:08–12:20:31:
FAILED / MODEL_PROTOCOL, 10 recorded returned model calls, 45 tool calls, 135848 recorded tokens,
zero Candidate and Delivery rows. It has 11 MODEL_REQUEST events and no COMPLETION_FORMAT_RETRY events.

| Event | Beijing time | Safe persisted fact |
| --- | --- | --- |
| 957 | 12:19:01.831 | MODEL_REQUEST request=10 |
| 959 | 12:19:41.034 | PATCH_APPLIED, ok=true |
| 961 | 12:19:42.535 | POLICY_REJECTED / SOURCE_CREDENTIALS / PATCH_SOURCE; mutationNotStarted, alternativeAllowed, resultRecorded all true |
| 962 | 12:19:43.811 | MODEL_REQUEST request=11 |
| 963 | 12:20:31.704 | FAILED / MODEL_PROTOCOL |

The legacy loop writes MODEL_REQUEST immediately before `model.call`, and independently commits usage
immediately after a Reply returns and current-state validation succeeds. SqliteAgentStore.usage uses its own
short transaction; terminal failure does not roll it back. The eleventh model invocation therefore failed
before a returned Reply was recorded. Tool identity/argument checks and final-completion parsing occur after
that usage write; the absence of completion-format retries is additional evidence against that path here.
This establishes the invocation boundary from code plus persisted ordering, not an HTTP receipt from Provider.

Under the baseline SpringAiAgentModel, MODEL_PROTOCOL at this boundary covers missing ChatResponse,
missing first Generation, finishReason=length, or an unsupported outgoing conversation role. Normal loop
history only uses system/user/assistant/tool roles. The SOURCE_CREDENTIALS feedback is structured JSON with
the original tool-call identity; local actual Spring AI conversion tests confirm two tool calls followed by
PATCH_APPLIED and source-refusal responses preserve valid ordered history and allow a subsequent reply.
No evidence establishes that the security refusal itself caused the failure.

The accessible default runtime log contains no matching Run ID, MODEL_PROTOCOL, finish_reason/finishReason
or Spring AI diagnostic entry. No historical response-presence, first-result presence, finish reason, tool-call
structure or rejected-response usage was persisted. Raw Provider data was not dumped or enabled for diagnosis.
Thus length is a hypothesis; an empty result remains possible. The eleventh response is not known to have
contained a patch at all. Ordinary SDK exceptions map to MODEL_TRANSPORT, not this persisted code.

## Model lifecycle, conversion and response budget

CodingAgentService owns history, request events, returned-call usage, argument validation, actual tool dispatch,
structured completion and Candidate finalization. SpringAiAgentModel converts those messages to Spring AI,
uses native tool definitions with internalToolExecutionEnabled=false and a non-executable callback, and
retains maxAttempts=1. It returns only complete accepted adapter Replies; Application dispatch remains sole owner.

Inspected actual installed Spring AI 1.1.8 DeepSeekChatModel bytecode, not an assumed newer SDK:
- absent body or null choices produces an empty ChatResponse; an empty choices list also yields no result;
- native finishReason is placed in Generation metadata; case-insensitive LENGTH rejection was already correct;
- tool IDs/name/argument strings are converted into AssistantMessage.ToolCall; the SDK supplies function type;
- missing function objects can fail inside SDK conversion and remain provider-call failures, not evidence of length.

Source default and composition-root response budget are 8192, separate from M1–M3 AiGateway maxTokens.
The local HTTP regression confirms max_tokens=8192 is actually sent. Long combined tool arguments can consume
this per-response output budget, but no retained historical arguments/options prove they exhausted it in this Run.
Live Provider/model limits were not queried or changed. The local length fixture demonstrates safe rejection,
not the historical cause. No max-token increase or automatic retry was added. Bounded regeneration was evaluated
and deferred: without identifying the real branch it would add paid attempts; partial tool calls must never be
executed or inserted as a complete conversation turn. Existing wall/context/safety bounds remain.

## Implemented corrections and safe diagnostics

Original Run reason/API codes MODEL_PROTOCOL and MODEL_TRANSPORT remain intact. New project-owned
AgentModelFailure adds enum reason and stage. MODEL_FAILED metadataVersion=1 events include request ordinal,
callReturned, and when observable: ChatResponse/result/output presence, allowlisted finish reason, tool-call
count, response-token limit and actual reportedTokens. responsePresent refers to the SDK ChatResponse object,
not proof that the raw HTTP body was populated. Unknown finish values become OTHER; absent usage stays absent.
No prompts, model output, argument/patch text, credentials, Provider error messages or exception text are persisted.

Reasons distinguish missing response/result/output, truncation, invalid tool identity/name/arguments/type/structure,
unsupported conversation role, malformed structured completion and provider-call failure. Null tool name previously
reached immutable Map.containsKey(null) and could produce an accidental NullPointerException; it now has an explicit
TOOL_NAME_INVALID classification. Invalid JSON arguments retain existing INVALID_ARGUMENTS correction behavior.
Truncated replies remain rejected before any incomplete tool can be returned/executed; there is no retry.

Usage contract is preserved: modelCalls/tokens aggregate normally returned and recorded Replies. A rejected
request is distinguishable by MODEL_REQUEST plus MODEL_FAILED and callReturned=false. Reported usage from a
rejected response is recorded separately, never invented or double-counted in the existing aggregate. The UI
clarifies this scope; request #11 and returned-call count 10 are compatible observations, not a fabricated success.

Confirmed additional source defect: Spring AI 1.1.8 logs full Prompt at WARN for missing body/choices. Existing
package INFO did not suppress it. The application now sets only that concrete DeepSeekChatModel logger to ERROR.
A regression captures the old INFO behavior using a synthetic marker with console propagation disabled, then
checks the actual application YAML suppresses it. This demonstrates a leak path, not a real-user data leak.
New diagnostics replace the need to expose Provider/Prompt free text.

## Frontend behavior

Terminal Run classification takes precedence over earlier recoverable operations. Before a Run snapshot catches
up, the same Run's persisted terminal event supplies its reason. Only matching, versioned, allowlisted model
subcategory metadata is displayed. Historical security rejections and patch alerts remain visible. Unknown fields
and raw payloads are ignored. The historical fixture now displays MODEL_PROTOCOL instead of claiming a policy
refusal was the final cause.

Stop uses a dedicated square stop icon, danger foreground/border, subtle danger background, hover and keyboard
focus outline. Normal foreground/background token contrast calculates to 5.71:1. Sending or accepted cancellation
shows 正在停止 and disables repeat submission; the status text distinguishes sending from acceptance. Sticky
placement, target selection, cancellation API and terminal-state behavior are unchanged. Model waiting labels say
第 N 次请求; recorded returned-call counts retain their existing values.

## Changed files and rationale

| File/area | Reason |
| --- | --- |
| application/agent/AgentModelFailure.java | Fixed diagnostic schema and enum categories owned by Application |
| application/agent/AgentTools.java | Specific invalid-protocol reasons and null-name correction |
| application/agent/CodingAgentService.java | Persist request-correlated failures without changing usage/dispatch contracts |
| infrastructure/agent/SpringAiAgentModel.java | Observe bounded metadata and classify missing/truncated/invalid replies safely |
| app/src/main/resources/application.yml | Suppress demonstrated SDK WARN Prompt path |
| AgentToolsProtocolTest.java | Invalid identities/limits and recoverable JSON argument behavior |
| SpringAiAgentModelTest.java | Real Spring AI conversion through a loopback fake Provider, missing/truncated/continuation/transport cases |
| CodingAgentServiceTest.java | Durable diagnostic reload, accounting, completion bound and zero Candidate/Delivery on failure |
| AgentModelLoggingPolicyTest.java | Actual SDK leak reproduction and configured suppression |
| frontend/src/components/agentEventPresentation.ts | Terminal priority, safe diagnostics, distinct request wording |
| frontend/src/components/AgentActivityRow.vue | Explicit failed-model row |
| frontend/src/components/NavigationIcon.vue | Stop icon only |
| frontend/src/views/EvolutionView.vue | Local stop styles/pending state and usage explanation |
| frontend/tests/agent-timeline.test.ts | Terminal priority, stale snapshot and untrusted/mismatched diagnostics |
| frontend/tests/agent-workflow.test.ts | Stop icon/style/pending and existing cancellation assertions |
| docs/AUTONOMOUS_AGENT.md, ADR-0002, this report | Runtime contracts, known SDK logging policy and evidence limits |

No module boundaries, dependencies, database schema, Docker scripts/resources, context compression,
SOURCE_CREDENTIALS rules, patch guards, Candidate or Delivery semantics changed.

## Actual verification

```powershell
.\mvnw.cmd -pl backend/delveforge-infrastructure -am test '-Dtest=SpringAiAgentModelTest,AgentToolsProtocolTest,AgentPatchCorrectionsTest,AgentToolActivityTest,CodingAgentServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false'
# Development PASS: 83 tests, zero failures/errors; before adding final service/logging cases.

.\mvnw.cmd -pl backend/delveforge-app -am test '-Dtest=SpringAiAgentModelTest,AgentToolsProtocolTest,CodingAgentServiceTest,AgentModelLoggingPolicyTest' '-Dsurefire.failIfNoSpecifiedTests=false'
# PASS: 51 tests, zero skipped/failures/errors.

$env:DELVEFORGE_AGENT_LIVE='false'
$env:DELVEFORGE_AGENT_E2E='false'
$env:DELVEFORGE_AGENT_DOCKER='false'
$env:DELVEFORGE_AGENT_COMPATIBILITY='false'
$env:DELVEFORGE_TASK2_E2E='false'
Remove-Item Env:DELVEFORGE_SANDBOX_PROTOCOL_IMAGE -ErrorAction SilentlyContinue
Remove-Item Env:DELVEFORGE_SANDBOX_LEGACY_IMAGE -ErrorAction SilentlyContinue
.\mvnw.cmd verify '-Dmaven.jar.forceCreation=true'
# BUILD SUCCESS: 1579 total, 1555 executed passed, 24 gated skips, zero failures/errors.
# All paid/live/Docker gates disabled; no actual Docker resource verification/mutation in this task.

pnpm --dir frontend install --offline --frozen-lockfile
# PASS: locked dependencies installed inside isolated worktree; package/lock files unchanged.
pnpm --config.verifyDepsBeforeRun=false --dir frontend test agent-timeline.test.ts agent-workflow.test.ts
# Initial regression RED: 3 failed / 103 passed, demonstrating terminal/diagnostic/stop gaps.
pnpm --config.verifyDepsBeforeRun=false --dir frontend test
# Final PASS: 16 files / 244 tests.
pnpm --config.verifyDepsBeforeRun=false --dir frontend typecheck
# PASS.
pnpm --config.verifyDepsBeforeRun=false --dir frontend build
# PASS.
git -c core.safecrlf=false diff --check
# PASS.
```

Intermediate frontend runs also failed on a new test's import.meta.url file lookup under Vite and on old
request-label assertions. The test now uses the repository's existing cwd-based source-read pattern; request
wording assertions were updated while retaining ordinal, count, disabled-state and API-target checks.
No valuable assertion was removed or weakened. Logs are ignored model-*.log files in the isolated worktree.

## Remaining limits and manual checks

Historical exact cause remains unconfirmed; new diagnostics apply only to future executions. There was no paid
model call, real Agent task, deployment or browser visual run. Do not label this as end-to-end coding success.
The current stopping behavior for truncation and existing completion correction limit remain by design.

After the user explicitly merges/rebuilds/restarts for their own integration testing:
1. Open the existing MODEL_PROTOCOL Run: current failure should show MODEL_PROTOCOL while its earlier source
   rejection remains in patch history; no subcategory is invented for old events.
2. In a controlled fixture or separately authorized test, verify the red stop icon/border/background, Tab focus,
   immediate 正在停止/disabled state, sticky placement on desktop/narrow screens and removal after real termination.
3. Inspect future MODEL_FAILED metadata for an actual reason before considering response-budget changes or any
   paid regeneration. No raw response logging is needed. The sandbox script did not change, so this task does
   not require rebuilding or replacing the existing compatible sandbox image.

Commit is recorded in the feature branch Git history and final handoff. This task does not merge or push.
