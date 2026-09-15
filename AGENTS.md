# AGENTS.md

> This file defines the working rules for Coding Agents operating in the DelveForge repository.
>
> It is not a product introduction or architecture specification.
> Product intent, architecture, domain semantics, and development priorities are defined by the project documents under `docs/`.

## 1. Required Context

Before making any non-trivial code change, read the relevant project documentation.

At minimum, inspect:

```text
docs/PRODUCT.md
docs/ARCHITECTURE.md
docs/DOMAIN_MODEL.md
docs/ROADMAP.md
```

These documents answer different questions:

```text
PRODUCT.md
→ What DelveForge is trying to achieve
→ Who it serves
→ What is in or out of scope

ARCHITECTURE.md
→ How the system is structured
→ Module boundaries
→ Dependency rules
→ Technology constraints

DOMAIN_MODEL.md
→ Core domain concepts
→ State transitions
→ Business rules
→ Invariants
→ Authorization and safety boundaries

ROADMAP.md
→ What should be built now
→ Current milestone
→ Acceptance criteria
→ Definition of Done
```

When a task involves a long-term architecture decision, inspect the relevant ADRs under:

```text
docs/decisions/
```

if that directory and relevant ADRs already exist.

If a new long-term architecture decision is introduced and requires an ADR according to this file's Documentation & ADR Policy, create the directory and ADR as part of that decision work.

## 2. Source of Truth & Conflict Resolution

Project documents are authoritative for their respective concerns.

Use the following responsibility boundaries:

```text
Product behavior / Product scope
→ PRODUCT.md

Architecture / Module boundaries / Dependency direction
→ ARCHITECTURE.md

Domain semantics / State transitions / Invariants
→ DOMAIN_MODEL.md

Development priority / Milestones / Current implementation scope
→ ROADMAP.md

Long-term architecture decisions
→ ADRs under docs/decisions/
```

`AGENTS.md` defines how an Agent works inside those constraints.

If code and documentation disagree:

1. Do not silently choose whichever version is easier to implement.
2. Determine whether the code is outdated or the documentation requires revision.
3. For a small, obvious inconsistency within the current Task, fix the implementation and update the relevant documentation when necessary.
4. If resolving the conflict would change product scope, domain semantics, module boundaries, or a long-term architecture decision, do not make that decision implicitly.
5. Report the conflict and explain the required design change before implementing it.

A user-requested implementation detail does not automatically override an established architecture rule or domain invariant.

If the requested behavior conflicts with an existing invariant or safety boundary, explicitly surface the conflict.

## 3. Core Working Principles

### 3.1 Work Within the Existing Product Direction

The Agent's responsibility is to:

> Implement the current Task within DelveForge's existing product goals, architecture constraints, domain model, and Roadmap.

Do not redefine DelveForge into a different product in order to simplify an implementation.

In particular, preserve the project's core principles:

```text
Personal Before Generic
Evolution Before Rewrite
Evidence Before Recommendation
Human Chooses Direction
Incremental & Verifiable
```

### 3.2 Domain Rules Override Implementation Convenience

Do not bypass domain rules merely because another implementation would be easier.

The following boundaries are intentional and must remain explicit:

```text
User confirmation
Software Asset authorization
Working Copy isolation
Evolution Step scope
Verification
Rollback / Recovery
Historical traceability
```

### 3.3 AI Proposes, Domain Decides

LLM output is not automatically valid domain state.

AI may propose:

```text
User Profile analysis
Repository analysis
Product Directions
Evolution Plans
Code changes
```

but proposals must pass the corresponding application and domain rules before becoming accepted system state.

Do not place domain authority inside prompts or model responses.

### 3.4 Protect the Original Software Asset

Repository Analysis is read-only.

Evolution code changes must occur only inside the Working Copy associated with the Evolution Plan.

Never modify the original Software Asset as a shortcut for implementing Evolution Execution.

### 3.5 Prefer the Smallest Correct Change

Implement the smallest change that fully satisfies the current Task and its acceptance criteria.

Do not enlarge the Task merely because adjacent code could also be improved.

Correctness, safety, traceability, and consistency with the existing design take priority over speculative generalization.

## 4. Before Coding

