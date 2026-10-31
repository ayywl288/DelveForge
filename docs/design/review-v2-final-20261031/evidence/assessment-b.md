# Assessment B — independent technical evidence

Impeccable 4.8.0; target `frontend/src`; derived slug `frontend-src`. Assessment A output was neither received nor read. The supplied Operate brief substituted for an interview in this delegated assessment. Questions skipped: this is the independent evidence subtask; the parent owns synthesis and close-out.

## Scope and method

The latest matrix combines 25 unchanged-state baseline measurements with 40 final-build measurements: 13 states × 1440/1280/768/420/320 CSS px, height 900, actual `devicePixelRatio = 1`. States: projects, discovery, review, repository, directions, planning, evolution, candidate, delivery, initial, initial-authorization, plan-proposed, review-incomplete. Raw baseline and final measurements remain separate, so their chronology is explicit.

All browser traffic used the isolated production fixture on 127.0.0.1:4179. Fixture writes were refused; no real backend, provider, repository mutation, or actual Agent execution was used.

The native IAB path was tried first in a fresh hidden tab. The required mutable injection preflight failed with `TypeError: Cannot set property title of [object Object] which has only a getter`. No reliable user-visible overlay exists. No live server was started, no detector script was injected, and no entry source or hooks were changed. CDP measurement in a separate temporary Chromium profile is the explicit fallback; this is not a detector overlay or pixel-contrast scan. The native temporary tab and owned Chromium profiles were closed; the existing fixture server was left running.

The source detector ran once against `frontend/src`, returning `[]`: zero findings and zero rule/file locations. It was run before the parent's final narrow Projects/Evolution adjustments. It was not rerun, and its empty output is not presented as a new scan of those later edits. Final browser checks cover those affected states. No prior critique output or Assessment A output was consumed.

## Final audit health

Both localized issues found in the first pass were corrected by the parent and independently rechecked. No P0/P1 technical finding remains open within this measured scope.

| Dimension | Score / 4 | Evidence and limit |
|---|---:|---|
| Accessibility | 3 | Named controls, labels, skip link, keyboard outlines and native dialogs work; both localized contrast issues now pass. This is not full WCAG certification. |
| Performance | 3 | Production assets approximately 273 KB JS + 119 KB CSS uncompressed; short fixture loads, no observed runtime exceptions. No throttled-device, LCP/INP/CLS or real-provider benchmark. |
| Theming | 3 | Coherent graphite/indigo tokens and semantic tones; some local hardcoded surface colors require contrast-specific care. Light is the declared scheme; no dark-mode requirement inferred. |
| Responsive design | 3 | No page overflow in 65 combinations, native dialogs fit at 320, mobile navigation restores focus. Some secondary targets are below preferred 44 px; no WCAG target-size failure inferred from that alone. |
| Implementation integrity | 4 | Coherent product-specific states, explicit authorization/evidence boundaries, and no remaining verified implementation-drift finding in the measured scope. |
| **Total** | **16 / 20** | **Good; bounded browser/fixture evidence, not a complete WCAG or device certification.** |

## Findings

### Resolved P1 — Project name's keyboard outline had insufficient contrast on its dark card

Location: `frontend/src/identity.css:170`, applying to `frontend/src/App.vue:188` (`#current-project-name`, `tabindex="0"`). The actual Tab sequence reaches this title. Computed outline is `rgb(79,88,184) solid 2px`; its adjacent project-card background is `rgb(48,53,67)`. Their contrast is **1.99:1**, below the **3:1** non-text contrast threshold for the author-supplied focus indicator (WCAG 1.4.11). The same outline has sufficient contrast on the light navigation surface, so changing the entire sidebar ring is unnecessary.

Resolution: the parent scoped a light indigo ring to the project heading. The real four-Tab sequence in the rebuilt page reaches `#current-project-name` with `rgb(195,201,255) solid 2px`, against `rgb(48,53,67)`: **7.65:1**, passing. Evidence: `post-fix-recheck.json`.

### Resolved P1 — New-flow caption narrowly missed AA text contrast

Location: `frontend/src/identity.css:99-101`, `.new-project-trigger .meta`, in the closed new-flow disclosure on Projects. The caption “新建用户画像，或复用已确认画像” is 11 px normal weight, foreground `rgb(96,106,126)` on `rgb(230,233,246)`, measured **4.49:1** at every requested width. AA normal text needs **4.5:1**; do not round this up to a pass. This is one shared style, not five independent issues.

