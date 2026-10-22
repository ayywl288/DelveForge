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

## Alternatives and trade-offs

Keeping legacy execution would create two competing authorities. An in-memory lock or a
Git-first/SQLite-first write alone cannot survive restart. A second source bundle duplicates
the frozen Candidate. Automatic hard reset could destroy external changes. The journal adds
one persistent workflow; conservative recovery may require user intervention for ambiguous
partial mutations, preserving their exact state.
