# Frontend visual redesign — validation and handoff

2026-10-31 · local review candidate · not final visual acceptance

## Revision and scope

- Starting branch/HEAD: `main`, `a3a48c41254c0173c370580d818d8774cf765fc9`, upstream `origin/main`.
- Tracked files were clean. Existing untracked `frontend/design/` and `grilling/` were protected.
- Work branch: `codex/frontend-visual-identity`. Implementation commit: `faa5497`.
- Changes are Vue presentation, shared CSS, SVG identity, HTML metadata, a small responsive
  focus workflow, regression coverage, isolated visual fixtures and visual documentation.
- Backend Java, migrations, API clients, session business logic, provider prompts, Agent
  protocol, Candidate/Delivery states and authorization requirements were not changed.
- No paid AI requests, production database access, Working Copy mutation, push, merge,
  publication or deployment occurred.

## Review package

Open [the screenshot gallery](review-20261031/index.html). It includes 27 final screenshots:
all nine views at 1440×900, 1280×800 and 420×900, with the latter two values describing the
viewport rather than the full-page image height. Four representative baseline screenshots,
the brand exploration/size sheet, empty/deleted/error states, text scaling and long-evidence
checks are included. The committed package is approximately 4.1 MB; transient browser
profiles, inspector films, tool checkouts and executables remain outside Git under `target/`.

All screenshots are **fixture-backed renders of the real application**. After screenshots
use the production `dist/` build, original Vue components and the existing API/session code.
The local fixture server intercepts every `/api` request, refuses mutations and never proxies
to the user's backend. No screenshot is evidence of a real backend or Provider run.

Synthetic content uses Report Kit, CLI log reader and API contract examples, `/sample/…`
paths, fixed timestamps and repeated synthetic revisions. Candidate explicitly retains
`NOT_TESTED`; a blocked Delivery and formally accepted Delivery are separate scenarios.
The accepted fixture has consistent Step and WorkingCopy revisions. The baseline fixture's
navigation counts were subsequently corrected; differences in synthetic deletion warnings
must not be interpreted as a product behavior change.

The complete baseline set remains in
`target/frontend-visual-identity-20261031/before-corrected/` (27 screenshots). Final full
captures are in `production-final/`; older `before/`, `after-round1/` and `final/` captures
are intermediate evidence and must not be used as the final state.

## Actual checks

| Check | Result and scope |
|---|---|
| Baseline `pnpm test` | 21 files, 298 tests passed |
| Final `pnpm test` | 22 files, 299 tests passed; new mobile-menu/focus regression |
| `pnpm typecheck` | Passed |
| `pnpm build` | Passed; Vue/TypeScript and Vite 8.3.0 |
| Final production bundle | 267.06 kB JS / 92.86 kB gzip; 95.60 kB CSS / 18.63 kB gzip |
| `git diff --check` | Passed |
| Final browser captures | 27/27 without document horizontal overflow or JavaScript exceptions |
| Additional independent browser review | Nine states at 320px, five at 768px; no document overflow or unnamed sampled buttons |
| Native confirmation dialog | Direction confirmation opened; focus was inside; Escape closed it; original button regained focus |
| Mobile and breakpoint focus | Menu expands/collapses, stage selection focuses main; desktop-to-mobile focuses visible menu, reverse transition focuses brand |
| Keyboard entry | Tab reaches skip link; Enter focuses main content |
| Text scaling | All nine states at 1440×900 with computed text sizes doubled; no document overflow. This is text-only enlargement, not a claim of browser-menu zoom testing |
| Long source reference | 769-character unbroken evidence path remained complete and did not overflow at 420px |
| Large Evidence collection | 25 records / 5 pages; six records per page; complete 610-character source; next-page action focused the page-status text without overflow at 420px |
| Reduced motion | Real media preference emulated; zero infinite animations; current activity text remained visible |
| Empty/deleted/error | Real project components rendered empty state, restore action, and HTTP 503 feedback at 420px |
| Visible text contrast | Independent computed foreground/opaque-background checks across nine states found no visible AA failures; clipped `.sr-only` false positives excluded |

