# ADR-0014: Repository compatibility and trusted preparation baseline

Date: 2026-10-24

Status: Accepted for this isolated implementation branch; independent review and merge are separate.
Validation: repository compatibility and preparation（Git History）.

## Current problem and existing constraint

Provisioning initializes a WorkingCopy at analyzed commit A. The Agent rejects tracked Maven target output,
all large baseline files and a total above 16 MiB. Normal Maven repositories may therefore fail before coding.
INV-W09 still requires sourceRevision to equal the RepositoryProfile analyzedRevision; that evidence must
never be relabeled as analysis of another revision. Delivery requires an exact trusted parent and tree replay.

## Decision

Keep sourceRevision=A and add immutable PreparationProvenance with preparedRevision=P. Identity preparation
has P=A. A deterministic policy may remove only individually proven Maven-generated entries and add narrow
ignore metadata inside the managed independent clone. It creates P as a commit with sole parent A. Persist
A/P, policy version and exact changed paths with activation in one transaction. Initial currentRevision and
lastVerifiedRevision are P; subsequent Step baselines follow the accepted current trusted revision.
Candidate Diff replays P to B; Delivery C has parent P and tree B for the first Step. Analysis evidence remains A.

Workspace owns pinned Git inspection and preparation, Application owns use cases and ports, Domain owns
provenance semantics. No reverse module dependencies, host project builds, model calls or new provider are
introduced. Preparation does not authorize or start a Step. Original repositories remain read-only.

## Alternatives and trade-offs

Manual cleanup/commits violate the requested normal workflow. Deleting every target directory could destroy
modules or resources. Pretending A=P destroys traceability. A new generic recovery engine is unnecessary.
The chosen policy requires exact evidence for deletion and explicit blockers for ambiguous entries. Persisted
legacy rows with no preparation columns mean P=A; no historical record or occupied WorkingCopy is rewritten.
Failed activation discards only this invocation's owned clone; interrupted or unknown directories are retained,
never adopted as trusted state merely because a path or commit exists. Concurrent preparation uses distinct
owned directories and the existing atomic activation CAS; no automatic Step retry or authorization reuse.

## Affected areas

WorkingCopy, PlanActivationPolicy, provisioning/read ports, Git Workspace, Docker source capture, Maven
inspection, SQLite activation persistence and migration, existing API/UI preparation flow and regression tests.
The validation report must distinguish implemented guarantees, executed tests and remaining unsupported cases.
