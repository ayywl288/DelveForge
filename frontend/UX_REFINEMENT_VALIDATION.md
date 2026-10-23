# Frontend UX refinement validation

Date: 2026-10-23 (Asia/Shanghai)

## Baseline and scope

Verified repository `ayywl288/DelveForge`, branch `main`, full initial HEAD
`70b8dbab6040398b9c5ae98db159f0080724233e`. Main had only the existing untracked
`frontend/design/` and `grilling/` directories. Implementation uses the isolated `frontend-ux-followup`
worktree and branch `feature/frontend-ux-followup` based on that commit.

This task changes frontend presentation, request activity tracking, and frontend eligibility explanations.
There are no backend/API/schema/dependency changes, real database/WorkingCopy mutations, live AI requests,
real Agent execution, merge, push, or publication. Source review used the current Domain Model and
`StepExecutionPolicy`; frontend snapshots do not replace backend authorization or physical-state checks.

## Five focused changes

1. **Chat presentation.** Ordinary bubbles no longer visibly repeat author labels; hidden author text
   remains available to screen readers. User bubbles align right on lavender, assistant bubbles align left
   on a neutral surface. Confirmed acceptance is a double-check icon with an accessible description that
   explicitly excludes delivery/read receipts. Pending/error/uncertain states retain their real meanings.
   The waiting bubble displays three 6 px dots and hidden status text, using the conversation's single live
   region. Entry motion remains 230 ms; reduced-motion CSS disables message, waiting and busy animations.
2. **All Discovery question paths.** An explicit typed AI activity tracks normal turns, request-next-question,
   reopen from CONFIRMED, and return from REVIEWING. It is scoped to project epoch/profile identity.
   The view opens the chat while these requests are pending, suppresses the empty introduction and bare
   request note, and clears waiting on completion. Actual nextQuestion/revision/sufficiency are retained;
   empty or failed responses do not create assistant messages. A successful return-to-exploration transition
   survives a failed subsequent question, with an explicit notice. Late question/transition responses or
   errors cannot overwrite another profile/project or initiate its question request.
3. **Local AI feedback.** `InlineBusy` provides a compact indeterminate spinner/label next to repository
   analysis, direction discovery and plan generation. App suppresses the global banner on the owning page
   and restores a restrained fallback when users navigate away. Activity ends before the subsequent
   project-association save; accepted business results and synchronization errors remain separate.
   No delay, fake phase, percentage, retry or additional Provider call was introduced.
4. **Plan preparation flow.** Order is overview → compact preparation → detailed roadmap. The review
   checkbox and independent confirmation dialog remain required. Preparation never authorizes a Step.
   ACTIVE plans immediately show the returned WorkingCopy state/location; SUPERSEDED plans retain their
   historical presentation without a preparation action. Disabled Step controls explain the actual visible
   prerequisite and link back to preparation when appropriate.
5. **Evolution Step display.** The selector is the primary Step identifier; the repeated full heading is
   removed while status, scope and planned changes remain. A shared frontend prerequisite description
   covers plan state, copy identity/source/status, trusted revisions, Step baseline, earlier Steps and
   unresolved recovery state. Premature confirmation is hidden and users can return to Planning.
   READY starts also respect these visible prerequisites. Existing Agent/SSE/Candidate/Delivery logic and
   late-selection response protections are retained.

The intended observable tightening is that the frontend no longer offers confirmation/start when its
current snapshot already violates a backend prerequisite. Hidden confirmations are also guarded against
stale selection. No domain eligibility, authorization action or server contract was changed.

## Changed files

```text
frontend/src/App.vue
frontend/src/components/InlineBusy.vue
frontend/src/state/session.ts
frontend/src/state/stepPrerequisite.ts
frontend/src/style.css
frontend/src/views/DiscoveryView.vue
frontend/src/views/RepositoryView.vue
frontend/src/views/DirectionsView.vue
frontend/src/views/PlanView.vue
frontend/src/views/EvolutionView.vue
frontend/tests/discovery-messaging.test.ts
frontend/tests/agent-workflow.test.ts
frontend/tests/views.test.ts
frontend/tests/ux-refinement.test.ts
frontend/README.md
frontend/UX_REFINEMENT_VALIDATION.md
```

## Automated verification

Commands run from `frontend/` in the isolated worktree:

| Command | Result |
|---|---|
| `pnpm test` (initial implementation) | FAIL: 5 old expectations and one resulting hidden-dialog error; resolved before final verification |
| `pnpm test -- tests/ux-refinement.test.ts tests/discovery-messaging.test.ts tests/agent-workflow.test.ts tests/views.test.ts` | PASS: 62 tests at the focused checkpoint |
| `pnpm test` (intermediate complete suite) | PASS: 143 tests across 14 files |
| `pnpm test` (final complete suite) | PASS: 145 tests across 14 files; no failures/unhandled errors |
| `pnpm typecheck` | PASS, including final source |
| `pnpm build` | PASS: 63 modules, production bundle built |
| `git diff --check` (repository root) | PASS |

The updated assertions retain the original checks: acceptance is now asserted through its accessible icon;
three stale-selection tests also require the earlier-Step lock instead of an incorrectly enabled
confirmation; the preparation test now supplies the actual WorkingCopy binding returned by preparation.
The hidden-dialog handler is null-safe and rechecks the selected Step. No valuable tests were deleted.

New permanent coverage includes every Discovery entry path, success/failure/empty responses, retained
transition acceptance, profile and project switches, normal-turn/global fallback, all three local AI
indicators, single-flight/API counts, preparation ordering/explicit confirmation/immediate state update,
historical and invalid-copy conditions, READY start eligibility, and nonduplicated Step identification.
The previous optimistic submission, draft, uncertain-result, synchronization, SSE and Delivery regressions
are part of the final complete suite.

Ignored verification logs: `frontend/ux-initial-tests.log`, `frontend/ux-focused.log`,
`frontend/ux-full-tests.log`, `frontend/ux-typecheck.log`, `frontend/ux-build.log`.
Backend execution tests were not run because backend code/contracts are unchanged.

## Browser and visual checks

Performed with the real view/session code and controlled synthetic responses, without a backend connection:

- Desktop: message alignment, no visible author labels, pending dots, accepted double-check, long Chinese
  replies, preparation ordering, review/confirmation, immediate ACTIVE/WorkingCopy update, compact
  Evolution Step selector, and historical-plan lock.
- Narrow: a same-origin iframe provides an actual 390 px CSS viewport. Long Chinese bubbles wrap within
  it; the chat log scrolls independently while the composer remains outside the log. Scrolling the page
  reaches preparation before the roadmap. Preparation controls remain readable and usable.
- The automated App tests separately verify local/global exclusivity and navigation fallback for all AI
  operations; the standalone browser fixture did not replace those tests with fabricated global UI.

Screenshots were saved outside the repository as `ux-followup-chat-desktop.png`,
`ux-followup-chat-narrow.png` and `ux-followup-plan-narrow.png` in this chat's visualization directory.
The temporary preview files/server were removed/stopped after inspection.

Remaining limits: this is synthetic frontend/browser validation, not live Provider/Agent/Delivery E2E.
Reduced-motion overrides were inspected in source, but an actual OS preference toggle and screen-reader
announcement behavior were not manually tested. Native mobile-device behavior and the user's running
backend/browser combination remain manual acceptance items. Unknown Discovery outcomes remain page-local
and cannot be resolved automatically without a backend request-status contract, as before this task.