Before modifying code:

1. Identify the current Task and its acceptance criteria.
2. Check the current Milestone in `docs/ROADMAP.md`.
3. Identify the affected Maven Module, feature, Aggregate, and external adapters.
4. Read the relevant implementation and existing tests.
5. Identify the architecture rules and domain invariants affected by the change.
6. Identify whether the Task changes observable behavior, persisted state, domain state transitions, or external interfaces.
7. Determine the minimum set of files that need to change.
8. Check whether documentation or an ADR must also be updated.

Before editing a domain workflow, explicitly inspect the related state transitions and invariants in `DOMAIN_MODEL.md`.

Before editing module dependencies, external integrations, persistence, AI integration, Workspace behavior, or system composition, inspect the corresponding rules in `ARCHITECTURE.md`.

Before introducing new behavior outside the current Milestone, verify that it is actually required by `ROADMAP.md`.

### 4.1 Major Design Changes

Do not directly implement a change that requires a significant alteration to:

```
Product scope
Architecture boundaries
Maven module dependencies
Aggregate boundaries
Core domain concepts
Domain invariants
Evolution safety semantics
Persistence semantics
Long-term technology choices
```

First surface the design issue.

The explanation should include:

```
Current Problem:
What problem is blocking the current implementation?

Existing Constraint:
Which current architecture rule, domain rule, or product decision is involved?

Proposed Change:
What would need to change?

Why Existing Design Is Insufficient:
Why can the Task not be implemented correctly within the current design?

Affected Areas:
Which modules, domain concepts, persistence structures, APIs, or documents are affected?

Alternatives:
What other approaches were considered?

Trade-offs:
What is gained and what new cost or risk is introduced?
```

Do not disguise an architecture change as a small refactor.

## 5. Scope Control

Only modify code and documentation necessary to complete the current Task.

The Agent must not expand the Task simply because adjacent improvements are possible.

### 5.1 Prohibited Unrelated Changes

Unless explicitly required by the current Task, do not:

- refactor unrelated modules;
- rename unrelated classes, methods, packages, fields, or files;
- reformat unrelated files;
- reorganize package structures;
- change public interfaces that are not relevant to the Task;
- delete code whose purpose has not been understood;
- replace existing patterns with preferred personal patterns;
- introduce speculative abstractions for possible future requirements;
- implement features from future Roadmap Milestones;
- add infrastructure that is not required by the current acceptance criteria;
- upgrade dependencies;
- replace libraries or frameworks;
- change Maven Module boundaries;
- change persistence technology;
- change the selected LLM provider strategy;
- change the Working Copy strategy.

### 5.2 No Opportunistic Refactoring

A Task may expose nearby technical debt.

If the technical debt does not block the Task:

```
Do not fix it as part of the current change.
```

Record it as a possible follow-up when useful.

If a small local refactor is necessary to implement the Task safely:

```
Task
    ↓
Required local refactor
    ↓
Task implementation
```

the refactor must remain limited to the affected area and must not alter unrelated behavior.

### 5.3 Roadmap Boundary

The current Milestone in `docs/ROADMAP.md` defines the development boundary.

Do not implement future Milestone functionality merely because the architecture already anticipates it.

Examples:

```
Architecture supports Recovery
≠
Implement complete Recovery during an earlier Milestone

Architecture supports future Desktop Shell
≠
Create Desktop integration during Core MVP development

Software Asset is extensible
≠
Implement automatic GitHub discovery during MVP
```

Architecture should permit future evolution without forcing future functionality into the current Task.

## 6. Architecture Rules

All implementation must comply with `docs/ARCHITECTURE.md`.

The rules below are especially important because violating them can silently destroy the intended architecture.

### RULE-ARCH-001 — Respect Maven Module Boundaries

The backend consists of:

```
delveforge-domain
delveforge-application
delveforge-infrastructure
delveforge-app
```

Allowed dependency direction:

```
delveforge-domain
        ↑
delveforge-application
        ↑
delveforge-infrastructure

delveforge-app
    ├── delveforge-application
    └── delveforge-infrastructure
```

Interpretation:

```
domain
→ depends on no other DelveForge module

application
→ may depend on domain

infrastructure
→ may depend on application and domain

app
→ composes application and infrastructure
```