Resolution: the parent changed the scoped caption to `rgb(83,93,115)` on the same background: **5.45:1**, passing at all five widths in the rebuilt production fixture. Evidence: `post-fix-recheck.json`.

No P0 or P2/P3 issue is added merely to fill a list. Both P1 findings above are historical and resolved. The 18 px checkbox glyphs have visible associated labels, the 22 px focusable project title is not a pointer action, and inline navigation links can use the target-size exceptions. Those mechanical small-target results are retained as raw evidence, not reported as WCAG failures.

## Verified behavior worth preserving

- **65 latest combinations:** zero document-level horizontal overflow, zero unnamed AX buttons/textboxes/comboboxes/links/checkboxes/radios, zero recorded runtime exceptions.
- **3,637 rendered text-node checks:** hidden ancestors, closed-disclosure bodies, `aria-hidden`, inert, sr-only/visually-hidden, decorative SVG and disabled controls excluded. Ancestor background alpha is composited. The one repeated caption style above is the sole below-threshold text finding.
- **1,095 computed control-text checks:** zero below-4.5 ratios. This supplements the text-node check; nested caption text is tested separately. It does not assert that all decorative borders must meet 3:1.
- **Real keyboard input:** skip link receives the first Tab with a visible 2 px outline. Subsequent Tab checks at desktop/tablet/mobile show the current control receiving a visible outline. At 768/420/320, selecting the current stage closes the mobile menu and focuses main content. Crossing to desktop restores focus from the disappearing toggle to the visible brand.
- **Native detail dialogs:** opening focuses Close; Tab traverses the close/reading surface; Escape closes and returns to the exact originating project-details button at 1440/768/420/320. Browser-chrome focus transitions are recorded as document BODY and are not misreported as access to background controls.
- **Initial paid action:** the “确认 API 费用与预算” dialog is a named AX modal, initially focuses “返回审阅”, fits 320 CSS px, and its final confirmation is disabled before acknowledgment. No acknowledgment or paid execution was submitted.
- **Discovery pending:** the real frontend renders three waiting dots inside the existing conversation log. Reduced motion yields `animation-name: none` with static visible dots and a textual waiting alternative. The fixture's delayed 409 produces explicit uncertain-result wording and no automatic replay; the submitted text remains visible in the conversation.
- **Long repository evidence:** the 25-record fixture opens its evidence section with six records per page, five pages, and no horizontal overflow at 320.
- **Synthesized Chromium touch:** a vertical swipe begun over the short timeline moves the document (`scrollY` 598→1360). That fixture's timeline has no internal overflow (78 px content/78 px viewport), so it proves page-scroll cooperation only, not internal timeline scrolling or interrupted custom drags.

## Final narrow recheck after fixes and metadata additions

The latest production build was checked again for Projects/Candidate/Delivery at all five widths: 15 additional combinations, all without page overflow or runtime exceptions. The caption and actual project-title Tab outline now pass as described above; no source-detector rerun was performed.

At both 1440 and 320, expanding Candidate's “冻结版本与完整性” reveals its ID, 40-character baseline, 40-character expectedTree and 64-character SHA-256 without overflow. Delivery displays accepted deliveryRevision and baselineRevision, without replacing the accepted-file summary with the Candidate list. The Candidate diagnostic keeps the raw `WORKING_COPY_BUSY` cause, explicitly identifying it as a backend-recorded reason. The new “交付需核对：查看原因、证据与代码结果” link updates the hash and focuses `#agent-changes-title`; no unsupported recovery semantics were inferred. This is verified presentation and navigation, not proof of real recovery or delivery behavior. Evidence: `metadata-check.json`.

## Exact limits

These are Chromium emulated viewports, not physical iPhone/Android devices, Safari or Firefox. No NVDA/JAWS run, forced-colors run, 200% text-zoom check, low-end CPU/network profile, or pixel-sampled screenshot contrast analysis was performed. No custom drag control was exercised; the timeline case above is a synthesized touch-scroll check. The source detector's empty result is separate from browser measurements and does not override verified CSS findings. Real backend/provider status, persistence, costs and execution correctness are outside this fixture review.

## Evidence files

- `detector-source.json`
- `measurements-baseline.json`, `measurements-final.json`
- `latest-summary.json` (65-combination consolidation)
- `interactions-baseline.json`, `interactions-final.json`
- `targeted-interactions.json`, `edge-interactions.json`
- `post-fix-recheck.json`, `metadata-check.json`

Review tooling and evidence only were written under this `audit/` directory. No source edits or Git operations were performed by Assessment B; prior report directories, frontend/design and grilling were not modified.