Final build and test logs are in the isolated `target/frontend-visual-identity-20261031/`
directory. No backend Maven run was needed for this frontend-only task.

The collection screenshot and record are [here](review-20261031/checks/evidence-collection-420.png)
and [here](review-20261031/checks/evidence-collection.json).

### Regression scenario mapping

The full existing suite remains active. `projects`, `session` and `app` tests cover project
create/open/delete/restore, navigation and restored contexts. Discovery messaging/continuity
and view tests cover conversation, review and revision-bound confirmation. Asset authorization,
repository compatibility, workflow and Step authorization tests cover registration, read-only
analysis entry, Select/Reject confirmation, preparation and explicit Step decisions.
Agent workflow/timeline/result tests cover start/stop, persistent activity/reconnect,
Candidate vs Delivery, accepted changes, failures and uncertainty. Evidence and long-content
tests retain complete disclosures and collection navigation. These are deterministic frontend
regressions; they do not substitute for a new live backend end-to-end run.

## Upstream tool security and actual use

Sources were read from the current upstream repositories, including installers, scripts and
relevant references:

- [Taste Skill](https://github.com/Leonxlnx/taste-skill), reviewed commit
  `12af471f627f866411b4bd785b19646d3672784c`, plugin version **2.1.5**.
- [Impeccable](https://github.com/pbakaus/impeccable), reviewed commit
  `df3a4abe7d0840d5f60324812c432e00b66a4e75`, skill **4.8.0**, engine **0.1.18**.

Environment: Node **22.16.0**, pnpm **12.9.1**, Codex CLI **0.153.4**, installed Chrome and Edge.
Codex exposes the plugin marketplace/add commands, but Taste's plugin route requires a fresh
session. The reviewed skill-copy route was used instead.

Taste's official `skills` npm package **1.7.4** was packed with scripts disabled and inspected
before execution. Its shipped CLI, dependency declarations and copy behavior were reviewed.
The direct unpacked CLI first failed because dependencies were absent; the fixed-version
`npx --yes --ignore-scripts skills@1.7.4 add <reviewed-local-checkout> --skill
design-taste-frontend --agent codex --global --copy --yes` installation then succeeded with
telemetry disabled. The installer warned that it declares Node ≥22.20.0; this machine has
22.16.0. Installation, the installed `draw.mjs --app`, and installed browser inspector all
actually completed. No Node upgrade or runtime frontend dependency was added.

Impeccable's official `install --providers=codex --scope=global` route was verified against
the current Rust installer. The actual command added `--no-hooks --yes` and used the reviewed
native engine. The Windows executable's SHA-256 was checked before execution:
`8f61e96b0c97001546a824d79b44f46b423b560f3eb1a0cc44a8695a1b395d9f`.
An interrupted download was resumed and verified. The native installer and global payload
installation completed with exit 0. It installed skill 4.8.0 and engine 0.1.18.

Both skills reside under `C:/Users/28226/.agents/skills/`. No automatic hook was enabled.
Upstream documents Codex project-hook trust approval and Windows limitations, so manual
detection was used. No Impeccable initialization, root `PRODUCT.md`, root `DESIGN.md`,
competing product record, global npm install or package/lockfile change was introduced.

Actual invocations:

| Capability | Evidence |
|---|---|
| Taste App Screen | Installed `draw.mjs --app --list kinds`/App sheet; `framed-sheet`, seed 31031, brand palette/type preserved |
| Taste browser inspector | `inspect.mjs --app URL --widths 1440,1280,420 --out …`; all nine production views covered |
| Impeccable context | Installed launcher `context --target frontend/src/App.vue`, exit 0; correctly resolved `docs/PRODUCT.md` |
| Impeccable source detector | Installed launcher `detect frontend/src/ --json`, exit 0, `[]` |
| Impeccable browser detector | Attempted production Evolution URL at 420×900; exit 1: `Navigation timeout of 30000 ms exceeded`. Continuous SSE is present; the exact cause was not established |
| Critique and audit | Two isolated assessments followed the installed playbooks; design assessment was completed before detector synthesis |
| Shape and refinements | Shape principles produced the internal brief from the supplied request; distill/polish/typeset/layout/adapt/harden informed targeted changes. No interactive shape approval or unrelated init flow was run |

The context tool reported its own `product-schema-legacy` diagnostic. The existing project
document remains authoritative and was preserved as explicitly requested; no schema rewrite
was inferred from that tool diagnostic.

## Critique, audit and corrections

Independent design assessment scored the then-current UI **28/40** across the ten Nielsen
heuristics. It identified stronger delivery navigation, fewer competing scroll regions,
more readable consequential copy, and quieter replanning as useful improvements. The primary
implementation pass addressed all four: direct files/Delivery link, naturally growing
direction panes, 13px important supporting text, and a secondary replanning disclosure.
This is not a new acceptance score for the corrected UI.

The independent technical audit and direct checks found no verified blocking issue in the
sampled states. Bounded audit assessment: accessibility 3/4, performance 4/4, theming 3/4,
responsive 3/4, implementation integrity 3/4 (**16/20**); a complete accessibility conformance
claim is not made.

The first static detector reported eight thick-side-border warnings and one overshooting
easing advisory. Borders and easing were corrected; the final source scan is empty.
The browser inspector found a local-navigation shadow and inconsistent monospace families;
both were corrected. A contrast warning on hidden new-project form labels led to an explicit
light form surface. A 320px refresh-label wrap was fixed without dropping its meaning.

Latest relevant Taste reports have **zero FAIL findings**. Remaining aesthetic warnings are
retained with their context; no broad detector ignores were added:

- Discovery's conversation and independently useful inspector have different natural heights.
  Aligning to the top avoids inserting empty stretched space.
- Repository `hero-void` is a page-oriented heuristic applied to a technical reading pane;
  the screenshot retains real purpose, capability rows and revision context rather than
  adding fabricated content. The pale warm surface is an authorization/risk notice, not
  the page canvas (`palette-cream`).
- Execution's stronger top rules distinguish activity from accepted files; the remaining
  `heavy-borders` warning describes these group markers and the graphite navigation separator.
- A `panel-void` warning on accepted-file results corresponds to separation before test evidence.
  The complete result and `NOT_TESTED` text remain visible.

Some source file-type metadata retains 11px text. Physical touch, a full screen-reader pass,
and a new live backend/paid Agent run were not performed. These limits do not change the
existing automated business regression results. Final visual acceptance remains exclusively
the user's decision.

## Preservation and reproduction

SHA-256 inventories of all eight pre-existing files in `frontend/design/` and `grilling/`
matched after implementation. Neither directory was deleted, moved, overwritten, staged or
committed. No `git clean` or destructive reset was used. All screenshot content was inspected
for actual local paths, credentials and identities; committed images contain synthetic data.

From the repository root:

```powershell
node frontend/scripts/visual-review.mjs
# http://127.0.0.1:4178/?visual=projects
```

For production verification, run the existing `pnpm build` in `frontend/`, then:

```powershell
node frontend/scripts/visual-review.mjs --production
# http://127.0.0.1:4179/?visual=projects
```

Other fixture values: `discovery`, `review`, `repository`, `directions`, `planning`,
`evolution`, `candidate`, `delivery`, `empty`, `deleted`, `error`, `stress`.
The server is loopback-only and uses its own origin; it clears storage only on that
dedicated fixture origin. Stop it with Ctrl+C. Review servers started during this task
are stopped before handoff; no service is deployed.

Questions skipped: the supplied brief already fixed the scope and design direction.