Do not introduce reverse dependencies.

In particular:

```
domain
✗ application
✗ infrastructure
✗ app

application
✗ infrastructure
✗ app
```

### RULE-ARCH-002 — Domain Must Remain Technology Independent

`delveforge-domain` must not depend on implementation technologies such as:

```
Spring Web
Controller APIs
MyBatis-Plus
SQLite
DeepSeek SDK
Provider-specific Spring AI APIs
Git libraries
Filesystem APIs used as infrastructure
Shell execution
Build Tool integration
```

Domain objects should express business semantics rather than technical integration details.

### RULE-ARCH-003 — Application Owns Use Cases and Ports

`delveforge-application` owns:

```
Use Cases
Application Services
Workflow Orchestration
Inbound / Outbound Port abstractions
AI Gateway interfaces
Workspace Gateway interfaces
Persistence Repository interfaces
```

Application code may coordinate Domain behavior and external capabilities through ports.

It must not directly instantiate or call concrete Infrastructure adapters.

### RULE-ARCH-004 — Infrastructure Implements External Capabilities

`delveforge-infrastructure` contains implementations for capabilities such as:

```
LLM Provider integration
SQLite / MyBatis-Plus persistence
Git
Filesystem
Shell
Build
Test
```

Infrastructure should implement abstractions owned by the Application layer where appropriate.

Do not move domain decisions into Infrastructure because an adapter has convenient access to data.

### RULE-ARCH-005 — App Is the Composition Root

`delveforge-app` is responsible for:

```
Spring Boot bootstrap
REST / Interface adapters
Configuration
Dependency wiring
Exception mapping
```

Controllers must remain thin.

A Controller may:

```
parse request
validate protocol-level input
invoke application use case
map application result to response
```

A Controller must not become the location for:

```
domain rules
workflow orchestration
repository analysis logic
AI prompt logic
Git operations
business state transitions
```

### RULE-ARCH-006 — Feature-Oriented Organization Inside Modules

Within a Maven Module, prefer organization by Feature or Domain Concept.

Examples:

```
domain
├── user
├── asset
├── direction
└── evolution
```

and:

```
application
├── userdiscovery
├── repositoryanalysis
├── opportunitydiscovery
├── evolution
└── port
```

Do not reorganize the project into a global structure such as:

```
controller/
service/
mapper/
entity/
utils/
```

across the entire system.

### RULE-ARCH-007 — No Generic Common Module

Do not introduce a generic:

```
delveforge-common
```

or equivalent dumping ground.

A shared type must have an explicit semantic owner.

Before creating shared code, determine whether it belongs to:

```
Domain
Application
Infrastructure
App / Interface
```

If ownership is unclear, do not solve the uncertainty by placing it into a generic common package.

### RULE-ARCH-008 — All LLM Access Goes Through AI Gateway

Business code must not directly depend on:

```
DeepSeek SDK
DeepSeek-specific API types
Provider-specific HTTP requests
Provider-specific model configuration
```

The required direction is:

```
Business / Application
        ↓
AI Gateway
        ↓
Provider Adapter
        ↓
External LLM
```

Provider-specific model IDs, endpoints, credentials, and parameters belong to configuration and Infrastructure.

They must not be hard-coded into domain or application logic.

### RULE-ARCH-009 — All Local Code Operations Go Through Workspace

Business modules must not directly execute:

```
Git commands
Shell commands
Build commands
Test commands
Filesystem mutation
Repository checkout
Working Copy creation
```

These capabilities must go through the Workspace abstraction.

This rule exists to keep local code access controlled, testable, and auditable.

### RULE-ARCH-010 — Repository Analysis Is Read-Only

Repository Analysis may request read capabilities from Workspace.

It must not receive or use mutation capabilities.

Repository Analysis must never modify:

```
source files
Git state
configuration
dependencies
generated files
```

inside the original Repository as part of analysis.

### RULE-ARCH-011 — Evolution Execution Is the Controlled Write Path

The Evolution flow is the only business flow allowed to request Workspace code mutation capabilities during MVP.

Even within Evolution:

```
write capability
≠
unrestricted filesystem access
```

