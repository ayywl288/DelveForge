# Sandbox tool protocol compatibility — 2026-10-24

## Scope and baseline

- Verified main: `437b5e6f479ca13f9009471473e2ed265227dcef`.
- Isolated branch: `codex/sandbox-protocol`; no merge or push.
- Main tracked files unchanged; existing untracked `frontend/design/` and `grilling/` untouched.
- No user SQLite/WorkingCopy mutation, application restart, paid model request or real user Step execution.
  Real Docker/Git evidence below uses synthetic fixtures, temporary test databases and owned disposable resources.

## Contract and implementation

`DockerAgentSandbox.prepare` pins the configured image to an image ID, starts the owned restricted container,
then invokes the actual `/opt/delveforge/sandbox.py` with `protocol`. This happens before source transfer,
initialize, any model request and patch operation. The handshake is internal; it is not a new model-facing tool.

Only the explicit envelope `ok=true`, `tool=protocol`, `result.protocol=delveforge-sandbox`, integer
`result.version=1` is accepted. Legacy/missing/wrong-name/non-integer/unsupported versions fail with
`SANDBOX_PROTOCOL_INCOMPATIBLE`. Malformed JSON is translated to that safe fixed compatibility error;
Docker access, timeouts and cancellation retain their existing classifications. No returned free text is echoed.
The exception provides build/configure/restart guidance; the failed Run UI displays matching static guidance
and retains the exact reason code.

v1 identifies the existing safe semantics: confirmed Git check failure is `ok=false / PATCH_CONFLICT` with
`mutationNotStarted=true` and allowlisted feedback; successful application and capture is `PATCH_APPLIED`;
uncertain application or capture I/O results terminate; post-apply policy rejection retains SOURCE_CAPTURE.
The patch implementation, source-secret/path/symlink restrictions, Candidate and Delivery rules are unchanged.
Legacy conflict results never acquire fabricated no-mutation evidence.

## Actual artifact evidence

Built from this worktree's `backend/delveforge-infrastructure/src/main/resources/agent` context:

```powershell
docker build --pull=false -t delveforge-agent:protocol-v1-20261024-jdk21 backend/delveforge-infrastructure/src/main/resources/agent
```

The build first checked that the new tag did not exist. Dockerfile already COPYs sandbox.py/proxy.py and
makes /opt/delveforge read-only; no Dockerfile or application default configuration change was needed.
Maven packaging alone does not update the Docker artifact.

New JDK 21 artifact:
`sha256:fc945b338a77431ea3519e5e990257ecfe414361432c16cc2efd902ee2965057`.

- Actual image CLI returned `delveforge-sandbox`, integer version 1.
- Python image tests compared the installed script bytes directly with build source, without fingerprints.
- Real Git successfully applied the synthetic change; conflicting context left fixture bytes unchanged.
- Explicitly injected partial-application nonzero/OSError and post-application capture I/O failure produced
  only safe PATCH_OUTCOME_UNCERTAIN responses. These are fault-injection evidence, not observed real I/O incidents.
- Injected post-application policy failure retained POLICY_REJECTED / SOURCE_CAPTURE.
- Java exercised actual adapter prepare, requiring protocol before initialize; it also checked a successful
  patch followed by unchanged bytes on a repeated/already-present change.
- The actual legacy image was rejected before initialize or patch, not upgraded into a safe conflict.
- Service regressions separately proved zero model/tool calls on preparation rejection and zero durable
  rows in agent_candidate/candidate_delivery on compatibility or uncertain patch failures.

Existing tags were re-inspected and remain unchanged:

- task1-jdk21: `sha256:ad1f86a7a24650210a58ac04ce7abc522ff8fbe6c8119c121bea3a7c03c6b196`.
- task1-jdk17: `sha256:3887ae8a8afbb02c9867b9216d499dc555f43f408193ea93788685531e3ac109`.

The legacy image has no compatible explicit protocol version; it is not assigned an invented version 0.
This task built and tested the JDK 21 artifact only. Test-owned containers and volumes were cleaned up.
The separately built new image remains available for explicit rollout.

## Commands and actual results

All commands ran from the isolated worktree. Logs remain as ignored `protocol-*.log` files there.

