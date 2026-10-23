# Repository registration and Discovery messaging focused fix

Date: 2026-10-23 (Asia/Shanghai)

## Scope and workspace

- Repository: `ayywl288/DelveForge`; accepted baseline and actual initial main HEAD:
  `f460aa687d8a0103446fbd5baff91e048af652cc`.
- Implementation is in the isolated `frontend-ux` worktree on `feature/frontend-ux`.
  The existing main checkout and its untracked `frontend/design/` and `grilling/` were preserved.
- No dependency changes, database migrations, live Provider calls, Agent/Candidate/Delivery implementation
  changes, merge, push, or publication form part of this task.

## Registration evidence and root cause

The accessible runtime log contains errors on 2026-10-23 at 15:01:56.081, 15:03:04.535 and
15:03:06.872 for `/api/software-assets`. The structural exception is `IllegalStateException`.
The first captured stack identifies:

```text
SqliteSoftwareAssetRepository.registerUnique:46
RegisterSoftwareAssetUseCase.register:52
SoftwareAssetController.register:79
FrameworkServlet.doPost
```

Thus the captured failure is POST registration, after the frontend's GET lookup. The failing line rejects
an update count other than one from `lockRegistration()`. The original SQL only updates a singleton
technical row seeded by V14. A read-only inspection of the default runtime database established that
the migration was already applied while its singleton guard row was absent. No asset/profile rows,
request text, exception free text, credentials, or user repository paths are reproduced here.

The demonstrated trigger is a missing technical guard row. There is no evidence establishing which
earlier operation removed that row. The captured failure is not a path normalization or license rejection.
No runtime request was sent to register debugging data against the user's database.

The isolated regression first removes only the technical row in a temporary database. Lookup succeeds
with an empty result, and the original registration implementation throws at the same repository line.
This regression failed before the patch. A separate HTTP → Application → real SQLite regression now
covers GET lookup → POST registration → GET lookup and duplicate rejection using a synthetic Windows path.

The patch changes only the mapper's guard write to `INSERT ... ON CONFLICT(id) DO UPDATE`.
It establishes or locks the technical row in the same registration transaction before duplicate detection.
The guard contains no business or authorization state. Existing guard values are retained. SQL/schema
failures remain server errors; exception mappings and API contracts are unchanged. No migration or
automatic authorization repair is introduced. Tests also exercise concurrent registrations when the
guard is absent, authorization preservation, and rollback of guard creation together with asset insertion.

## Discovery messaging behavior

Previously the composer remained disabled and retained sent text until the full request/context-save
sequence ended. The profile revision/status watcher also cleared newly typed text.

Now the session inserts a pending user message synchronously, the composer clears immediately after
that insertion, and it remains editable. The global single-flight and project synchronization guards
still govern submission. A pending message shows a spinner and an assistant waiting row. Entry motion
is 230 ms with a 7 px upward transition and restrained easing; reduced-motion CSS disables it and the
waiting animations.

Only a successful Discovery Turn response marks the user message accepted. Actual returned profile,
revision, sufficiency and `nextQuestion` are used. The marker is not a read receipt. Project association
is then a distinct saving phase; its failure retains the accepted message and business result and keeps
the existing synchronization block. No streaming API, artificial network delay, or domain change exists.

Known rejection contracts (400 INVALID_REQUEST, 404 NOT_FOUND, 409 CONFLICT and 502
EXTERNAL_CAPABILITY_UNAVAILABLE) show a failed message. Connection loss, unreadable success responses,
unknown errors and generic server failures remain uncertain. The inline status action only GETs the
profile. It does not infer acceptance from a changed revision, resend a turn, or remove uncertainty.
An uncertain message blocks another Discovery Turn for that profile in the current page session.

Same-profile revision/status changes preserve drafts; switching profile identity clears the old draft.
Explicit profile review/confirmation semantics remain unchanged. Complete messages and drafts are not
stored in localStorage. Chat and uncertainty state remain page-local; reloading cannot establish that
an old server request has ended. The existing synchronous API has no request identity/status endpoint
with which to resolve that uncertainty automatically.

## Verification

All backend executions use Maven Wrapper and temporary test databases. Frontend checks use the existing
locked project stack. Logs remain ignored in the isolated worktree.