Every change must remain constrained by the currently authorized Evolution Step and its Working Copy.

### RULE-ARCH-012 — Do Not Introduce Circular Dependencies

Circular dependencies between:

```
Maven Modules
Feature packages
Application services
Domain services
```

are prohibited.

If two components appear to require each other, reconsider the responsibility boundary rather than introducing a cycle.

## 7. Domain Implementation Guardrails

`docs/DOMAIN_MODEL.md` is the authoritative source for:

```
Domain concepts
Ubiquitous Language
Aggregate boundaries
State transitions
Business rules
Invariants
Domain operations
Authorization boundaries
Evolution safety semantics
```

This file does not redefine those rules.

When implementing or modifying domain behavior, the Agent must identify and inspect the relevant sections and invariants in `DOMAIN_MODEL.md` before changing code.

### RULE-DOM-001 — Preserve Domain Terminology

Use the Ubiquitous Language defined in `DOMAIN_MODEL.md`.

Do not introduce alternate names for established concepts merely because another framework or implementation convention is familiar.

Examples include:

```
User Profile
Software Asset
Repository Profile
Product Direction
Evolution Plan
Working Copy
Evolution Step
Execution Result
Verification Result
Candidate State
```

### RULE-DOM-002 — Enforce Domain Rules Through Domain Boundaries

Do not bypass Aggregate Roots, Domain Policies, or explicit domain operations in order to simplify implementation.

Application and Infrastructure code must not directly force domain objects into states that would normally require invariant validation.

For example, avoid arbitrary state mutation equivalent to:

```
step.setStatus(SUCCEEDED);
```

when reaching that state requires domain validation.

### RULE-DOM-003 — Treat AI Output as Untrusted Proposal Data

LLM output must not directly become trusted domain state.

The implementation should preserve the conceptual boundary:

```
AI Output
    ↓
Parse / Validate
    ↓
Application / Domain Rules
    ↓
Accepted Domain State
```

Malformed, incomplete, contradictory, or invariant-breaking AI output must not bypass domain validation.

### RULE-DOM-004 — Preserve Explicit Human Authorization Boundaries

Do not implement shortcuts that infer or manufacture user authorization.

Where `DOMAIN_MODEL.md` requires explicit user action, the implementation must preserve that action as a meaningful boundary.

This includes, where applicable:

```
Product Direction selection
Evolution Step confirmation
Repair authorization
Retry authorization
```

One authorization must not be silently reused as authorization for a different domain action.

### RULE-DOM-005 — Preserve Working Copy Isolation

Do not implement Evolution code mutation against the original Software Asset.

All Evolution mutations must follow the Working Copy semantics defined in `DOMAIN_MODEL.md`.

Repository Analysis must remain read-only.

Do not bypass Working Copy preparation because direct modification of the source Repository would be easier.

### RULE-DOM-006 — Preserve Verified-State Safety

Do not implement execution logic that allows later Evolution work to continue from software state that the Domain Model does not consider verified.

When changing Evolution Execution, Verification, Repair, Retry, Rollback, or Recovery behavior:

1. inspect the relevant Working Copy and Evolution Step state models;
2. inspect the related `INV-*` rules;
3. preserve the distinction between current software state and trusted verified state;
4. preserve the defined failure and recovery boundaries.

Do not create an alternative interpretation of:

```
baselineRevision
currentRevision
lastVerifiedRevision
Candidate State
Verified State
```

inside Application or Infrastructure code.

### RULE-DOM-007 — Preserve Historical Meaning

Do not overwrite historical domain information when `DOMAIN_MODEL.md` requires it to remain traceable.

This includes, where applicable:

```
referenced User Profile revisions
Repository Profile snapshots
historical Product Directions
historical Evolution Plans
Evolution Step results
authorization facts
failure / rollback / recovery audit information
```

Persistence convenience must not destroy domain traceability.

### RULE-DOM-008 — Asset Authorization Cannot Be Bypassed

Do not treat application-level user confirmation as a replacement for Software Asset read permission, license constraints, or usage authorization.

When a Task affects asset access or Evolution eligibility, inspect the relevant Asset Authorization invariants in `DOMAIN_MODEL.md`.