```powershell
.\mvnw.cmd -pl backend/delveforge-infrastructure -am test '-Dtest=DockerSandboxProtocolTest,DockerPatchPolicyTest,CodingAgentServiceTest,AgentPatchCorrectionsTest,AgentToolActivityTest' '-Dsurefire.failIfNoSpecifiedTests=false'
# Development PASS: 103 total, 101 executed, 2 image-gated skips, no failures/errors.
# The later durable zero-row assertions were covered by final full verify below.

$env:DELVEFORGE_SANDBOX_PROTOCOL_IMAGE = 'sha256:fc945b338a77431ea3519e5e990257ecfe414361432c16cc2efd902ee2965057'
$env:DELVEFORGE_SANDBOX_LEGACY_IMAGE = 'sha256:ad1f86a7a24650210a58ac04ce7abc522ff8fbe6c8119c121bea3a7c03c6b196'
.\mvnw.cmd -pl backend/delveforge-infrastructure -am test '-Dtest=DockerSandboxProtocolTest' '-Dsurefire.failIfNoSpecifiedTests=false'
# PASS: 12 tests, zero skipped/failures/errors; includes real compatible and legacy images.

python -B -m unittest discover -s backend/delveforge-infrastructure/src/test/python -v
# Before selecting an image: 22 passed, 7 explicit image-gated skips.
# With the dedicated image selected: 29 methods passed, zero skipped; includes image fault-injection subcases.

$env:DELVEFORGE_AGENT_LIVE='false'
$env:DELVEFORGE_AGENT_E2E='false'
$env:DELVEFORGE_AGENT_DOCKER='false'
$env:DELVEFORGE_AGENT_COMPATIBILITY='false'
$env:DELVEFORGE_TASK2_E2E='false'
# Keep only the two dedicated protocol-image variables above enabled.
.\mvnw.cmd verify '-Dmaven.jar.forceCreation=true'
# BUILD SUCCESS: 1564 total, 1542 executed passed, 22 existing gate skips, zero failures/errors.
# domain 203/0 skipped; application 762/0; infrastructure 396/21; app 203/1.

pnpm --config.verifyDepsBeforeRun=false --dir frontend test
# PASS: 16 files / 240 tests.
pnpm --config.verifyDepsBeforeRun=false --dir frontend typecheck
# PASS.
pnpm --config.verifyDepsBeforeRun=false --dir frontend build
# PASS.
git -c core.safecrlf=false diff --check
# PASS.
```

The first ordinary `pnpm --dir frontend test` executed all 240 tests successfully but its automatic dependency
precheck reported that a shared node_modules junction is not a real hoist directory. The final commands above
explicitly disabled that automatic install and completed without that warning; no dependency/config file changed.
Baseline source was also evaluated in memory: its protocol request was unsupported, confirming the old source
lacked this contract. This was not a real Agent run or a test of paid model behavior.

## Files and manual rollout

Changed areas: DockerAgentSandbox, sandbox.py, DockerSandboxProtocolTest, CodingAgentServiceTest,
test_sandbox_policy.py, new test_sandbox_image_protocol.py, the compatibility-only EvolutionView notice and
agent-workflow test, AUTONOMOUS_AGENT.md, ADR-0012 and this validation record. No dependencies were added.

1. Integrate/build the new Java/frontend code through an explicitly authorized local change; this task does not merge.
2. Use the verified JDK 21 image ID above in external `delveforge.agent.sandbox.images` key 21.
   Existing task1 tags/defaults remain unchanged. If selecting JDK 17, separately build and verify a new JDK 17
   image using the documented command before configuring that key.
3. Explicitly restart the application when the user chooses. Do not resume a failed Run or automatically start a Step.

Repeatable build, direct CLI probe and image regression commands are in `docs/AUTONOMOUS_AGENT.md`.

## Limits

No paid end-to-end Step or new JDK 17 artifact was verified. The handshake is a contract with a trusted,
controlled image, not attestation against a malicious image advertising a false version. Byte comparison and
runtime regressions cover the actual JDK 21 artifact built here. Existing model correction thresholds/context
compression are outside this compatibility fix. A user still must explicitly configure the new image and
restart new application code; an old configured image will now fail early as intended.
