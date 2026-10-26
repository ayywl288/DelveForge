# Independent fresh multi-step E2E acceptance

Date: 2026-10-26, Asia/Shanghai. Branch: `codex/independent-multistep-e2e`.
Starting implementation: `b57cdbbb9455d3f1344134ab9a6976aa86a754d0`.
Local evidence: `E:/develop/DelveForge/data/independent-multistep-20261026/`.

## Status and boundary

Environment preparation is complete; genuine acceptance is waiting for a credential.
No acceptance PASS or FAIL has been established.
No paid AI request, target implementation, generated Plan, Candidate or Delivery exists for this experiment yet.
The new process, user and machine environments have no `DEEPSEEK_API_KEY`; a credential input is pending.
FinTrack domain data and execution state are not reused. No merge or push is authorized.

Codex acts as the simulated user, environment operator, observer and external acceptance reviewer.
Only DelveForge/DeepSeek may plan, implement and test the target functionality.
The generated Plan will be retained without editing, splitting, merging or regenerating its Steps.
An unsuitable or single-step Plan will be reported honestly. External review will not trigger another evolution round.

## Frozen persona and original needs

This persona was recorded before User Discovery, Product Direction generation or implementation planning.
Its source file is `persona-frozen.md`, SHA-256
`a5388af4997fe24c3fc9285dc56d4a63cbea3529cf9f0fe24d8d24b8ae63e57f`.

Lin, 24, is a junior Java developer preparing a portfolio while volunteering for a neighborhood reading club.
She runs two club activities a month, studies on weekday evenings, checks tasks on her laptop each evening,
and plans the next week on Sunday. Tasks and associated notes are scattered between phone notes and chat messages.

She knows Java, Spring Boot, SQL and basic HTML/CSS, and can run a local Maven application.
She cannot maintain multiple servers, paid SaaS integrations or a complex frontend build system.
This is personal planning, not a multi-team platform. Account privacy must remain meaningful.

Original needs:

- Set and edit task deadlines; distinguish overdue and upcoming work without opening every item.
- Review the coming week's work and see whether completing tasks reduces the remaining workload.
- Preserve existing task descriptions and associated notes.
- Preserve tasks and planning information across application restarts.

The original browser acceptance is to register/login, create overdue/upcoming/later/completed tasks with notes,
change a deadline, complete an item, and use the generated planning/overview functionality to decide what to do next.
Restart must preserve saved data; a second account must not mix personal tasks in the main planning view.
There is no prescribed Step count, code structure, schema, API, library or test implementation.
UI wording and product design remain the responsibility of the generated Direction and Plan.
Original requirements will not expand after Plan confirmation.

## Repository selection and preservation

Selected candidate: [KhaledAshrafH/Taskarium](https://github.com/KhaledAshrafH/Taskarium).
Actual cloned HEAD: `91f69ab1d7ab3a654814a1971cd3871ab0dffb1d`.
The checked-in MIT license grants the relevant local modification rights; the experiment retains its notice.

Actual source includes Spring Boot 3.2.5, Maven, Java 17, Thymeleaf pages, H2 file persistence,
registration/login, task CRUD, task notes, user ownership and role-based routes.
It is a small complete application with existing business behavior, rather than an API-only example.
The POM also explicitly selects compiler source/target 11; compatibility must be decided by DelveForge.
Bundled binary H2 files will be preserved; final usability will use isolated synthetic runtime data.
No repository POM, dependency, source, configuration or Git history is manually adapted.

The untouched clone lives at `original-taskarium/`; `original-before.json` records file sizes and SHA-256 hashes.
`main-untracked-before.json` records the original main checkout's unrelated untracked drafts.
Existing databases, worktrees and reports are retained.

## Fresh verification and interventions

- Frontend: locked offline dependency installation, typecheck and production build passed.
- Frontend: 17 test files / 253 tests passed in this new checkout.
- Supported startup image checks built and verified current-source JDK 17/21 images using real Docker,
  protocol v3, script hashes and Java version checks. Image identities are in `verified-agent-images.json`.
- Backend: fresh `mvnw.cmd -o verify` passed, exit 0, 165 XML reports; 1,699 total tests,
  33 gated skips, 1,666 executed, zero failures/errors. Logs, XML archive and exact skipped suites
  are retained in `delveforge-backend-verify.log`, `backend-test-xml.zip` and `backend-verification.json`.
  The skips include live Provider, Docker and public-repository gates; they are not fresh passes.
  Built executable JAR SHA-256: `e70dac9bb39a01beb4e669c1692263629ddcaa6bcfe831e741a2ecbdb327839e`.
- No DelveForge product fix or target business-code intervention has been made.

The newly built application started against a previously nonexistent isolated `product.sqlite`,
with new WorkingCopy and Agent roots, a new owner/cache identity and no existing-roots fallback.
Normal API reads returned empty project and asset lists. Before spending on discovery, the public clone
was registered through the ordinary asset API with explicit read permission and MIT usage authorization:
asset `93d346df-8444-44e0-aa8b-cbf443ca33e7`.

The ordinary read-only compatibility endpoint returned `COMPATIBLE`, policy `maven-generated-v1`,
root Maven module, automatically selected JDK 17, 73 files / 752,250 bytes, 67 text / 6 opaque files,
and no generated paths or blocking issues. Binary resources and credential-sensitive files remain
immutable under the existing policy; no source or configuration was adapted to obtain this result.
Compatibility is not a build or business-usability result for Taskarium.

After these keyless checks the isolated Java process was stopped, leaving its port available for secure startup.
Read-only SQLite inspection found integrity `ok`, zero foreign-key issues, one SoftwareAsset and zero UserProfiles,
RepositoryProfiles, Directions, Plans, WorkingCopies, AgentRuns, Candidates or Deliveries.
A consistent SQLite backup is retained as `pre-discovery-consistent-backup.sqlite`.
The original clone's complete source-file size/hash snapshot is unchanged. Paid model calls: **0**.

`acceptance_driver.py start` prompts for the DeepSeek key without echo and places it only in the new Java
process environment. It does not save the key to a file, command argument, log or report.
The startup record contains only credential presence and non-secret settings.
This helper controls the environment and captures normal HTTP evidence; it does not implement target features,
rewrite a Plan, edit repository source or mutate domain records through SQLite.

Initial Git clone and Docker inspection were denied by the restricted tool environment; the same authorized,
scoped operations succeeded with normal host access. These are environment execution limits, not product failures.
An operator helper initially used a Python standard-library module name and was renamed before use;
this did not change DelveForge or Taskarium.

## Pending acceptance evidence

Fresh User Discovery and confirmation, repository analysis, Directions and selection, original generated Plan,
WorkingCopy preparation, each ordered Step's Agent/Candidate/Delivery, cumulative Git and SQLite proof,
real browser usability, actual model/tool/token totals, final verdict and task closeout remain pending.