### RULE-DOM-009 — Domain Model Changes Must Be Intentional

If implementing the current Task appears to require violating or changing an existing domain invariant:

```
Do not work around the invariant in code.
```

Instead:

1. identify the conflicting invariant or state rule;
2. determine whether the Task actually requires a domain-model change;
3. surface the design conflict;
4. update `DOMAIN_MODEL.md` only if the domain decision itself is intentionally changed;
5. then implement the new semantics consistently.

Implementation difficulty alone is not a valid reason to weaken a domain invariant.

## 8. Coding Standards

### 8.1 General

Prefer:

```
clarity
explicit behavior
small units
existing project conventions
domain terminology
testability
```

over:

```
clever abstractions
framework tricks
premature generalization
hidden side effects
```

Code should make important domain decisions visible.

### 8.2 Prefer Existing Patterns

Before introducing a new abstraction:

1. Search for an existing equivalent capability.
2. Determine whether the current project already has an established pattern.
3. Extend the existing pattern when it remains appropriate.
4. Introduce a new abstraction only when it represents a genuinely different responsibility.

Do not create duplicate concepts such as:

```
AiClient
LlmClient
ModelClient
ChatProvider
```

if they all express the same existing AI Gateway responsibility.

### 8.3 Avoid Premature Abstraction

Do not introduce:

```
Plugin systems
Generic workflow engines
Custom event buses
Generic repository frameworks
Generic command frameworks
Dynamic module registries
Complex strategy hierarchies
```

unless the current requirements demonstrate the need.

The architecture should remain evolvable without implementing hypothetical future complexity.

### 8.4 Naming

Use names from the project's Ubiquitous Language whenever a domain concept exists.

Prefer:

```
EvolutionPlan
RepositoryProfile
WorkingCopy
VerificationResult
```

over invented synonyms.

For Application operations, prefer names that express the use case.

For Infrastructure types, make technical implementation explicit where useful.

Examples:

```
DeepSeekAiGatewayAdapter
SQLiteUserProfileRepository
GitWorkspaceAdapter
```

rather than leaking those names into Domain concepts.

### 8.5 Avoid Generic Utility Dumping Grounds

Do not introduce broad packages such as:

```
utils
common
helpers
misc
base
```

for unrelated behavior.

Place behavior near the concept that owns it.

A true technical utility with no domain ownership should still have a narrow and explicit responsibility.

### 8.6 State Transitions Must Be Explicit

When domain states change, prefer explicit domain operations over arbitrary field mutation.

Avoid logic equivalent to:

```
step.setStatus(SUCCEEDED);
```

from arbitrary Application or Infrastructure code when reaching that state requires domain validation.

The code should make illegal state transitions difficult to express.

### 8.7 Error Handling

Do not silently ignore failures.

Avoid:

```
catch (Exception e) {
}
```

or equivalent broad exception swallowing.

Errors should preserve enough context to determine:

```
what operation failed
which domain object was involved
what external capability failed
whether retry / rollback / recovery is safe
```

Technical exceptions should be translated at appropriate boundaries rather than leaked indiscriminately across the system.

### 8.8 Logging

Logs should support diagnosis without becoming the source of domain truth.

Log meaningful context such as:

```
operation
relevant entity ID
adapter / external capability
result
failure reason
```

Do not log secrets.

Never log:

```
API keys
access tokens
credentials
sensitive provider configuration
```

Avoid dumping complete source files, prompts, user data, or model responses into logs unless explicitly necessary and appropriately controlled.

### 8.9 Configuration

Environment-dependent values must be configurable.

Do not hard-code:

```
API keys
LLM endpoints
model IDs that are intended to be configurable
filesystem workspace roots
environment-specific URLs
database locations
```

into business logic.

## 9. Dependency Policy

Adding a dependency is a design decision, not a convenience action.

Before adding a new dependency, determine:

```
Dependency:
What library is being introduced?

Purpose:
What exact capability does the Task require?

Why Existing Dependencies Are Insufficient:
Why can the requirement not reasonably be implemented using the current stack?

Alternatives Considered:
What existing or standard capabilities were considered?

Maintenance Risk:
What additional compatibility, security, licensing, or upgrade burden does this dependency introduce?
```