1. Reproduction before mapper change:

   ```powershell
   .\mvnw.cmd -pl backend/delveforge-infrastructure -am test '-Dtest=SqliteSoftwareAssetRepositoryIntegrationTest#registersAfterTechnicalGuardRowIsMissingWithoutChangingAuthorizationOrDuplicateRules' '-Dsurefire.failIfNoSpecifiedTests=false'
   ```

   Expected RED: one test errored at the original guard check. Log: `registration-red.log`.

2. Initial focused backend run:

   ```powershell
   .\mvnw.cmd -pl backend/delveforge-infrastructure -am test '-Dtest=SqliteSoftwareAssetRepositoryIntegrationTest,LocalRepositoryIdentityAdapterTest,RegisterSoftwareAssetUseCaseTest,AssetAuthorizationMigrationTest' '-Dsurefire.failIfNoSpecifiedTests=false'
   ```

   PASS. Log: `registration-green.log`. The final broader run below includes the subsequently added
   transaction and HTTP regressions.

3. Final focused backend run:

   ```powershell
   .\mvnw.cmd -pl backend/delveforge-app -am test '-Dtest=SqliteSoftwareAssetRepositoryIntegrationTest,LocalRepositoryIdentityAdapterTest,RegisterSoftwareAssetUseCaseTest,AssetAuthorizationMigrationTest,SoftwareAssetControllerIntegrationTest,ApiExceptionHandlerTest' '-Dsurefire.failIfNoSpecifiedTests=false'
   ```

   PASS: 56 tests (12 Application + 19 Infrastructure + 25 App), no failures/errors/skips.
   Log: `registration-final.log`. An earlier run of this command failed because the new HTTP test fixture
   did not JSON-escape Windows backslashes. Its helper was corrected; production path handling was unchanged.

4. Frontend (run from `frontend`):

   ```powershell
   pnpm test -- tests/discovery-messaging.test.ts
   pnpm test
   pnpm typecheck
   pnpm build
   ```

   Focused messaging: 13 PASS. Complete frontend: 121 PASS across 13 files. Typecheck and production build:
   PASS. Required checks were rerun after the final context-saving label and scrolling changes.
   Logs: `frontend/discovery-focused.log`, `frontend/frontend-test.log`,
   `frontend/frontend-typecheck.log`, `frontend/frontend-build.log`.

5. Broad backend verification:

   ```powershell
   .\mvnw.cmd verify
   ```

   PASS / BUILD SUCCESS: 1,472 discovered tests, 1,450 executed successfully, 22 gated skips,
   zero failures/errors; 146 Surefire XML reports. The four-module reactor and repackaged application JAR
   completed successfully in 8 minutes 24 seconds. Log: `backend-verify.log`.

6. `git diff --check`: PASS. Final main checkout remains at the accepted baseline with its two original
   untracked directories. The feature worktree contains only the task changes listed below.

## Changed files

- `backend/delveforge-infrastructure/src/main/java/com/ayywl/delveforge/infrastructure/persistence/softwareasset/SoftwareAssetMapper.java`
- `backend/delveforge-infrastructure/src/test/java/com/ayywl/delveforge/infrastructure/persistence/softwareasset/SqliteSoftwareAssetRepositoryIntegrationTest.java`
- `backend/delveforge-app/src/test/java/com/ayywl/delveforge/app/api/softwareasset/SoftwareAssetControllerIntegrationTest.java`
- `frontend/src/state/session.ts`
- `frontend/src/views/DiscoveryView.vue`
- `frontend/src/style.css`
- `frontend/tests/discovery-messaging.test.ts`
- `frontend/README.md`
- `docs/ARCHITECTURE.md`
- `docs/validation/frontend-ux-registration-discovery.md`

## Browser verification and remaining limits

An isolated localhost preview mounted the real Discovery view/session with controlled synthetic responses
and no backend connection. Browser interaction verified immediate message insertion, cleared/editable
composer, disabled sending while pending, waiting indicator, returned question, accepted marker, retained
draft across the returned revision, inline connection-loss treatment, and read-only status checking that
keeps uncertainty. The temporary preview file and server were removed/stopped afterward.

This is synthetic browser verification, not real Provider E2E or deployment acceptance. Actual reduced-motion
OS behavior, mobile layouts, and the user's normal backend/browser combination still need manual validation.
The running main instance was not replaced, restarted, migrated or exercised with mutation requests.
