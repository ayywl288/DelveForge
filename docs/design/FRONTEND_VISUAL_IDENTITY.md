# DelveForge Frontend Visual Identity

2026-10-31 · Editorial Engineering Workbench · local review candidate

This specification owns presentation choices only. [PRODUCT.md](../PRODUCT.md),
[ARCHITECTURE.md](../ARCHITECTURE.md), and [DOMAIN_MODEL.md](../DOMAIN_MODEL.md)
remain authoritative for product behavior, boundaries, authorization, and state.

## Brief and direction

The Evolution Workbench makes code evolution legible through paths, evidence,
decisions, and trusted results. The audience is a developer spending a sustained
session understanding an existing repository and making explicit engineering decisions.
The interface operates and reads; it does not promote or simulate a finished product.

Three directions were compared:

| Direction | Strength | Decision |
|---|---|---|
| Editorial engineering workbench | Light reading surfaces, quiet structure, persistent graphite navigation, clear technical context | Selected for long Chinese/English reading and controlled decisions |
| Dark data terminal | Strong live-operation focus and concise technical density | Useful reference for the activity console; unsuitable as the whole reading experience |
| Quiet productivity ledger | Familiar neutral lists and forms | Clear but gives the evidence-to-evolution path too little identity |

Taste's `framed-sheet` App Screen sheet (seed 31031) informed flat surfaces,
aligned edges, and subdued chrome. The user's graphite navigation and light canvas
take precedence over that sheet's light L-frame. No dealt charts, vanity metrics,
search controls, or unrelated signature elements were introduced.

| Surface | First question | Leading material |
|---|---|---|
| Projects | Where can I continue? | Project identity, saved stage, accepted Step count and open action |
| Discovery | What problem am I trying to solve? | Saved conversation and six-dimension inspector |
| Profile Review | Is this revision accurate? | Six editable sections, revision, explicit confirmation |
| Repository | What can this code support? | Repository identity, analyzed revision, sections and source evidence |
| Directions | Which direction deserves investment? | Candidate list, selected state, rationale and explicit decision |
| Planning | What is the next bounded increment? | Current/target comparison, ordered Steps and prerequisites |
| Evolution | What actually happened, and what can I do? | Authorized Step, Run activity, Candidate, Delivery and accepted files |

## Color and contrast

`frontend/src/identity.css` is the current identity layer, loaded after the existing
layout stylesheet. Shared semantic variables also govern component-scoped styles.

| Role | Token/value | Usage |
|---|---|---|
| Ink / navigation | `--ink`, `--nav`: `#17282C` | Primary text, graphite shell, project open action |
| Canvas | `--canvas`: `#F5F6F3` | Working background |
| Surface | `--surface`: `#FFFFFF` | Reading and form surfaces |
| Primary action | `--accent`: `#176E67` | Deliberate action, links, selected route |
| Hover | `--accent-hover`: `#11564F` | Action hover and selected text |
| Secondary text | `--muted`: `#5A686A` | Supporting text and metadata |
| Selection | `--selection`: `#EAF2EE` | Subdued selected content |
| Warning | `--warning`: `#855025` on `#FBF3E9` | Constraints, uncertain results and risk |
| Danger | `--danger`: `#B54145` on `#FFF1F1` | Destructive controls and failures |
| Success | `--success`: `#276849` on `#EDF5EE` | Accepted facts; text always names the fact |
| Navigation secondary | `--nav-muted`: `#B9C8C5` | Readable text on graphite |
| Control border | `--control-line`: `#80918C` | Inputs and outlined refresh control |

Normal text targets at least 4.5:1; essential control boundaries and focus indicators
target at least 3:1. Structural separators may be quieter because they are not the
only sign of interaction. Color never carries status alone. Disabled controls retain
legible text and existing explanations. No dark-mode switch is introduced.

## Typography and rhythm

- UI: Segoe UI, PingFang SC, Microsoft YaHei, sans-serif; no external font requests.
- Code, paths and revisions: Cascadia Code, Consolas, SFMono-Regular, monospace.
- Page title 26px / 23px narrow; section title 18–20px; body 15px with generous Chinese
  leading; supporting copy 13–14px; compact metadata 12px. Small native status/file
  labels retain their existing compact treatment. Tabular figures keep counts stable.