### 9.1 Do Not Add Dependencies Speculatively

Do not introduce a dependency because:

```
it may be useful later
it reduces a few lines of code
it is currently popular
the Agent prefers it
```

### 9.2 Do Not Upgrade Unrelated Dependencies

Dependency upgrades are out of scope unless:

- the current Task explicitly requires them;
- the existing version blocks the implementation;
- a relevant security or compatibility issue must be fixed.

If an upgrade is required, limit it to the smallest justified scope and verify compatibility.

### 9.3 Respect Existing Technology Decisions

Do not replace established MVP choices such as:

```
Java 21
Maven + Maven Wrapper
Spring Boot
SQLite
MyBatis-Plus
Flyway
Vue 3
TypeScript
Vite
```

without an explicit architecture decision.

## 10. Testing Rules

Behavior changes require appropriate automated tests.

The type of test should match the responsibility being changed.

### 10.1 Domain Tests

Domain rules should be tested without requiring Infrastructure.

Important candidates include:

```
state transitions
Aggregate invariants
authorization boundaries
baselineRevision rules
Verification success / failure behavior
Plan completion conditions
```

Do not require Spring Boot, SQLite, Git, or external LLM access merely to test a pure Domain rule.

### 10.2 Application Tests

Application tests should verify Use Case orchestration and interaction with Ports.

Mock or fake external capabilities at the Port boundary when the purpose of the test is Application behavior.

Do not mock Domain behavior merely to avoid testing it.

### 10.3 Infrastructure Tests

Infrastructure adapters should verify integration with the actual technology where meaningful.

Examples include:

```
SQLite persistence mappings
Flyway migrations
Git operations
Working Copy creation
Filesystem boundaries
Build / Test execution
Provider response mapping
```

Do not treat a mocked Git adapter test as proof that the real Git integration works.

### 10.4 Regression Tests

When fixing a bug:

1. Reproduce the behavior where practical.
2. Add or update a test that fails for the bug.
3. Apply the fix.
4. Confirm the test passes.

### 10.5 Prohibited Test Manipulation

Do not make tests pass by:

- deleting relevant tests;
- disabling tests;
- weakening assertions without justification;
- changing expected values merely to match incorrect behavior;
- catching and ignoring exceptions;
- mocking the exact behavior that needs to be verified;
- adding unconditional success branches;
- removing failure-path coverage.

### 10.6 External AI Tests

Automated tests should not unnecessarily depend on live LLM calls.

Prefer stable test doubles for deterministic Domain and Application tests.

Live provider tests, when present, should be clearly separated from normal deterministic test execution.

## 11. Verification

Before declaring a Task complete, verify the change against the Task's actual scope.

### 11.1 Required Checks

Confirm:

- [ ] The requested behavior is actually implemented.
- [ ] Relevant Domain invariants remain valid.
- [ ] Maven Module dependency rules remain valid.
- [ ] No business code directly depends on a concrete LLM provider.
- [ ] No business code bypasses Workspace for Git / Shell / Filesystem operations.
- [ ] Repository Analysis remains read-only.
- [ ] Evolution Execution does not modify the original Software Asset.
- [ ] User confirmation boundaries remain intact.
- [ ] Relevant automated tests exist.
- [ ] Tests pass.
- [ ] Build passes.
- [ ] No unrelated files were modified.
- [ ] No temporary debug code remains.
- [ ] No unexplained dependency was added.
- [ ] No secret or credential was introduced into source control.
- [ ] Documentation is consistent with the implementation where the Task affects documented behavior.

### 11.2 Backend Verification

Use the Maven Wrapper rather than relying on a globally installed Maven version.

From the repository root, the preferred full backend verification is:

```
./mvnw verify
```

On Windows:

```
mvnw.cmd verify
```

During development, targeted module tests may be used for faster iteration.

Before completing a change that affects multiple backend modules, run the broadest practical verification required to demonstrate that the complete affected backend still builds and tests successfully.

Do not report a build as successful if only compilation of one unrelated module was performed.

### 11.3 Frontend Verification

When Frontend code changes:

