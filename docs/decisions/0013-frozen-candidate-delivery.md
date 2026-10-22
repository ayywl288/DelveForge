# ADR-0013: Frozen Candidate Delivery

Date: 2026-10-22

Status: Accepted for Task 2 implementation; acceptance evidence is tracked separately.

## Problem and constraints

Task 1 freezes A / D / B without changing the official WorkingCopy. Task 2 explicitly
replaces the legacy M4/M5 execution lifecycle. Git publication and SQLite acceptance
cannot share an atomic transaction. Neither a successful model response nor an exception
proves the resulting software state.

## Decision

Application owns Delivery through a dedicated Workspace port and a durable SQLite journal.
Candidate remains immutable. A Candidate has one durable Delivery identity. Its reservation
excludes Agent and legacy mutations. Intent is committed before writing official Git objects,
index, worktree, or references. Replay uses an independent index; actual index and full tracked
content are independently checked against B. Commit-tree uses a controlled system identity;
reference publication uses compare-and-set from A to recorded C. Hooks and unsafe attributes
remain prohibited. SQLite accepts Step success and both WorkingCopy revisions in one short
transaction with the Delivery completion event and reservation release.

On restart, inspect the journal, actual HEAD, index, content, authorization, and database facts.
A clean A can resume; B with HEAD A can finish publication; a clean recorded C can finish
SQLite acceptance. Partial or foreign content is retained as RECOVERY_REQUIRED. Never reset
or blindly reapply a patch. A proven pre-mutation rejection is DELIVERY_BLOCKED. An uncertain
transaction is resolved by reading durable facts, not by interpreting its exception.

Step owns confirmation/order and final acceptance; AgentRun owns coding attempts; Candidate
owns frozen source/evidence; Delivery owns Git publication and consistency. Agent failures
leave the confirmed Step available for a new Run. No automatic later Step execution.
NOT_TESTED remains evidence, not PASSED; unresolved failures prohibit acceptance.

Persisted Agent events also carry Delivery events, sharing a monotonic per-query cursor.
SSE repeatedly reads bounded persisted pages, eliminating a separate replay/live handoff.

### 2026-10-23: non-destructive mutation boundary

Independent review demonstrated that `read-tree -m -u` overwrites a newly created ignored
destination. `git apply --index` is also insufficient as a mutation primitive: its creation
fallback can rename with replacement, and modifications remove the old pathname first.
Official Delivery therefore renders only changed B files in disposable Git metadata, then
uses a narrow native filesystem boundary; Git never checks out official source paths.

JNA 5.17.0 is an Infrastructure-only dependency for OS handles and no-replace operations.
Java 21 does not expose Windows directory pinning or handle-based deletion; Git does not
provide the required no-replace contract. A new shell/native executable and a general
filesystem transaction library were rejected. The cost is a native runtime library and
explicit platform compatibility checks, with no change to module direction or storage.
JNA is dual-licensed Apache-2.0 / LGPL-2.1-or-later; no native executable or library is vendored.

Windows pins canonical, non-reparse directories without delete sharing, opens existing
files exclusively without truncation, compares their exact proven baseline bytes, creates
additions with CREATE_NEW, and deletes only through the verified open handle. Linux x86-64 uses
openat with O_NOFOLLOW, exclusive creation, and renameat2(RENAME_NOREPLACE) to capture old
entries before replacing them; captured bytes are checked and retained in Git metadata.
An unexpected captured entry is restored only with no-replace, or retained for inspection
if the original path has already been occupied. These are recovery artifacts, not another
WorkingCopy or Candidate bundle. Other platforms or missing native capabilities fail closed.
The native boundary also checks the official index bytes but never rewrites the index in
place. After source mutation succeeds, `git apply --cached` applies the frozen D using Git's
own index.lock and atomic index publication, with no worktree write. Failure at this point
retains the incomplete marker and cannot advance trust.

An on-disk incomplete-mutation marker is created before any official file/index mutation.
It is removed after full B/index/content proof, or when the native boundary proves that
no official file/index mutation started. Its remaining presence always yields UNKNOWN,
including restart after a partial write that happens to resemble B. No automatic reset,
cleanup, or acceptance bypasses this marker. Completed apply followed by a missing SQLite
checkpoint remains recoverable through the existing A/B/C protocol.
An untouched rejection records MUTATION_NOT_STARTED. If external material made inspection
UNKNOWN, the reservation stays RECOVERY_REQUIRED. Only an explicit retry, fresh authorization
and a fully proven clean A may resume that specific rejection after the external conflict is
resolved. Other regressed or incomplete mutations remain blocked; startup does not retry
RECOVERY_REQUIRED.

These protections address pathname replacement, ignored/untracked collisions and changed
tracked bytes at the mutation boundary. They are not a universal transaction against hostile
writers with pre-existing descriptors, writable mappings, direct filesystem access or changes
after the handles close. Linux captures preserve displaced bytes; Windows sharing excludes
ordinary concurrent opens. Unprovable content, index or path identity remains RECOVERY_REQUIRED.

## Alternatives and trade-offs

Keeping legacy execution would create two competing authorities. An in-memory lock or a
Git-first/SQLite-first write alone cannot survive restart. A second source bundle duplicates
the frozen Candidate. Automatic hard reset could destroy external changes. The journal adds
one persistent workflow; conservative recovery may require user intervention for ambiguous
partial mutations, preserving their exact state.