- English stage names support the Chinese title and wrap below it when necessary.
- Spacing follows a 4px base: 8/12px within groups, 16/24px between related blocks,
  28/32px for separation, 40px desktop content gutters.
- At 1440px: 224px navigation + two 40px gutters + 1136px working content.
  Flexible columns use `minmax(0, …)` and preserve the same content edges.

## Surfaces and interaction

- Resting panels: a quiet 1px rule, 6px radius, no shadow. Controls: 4px radius.
  Dialogs: 8px radius with a true backdrop. No glass, glow, or decorative gradient.
- Navigation uses a neutral brightness change. Ordered stage numbers express an
  actual workflow; stage result dots retain their original accessible text.
- Project rows prioritize identity and real accepted Step counts; the progress bar
  represents the actual count. No invented usage metrics or completion estimates.
- The new-project disclosure is the strongest workspace entry. Expanded forms use
  a light surface. Deleted, empty, conflicting and historical records retain text.
- Profile review uses two editorial columns with rules rather than six nested cards.
  Editing, revision-bound review acknowledgement, and explicit confirmation stay intact.
- Repository content is a technical reading surface. Lists use simple rows; source
  evidence remains fully expandable and paginated with its original references.
- Directions use a list/detail relationship. Both panes grow with the document so
  a short laptop viewport does not create three competing scroll areas.
- Planning uses a current/target comparison and the existing ordered Step path.
  Replanning is a quiet disclosure. Preparation and Step authorization remain separate.
- The execution console groups configuration beside cost/stop context on desktop.
  Actual Run statistics, event connection and activity retain their own labels.
  A non-mutating link takes readers from a Candidate-bearing Run to files and Delivery.
- Candidate, Delivery, accepted Step and `NOT_TESTED` remain distinct. Risk and recovery
  restrictions are never hidden to create a cleaner screenshot.

## Accessibility, long content and adaptation

Native buttons, labels, details and dialogs remain the interaction foundation. The skip
link and visible 2px focus indicators are retained. Confirmation dialogs use their
existing focus return and Escape handling.

At 640px and below navigation folds behind a labelled button with `aria-expanded` and
`aria-controls`. Selecting a stage closes the menu and focuses the main content. If a
breakpoint hides a focused navigation item or the menu button, focus moves to a visible
menu control or brand link. Desktop navigation remains continuously available.

At intermediate widths layouts stack before columns become unreadable. Narrow screens
use 16px gutters, 44px minimum button/disclosure targets and 16px form text. Repository
section navigation may scroll locally; the document must not scroll horizontally.

Unbroken paths and SHA values wrap. Complete evidence, technical identities and
historical details stay reachable through existing disclosures. Long collections retain
their existing pagination/load-more contracts. Text may expand without fixed-height
clipping. The existing component-specific reduced-motion alternatives keep status text
and a static activity indicator; no new entrance animation is added.

## Brand assets

The original Branch-to-Path construction has a bounded starting node, two quieter
alternative routes, and one straight chosen route continuing forward. Three compact
constructions were compared: rounded three-way path (selected), angular fork, and
enclosing route frame. The selected one retains separation at 16/24/32px without a
Git diamond, robot, hammer, brain or sparkle motif.

Assets are in `frontend/public/brand/`: `primary-logo.svg`, `symbol.svg`,
`wordmark.svg`, `monochrome.svg`. `frontend/public/favicon.svg` uses the inverse mark
on graphite. The navigation's `BrandSymbol.vue` uses the same geometry.

The wordmark uses live system-font text; exported SVG text can vary slightly on machines
without Segoe UI. Prefer the supplied compact symbol at small sizes. Monochrome SVG uses
`currentColor` when inline; when embedded as an image it defaults to black. Keep at least
4px clear space at a 32px symbol size. No third-party visual assets or runtime icon/image
libraries were added; existing authored outline navigation icons are retained.

See [validation and evidence](FRONTEND_VISUAL_VALIDATION.md). Final visual acceptance
belongs to the user after independent review.