- inspect `frontend/package.json`;
- use the package manager and scripts actually defined by the project;
- run the relevant test, type-check, lint, and build scripts that exist for the affected code.

Do not invent a package manager or command that the repository has not adopted.

### 11.4 Verification Must Be Real

Never claim:

```
Build passed
Tests passed
Verification succeeded
```

unless the corresponding command was actually executed successfully.

If verification cannot be performed because of an environment limitation, state exactly:

```
what was not run
why it could not be run
what remains unverified
```

## 12. Documentation & ADR Policy

Documentation is part of the implementation when behavior governed by the project documents changes.

Do not update documents mechanically after every code edit.

Update the document whose responsibility actually changed.

### 12.1 PRODUCT.md

Update when the change affects:

```
product behavior
target user
MVP scope
user-facing capability
product principles
success criteria
```

Do not modify PRODUCT.md merely because an implementation detail changed.

### 12.2 ARCHITECTURE.md

Update when the change affects:

```
Maven Module structure
module responsibility
dependency direction
major runtime flow
technology constraints
AI Gateway boundary
Workspace boundary
persistence architecture
deployment architecture
```

Small internal refactors that preserve the documented architecture do not require architecture documentation changes.

### 12.3 DOMAIN_MODEL.md

Update when the change affects:

```
Ubiquitous Language
Entity / Value Object semantics
Aggregate boundary
domain relationship
state machine
Domain Operation
Invariant
authorization rule
Working Copy safety semantics
persistence semantics
Domain Event meaning
```

Do not change implementation to contradict `DOMAIN_MODEL.md` and then leave the document unchanged.

### 12.4 ROADMAP.md

Update when:

```
Milestone status changes
Acceptance Criteria are completed or changed
Current Sprint materially changes
a planned capability is deferred or promoted
a new blocker changes development order
```

Do not mark a Milestone `DONE` until its Acceptance Criteria are actually satisfied.

### 12.5 ADR

Create or update an ADR when making an important, long-lived architecture decision whose alternatives and trade-offs should remain visible.

Typical ADR candidates include:

```
new major infrastructure technology
change to persistence strategy
change to Working Copy strategy
change to module boundaries
new cross-cutting integration approach
change to provider abstraction strategy
long-term deployment decision
```

An ADR is usually unnecessary for:

```
small class design
method naming
local refactor
ordinary bug fix
minor implementation detail
```

### 12.6 Keep Documents Consistent

When a Task legitimately changes more than one design concern, update all affected documents.

For example:

```
New domain behavior
        ↓
DOMAIN_MODEL.md

which requires a module boundary change
        ↓
ARCHITECTURE.md

and changes current milestone scope
        ↓
ROADMAP.md
```

Do not force all information into a single document simply to minimize edits.

## 13. Final Response Format

When a coding Task is completed, provide a concise implementation report.

Use the following structure.

### Changes

Describe what changed.

Include the important files or areas affected.

Do not dump every trivial file edit when a grouped explanation is clearer.

### Reason

Explain why this implementation was chosen.

Reference relevant architecture or domain constraints when they materially affected the design.

### Verification

Report exactly what was executed.

Example:

```
./mvnw verify
→ PASS

Relevant domain tests
→ PASS
```

If some verification was not run, state that explicitly.

### Risks

Describe remaining known risks relevant to the current change.

If no meaningful known risk remains:

```
No known task-specific risks.
```

Do not invent risks merely to fill the section.

### Follow-ups

List intentionally deferred work that is relevant but outside the current Task.

Do not silently expand the implementation to handle those follow-ups.

If there are none:

```
None.
```

## 14. Agent Completion Standard

A Task is not complete merely because code was generated.

A Task is complete only when the Agent can reasonably demonstrate:

```
Requested behavior implemented
        +
Architecture boundaries preserved
        +
Domain invariants preserved
        +
Relevant tests added or updated
        +
Build / tests verified
        +
No unrelated scope expansion
        +
Documentation synchronized when required
```

For DelveForge specifically, prefer:

```
Correct
Safe
Traceable
Verifiable
Incremental
```

over:

```
Clever
Highly abstract
Maximally automated
Prematurely generalized
```

The Agent should leave the repository in a state that is easier for the next developer or Agent to understand and safely continue from.
