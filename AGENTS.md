# DelveForge contributor instructions

## Product and ownership

Read `docs/PRODUCT.md`, `docs/ARCHITECTURE.md`, `docs/DOMAIN_MODEL.md` and `docs/ROADMAP.md` before a nontrivial change. Product owns user-visible scope, Architecture owns module and capability boundaries, Domain Model owns states and invariants, and Roadmap owns current acceptance. Inspect ADRs 0028–0031 for project execution, runtime isolation, model replay and persistence decisions. Raise material design conflicts before changing their owning contracts. Document new long-lived decisions in an ADR. Distinguish observed facts, choices and inferences.

## Authorization and isolation

A user authorizes a Run by sending a request in a Project Conversation. Read the original project only to import it. Only the controlled project file and execution ports may mutate the project workcopy. A model proposes actions. DelveForge owns validation, tool admission, dispatch, receipts and recovery. Preserve project and conversation ownership, per-project exclusion, credential confinement, immutable Run configuration and historical evidence. Do not repeat a tool whose execution cannot be ruled out. Do not modify user data or unrelated local work to test a change.

## Module boundaries

Keep the Java 21 Maven modules. Domain has no dependency on Spring, persistence, providers or local operations. Application owns use cases and ports, and depends on Domain. Infrastructure implements external capabilities through these ports. App wires Application and Infrastructure and maps REST. Keep controllers thin, avoid cycles, and prefer feature-oriented packages. Frontend code calls the local Workbench API. Keep provider clients and secrets on the host.

## Implementation and verification

Identify the task, affected sources, transitions, existing tests and smallest complete change. Preserve existing behavior outside the request. Prefer explicit state transitions and domain-specific errors. Persist related state changes atomically. Logs and errors must omit keys, private provider payloads, prompts and source content. Configure machine-specific paths, models and limits externally. Justify new dependencies and keep changes within scope.

For changed behavior, reproduce the issue and add relevant regression coverage. Test pure rules independently, use application ports to isolate external dependencies, exercise real SQLite/Git and provider wire formats where applicable, and use the existing opt-in suites for container or paid services. Preserve assertions for supported behavior. Run `mvnw.cmd clean verify`, the frontend type check, tests and production build when their code changes. Report executed tests, skipped prerequisites and genuine limitations. Protect historical Flyway checksums and user data.

## Readability and language

- Use four-space indentation, no tabs, and consistent spacing around operators and after commas. Use eight-space continuation indentation, relative to the statement being wrapped.
- Java control-flow statements always use braces, including single-statement bodies.
- Separate fields, constructors, and methods with blank lines. Expand dense one-line methods and control structures; trivial accessors need no JavaDoc.
- Use explicit imports. Group project imports, third-party imports, and JDK imports in that order, with a blank line between groups; sort each group lexically and keep static imports in a separate final group.
- Aim for lines within 120 characters. Wrap signatures, argument lists, chains, and complex conditions at meaningful boundaries; do not alter literals, prompts, SQL, or commands merely to shorten a line.
- Use explicit types when they carry important Domain or Application meaning. Use `var` only when the inferred type is immediately obvious and improves readability.
- Group related fields and operations, and separate meaningful business phases with blank lines, especially in orchestration code. Use meaningful local names and intermediate results where they clarify intent; do not split cohesive methods solely to reduce their length.
- Document nontrivial class responsibilities and capability boundaries. For important methods, state relevant preconditions, outputs, side effects, and invariants; avoid documenting obvious constructors or getters.
- Explain non-obvious field semantics, especially revision trust, cumulative budgets, caches, and raw versus sanitized material. Keep comments concise and adjacent to the code they explain.
- Comments primarily explain why, invariants, or non-obvious behavior. Explanatory code comments use Chinese; identifiers and established domain terms remain English.
- Prefer a short phase comment for an important ordering or persistence constraint; remove redundant narration and tutorial-style comments.
- Keep LLM prompt natural-language instructions consistent with the project's Chinese convention; preserve schema identifiers, structured field names, and enum values.
- Apply style cleanup only to the task's affected code. Preserve execution order, contracts, and protected text, and avoid unrelated formatting or stylistic method extraction.
