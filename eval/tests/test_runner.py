import base64
import hashlib
import json
import os
import sys
import tempfile
import threading
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import artifacts
import benchmark
import runner
import workbench
from artifacts import export, git, prepare, verify_base


class ArtifactTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        repo = self.root / "origin"
        repo.mkdir()
        git(repo, "init", "-q", "-b", "main")
        self.identity = ["-c", "user.name=Test", "-c", "user.email=test@example.invalid"]
        for name in ("first", "removed", "renamed"):
            (repo / name).write_text(name)
        (repo / ".gitignore").write_text("*.log\n")
        git(repo, "add", ".")
        git(repo, *self.identity, "commit", "-qm", "baseline")
        self.base = git(repo, "rev-parse", "HEAD").decode().strip()
        (repo / "future").write_text("unavailable")
        git(repo, "add", ".")
        git(repo, *self.identity, "commit", "-qm", "later")
        self.future = git(repo, "rev-parse", "HEAD").strip()
        self.source = self.root / "base"
        prepare(self.source, repo.as_uri(), self.base)
        self.work = self.root / "work"
        git(self.root, "clone", "-q", "--no-local", str(self.source), str(self.work))
        self.output = self.root / "model.patch"

    def freeze(self):
        return export(self.source, self.base, self.work, self.output)

    def test_single_commit_has_no_answer_even_in_unreachable_objects(self):
        self.assertNotIn(self.future, git(self.source, "cat-file", "--batch-all-objects", "--batch-check=%(objectname)"))
        git(self.source, "hash-object", "-w", "--stdin", data=b"unreachable")
        with self.assertRaisesRegex(ValueError, "objects"):
            verify_base(self.source, self.base)

    def test_export_commits_renames_deletions_untracked_binary_and_replay(self):
        (self.work / "removed").unlink()
        (self.work / "renamed").rename(self.work / "moved")
        (self.work / "first").write_text("committed")
        git(self.work, "add", "-A")
        git(self.work, *self.identity, "commit", "-qm", "agent")
        (self.work / "first").write_text("final")
        (self.work / "new.bin").write_bytes(bytes(range(256)))
        (self.work / "build.log").write_text("ignored")
        before = (self.work / ".git/index").read_bytes()
        snapshot = self.freeze()
        self.assertEqual(before, (self.work / ".git/index").read_bytes())
        self.assertEqual(["build.log"], snapshot["ignoredUntracked"])
        self.assertIn(b"rename to moved", self.output.read_bytes())
        replay = self.root / "replay"
        git(self.root, "clone", "-q", "--no-local", str(self.source), str(replay))
        git(replay, "apply", "--index", "-", data=self.output.read_bytes())
        self.assertEqual(git(replay, "write-tree").decode().strip(), snapshot["tree"])
        self.assertEqual((replay / "new.bin").read_bytes(), bytes(range(256)))
        self.assertEqual((replay / "first").read_text(), "final")
        self.assertFalse((replay / "removed").exists())
        self.assertEqual(snapshot, self.freeze())

    def test_credentials_in_new_files_or_tracked_changes_fail_closed(self):
        (self.work / ".env").write_text("temporary fixture")
        git(self.work, "add", ".env")
        with self.assertRaisesRegex(ValueError, "sensitive"):
            self.freeze()
        (self.work / ".env").unlink()
        (self.work / "first").write_bytes(b"-----BEGIN PRIVATE KEY-----")
        with self.assertRaisesRegex(ValueError, "credential"):
            self.freeze()
        self.assertFalse(self.output.exists())

    def test_unstable_source_never_publishes(self):
        original = artifacts.files
        count = 0
        def changing(work, *args):
            nonlocal count
            count += 1
            if count == 2:
                (work / "first").write_text("changed during freeze")
            yield from original(work, *args)
        with patch.object(artifacts, "files", changing), self.assertRaisesRegex(ValueError, "changed"):
            self.freeze()
        self.assertFalse(self.output.exists())

    def test_patch_membership_follows_git_rules_and_index(self):
        (self.work / ".gitignore").write_text("test\nfirst\ncustom/\n*.go\n")
        (self.work / ".git/info/exclude").write_text("local-output\n")
        for name in ("test", "local-output", "first", "new.go", "selected.go"):
            (self.work / name).write_text(name + "-changed")
        for name in ("custom/hidden.ts", "target/normal.java", "node_modules/local/source.js"):
            path = self.work / name
            path.parent.mkdir(parents=True)
            path.write_text("source")
        git(self.work, "add", "-f", "selected.go")
        snapshot = self.freeze()
        self.assertEqual(set(snapshot["excludedPaths"]), {"test", "local-output", "new.go", "custom/"})
        replay = self.root / "replay"
        git(self.root, "clone", "-q", "--no-local", str(self.source), str(replay))
        git(replay, "apply", "--index", "-", data=self.output.read_bytes())
        self.assertEqual(git(replay, "write-tree").decode().strip(), snapshot["tree"])
        for name in ("selected.go", "first", "node_modules/local/source.js", "target/normal.java"):
            self.assertEqual((replay / name).read_bytes(), (self.work / name).read_bytes())
        self.assertFalse((replay / "test").exists())


class LifecycleTest(unittest.TestCase):
    def test_progress_failure_during_reads_does_not_control_run(self):
        m = dict(runId="r", conversationId="c", transitions=[], submitClock=0, lastClock=0, lastWall=0)
        states = iter(["RUNNING", "RUNNING", "COMPLETED"])
        requests = []
        with tempfile.TemporaryDirectory() as tmp, ExitStack() as stack:
            manifest = Path(tmp) / "manifest.json"
            benchmark.save(manifest, m)
            def api(route, **kwargs):
                requests.append(route)
                self.assertEqual(benchmark.read(manifest)["runId"], "r")
                return {"runs": [{"id": "r", "state": next(states), "requests": 3}]}
            def checkpoint():
                if m["outcome"] == "RUNNING":
                    raise PermissionError("manifest replacement denied")
                benchmark.save(manifest, m)
            stack.enter_context(patch.object(workbench, "api", api))
            stack.enter_context(patch.object(workbench.time, "sleep"))
            stack.enter_context(patch.object(workbench.time, "monotonic", side_effect=[1, 15, 25]))
            stack.enter_context(patch.object(workbench.time, "time", side_effect=[1, 15, 25]))
            feed = workbench.observe(m, checkpoint)
            self.assertIn("replacement denied", benchmark.read(manifest)["progressError"])
        self.assertEqual(requests, ["/conversations/c"] * 3)
        self.assertEqual(feed["runs"][0]["requests"], 3)
        self.assertEqual(m["outcome"], "COMPLETED")
        self.assertNotIn("timingUncertain", m)

    def test_persistent_progress_failure_cannot_prevent_stop(self):
        for accepted in (True, False):
            with self.subTest(accepted=accepted), tempfile.TemporaryDirectory() as tmp, ExitStack() as stack:
                m = dict(runId="r", conversationId="c", transitions=[], startClock=0,
                         submitClock=0, lastClock=0, lastWall=0)
                actions = []
                states = iter(["RUNNING", "RUNNING", "STOPPED" if accepted else "RUNNING"])
                def api(route, body=None, **kwargs):
                    if body is None:
                        return {"runs": [{"id": "r", "state": next(states), "requests": 2}]}
                    actions.append(route)
                    if not accepted:
                        raise TimeoutError("stop reply lost")
                def locked():
                    actions.append("checkpoint")
                    raise PermissionError("held file")
                stack.enter_context(patch.object(workbench, "api", api))
                stack.enter_context(patch.object(workbench.time, "sleep"))
                stack.enter_context(patch.object(workbench.time, "monotonic", side_effect=[1, 2700, 2701]))
                stack.enter_context(patch.object(workbench.time, "time", side_effect=[1, 2700, 2701]))
                if accepted:
                    feed = workbench.observe(m, locked)
                    self.assertEqual(feed["runs"][0]["state"], "STOPPED")
                else:
                    with self.assertRaisesRegex(RuntimeError, "stop reply lost"):
                        workbench.observe(m, locked)
                    self.assertIn("stop reply lost", m["stopError"])
                self.assertEqual(actions[:3], ["checkpoint", "/runs/r/stop", "checkpoint"])
                self.assertEqual(m["outcome"], "TIMEOUT")
                self.assertNotIn("graded", m)

    def test_reboot_with_inconsistent_clocks_stops_existing_run(self):
        m = dict(runId="r", projectId="p", trialId="t", conversationId="c", transitions=[], submitClock=8000,
                 startClock=8000, lastClock=8010, submitWall=100, lastWall=110,
                 config={"choice": workbench.CHOICE, "modelInfo": {}, "requestLimit": 96})
        def feed(state):
            return {"runs": [{"id": "r", "state": state, "requests": 2, "allowance": 96,
                              "started_at": "1970-01-01T00:01:40Z"}], "model": workbench.CHOICE, "modelInfo": {}}
        replies = [[{"id": "c", "title": "t", "project_id": "p"}], feed("RUNNING"), None, feed("STOPPED")]
        with tempfile.TemporaryDirectory() as tmp, ExitStack() as stack:
            server = stack.enter_context(patch.object(workbench, "api", side_effect=replies))
            stack.enter_context(patch.object(workbench.time, "sleep"))
            stack.enter_context(patch.object(workbench.time, "monotonic", side_effect=[3, 4]))
            stack.enter_context(patch.object(workbench.time, "time", side_effect=[150, 151]))
            workbench.observe(m, lambda: None, recovering=True)
        self.assertEqual(server.call_args_list[2].args, ("/runs/r/stop", {}))
        self.assertTrue(m["timingUncertain"])
        self.assertEqual(m["outcome"], "TIMING_UNCERTAIN")
        self.assertEqual(m["startClock"], 8000)

    def test_critical_pre_submit_write_failure_prevents_dispatch(self):
        i = next(iter(runner.SUITE))
        model = {"model": {"requestedModel": workbench.CHOICE["model"], "thinking": "high",
                           "adapter": "OpenAI", "endpoint": "https://api.openai.com"}}
        with tempfile.TemporaryDirectory() as tmp, ExitStack() as stack:
            root = Path(tmp)
            path = root / "trials" / i / "manifest.json"
            prompt = root / "official/tasks" / i / "problem_statement.md"
            prompt.parent.mkdir(parents=True)
            prompt.write_text("original")
            benchmark.save(root / "checks" / i / "admission.json", {
                "admitted": True, "task": {"base": "base"}, "noop": {"graderImage": "g"}, "oracle": {"graderImage": "g"}})
            def api(route, body=None):
                if route == "/settings":
                    return {"maxConcurrentRuns": 1}
                if route == "/models":
                    return {"models": [{"provider": "openai", "id": workbench.CHOICE["model"], "verifiedEfforts": ["high"]}]}
                if route == "/conversations/c":
                    return {"model": workbench.CHOICE, "modelInfo": model, "runs": [], "messages": []}
                if route not in ("/conversations/c/model", "/projects/p/environment"):
                    self.fail("unexpected dispatch: " + route)
            def project(m, *args):
                m["projectId"] = "p"
                return root
            def locked(dest, value):
                if dest == path and value.get("submitClock") is not None:
                    raise PermissionError("critical identity locked")
                benchmark.save(dest, value)
            for name, obj in [("DATA", root), ("SERVICE", root), ("api", api), ("project", project), ("save", locked)]:
                stack.enter_context(patch.object(runner, name, obj))
            for name, value in [("identity", "c"), ("task", {"base": "base"}), ("baseline", root), ("verify_base", None),
                                ("docker", "g"), ("check_policy", {}), ("freeze_config", {})]:
                stack.enter_context(patch.object(runner, name, return_value=value))
            identities = []
            for attempt in range(2):
                with self.assertRaisesRegex(PermissionError, "critical identity locked"):
                    runner.execute(i)
                durable = benchmark.read(path)
                identities.append((durable["trialId"], durable["requestId"]))
                self.assertNotIn("submitClock", durable)
                self.assertNotIn("runId", durable)
            self.assertEqual(identities[0], identities[1])

    @unittest.skipUnless(os.name == "nt", "requires Windows file sharing")
    def test_windows_reader_denying_delete_preserves_atomic_checkpoint(self):
        import ctypes
        from ctypes import wintypes
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        create = kernel.CreateFileW
        create.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, wintypes.LPVOID,
                           wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
        create.restype = wintypes.HANDLE
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "manifest.json"
            clock = workbench.time.monotonic()
            m = dict(runId="original", conversationId="c", transitions=[], submitClock=clock,
                     lastClock=clock, lastWall=workbench.time.time(), seq=0)
            benchmark.save(path, m)
            ready, release = threading.Event(), threading.Event()
            observed = []
            def reader():
                # GENERIC_READ，允许读写共享但不允许 FILE_SHARE_DELETE。
                handle = create(str(path), 0x80000000, 3, None, 3, 0x80, None)
                if handle == ctypes.c_void_p(-1).value:
                    return
                try:
                    ready.set()
                    while not release.wait(0.005):
                        observed.append(benchmark.read(path)["seq"])
                finally:
                    kernel.CloseHandle(handle)
            thread = threading.Thread(target=reader)
            thread.start()
            try:
                self.assertTrue(ready.wait(2))
                with self.assertRaises(PermissionError) as failed:
                    benchmark.save(path, {"seq": 1})
                self.assertIn(failed.exception.winerror, (5, 32, 33))
                print(f"Windows held reader: WinError {failed.exception.winerror}", flush=True)
                self.assertEqual(benchmark.read(path), m)
                routes = []
                def api(route, **kwargs):
                    routes.append(route)
                    if len(routes) == 2:
                        release.set()
                        thread.join(2)
                    state = "RUNNING" if len(routes) < 3 else "COMPLETED"
                    return {"runs": [{"id": "original", "state": state, "requests": 3}]}
                with patch.object(workbench, "api", api):
                    workbench.observe(m, lambda: benchmark.save(path, m))
                self.assertEqual(routes, ["/conversations/c"] * 3)
                durable = benchmark.read(path)
                self.assertEqual((durable["runId"], durable["outcome"]), ("original", "COMPLETED"))
                self.assertIn("WinError", durable["progressError"])
            finally:
                release.set()
                thread.join(2)
            self.assertFalse(thread.is_alive())
            self.assertEqual(set(observed), {0})
            benchmark.save(path, {"seq": 2})
            self.assertEqual(benchmark.read(path), {"seq": 2})
            self.assertEqual(list(path.parent.glob("*.tmp")), [])

    def test_receipt_accounting_is_exclusive_and_retains_uncertainty(self):
        receipts = [{"exit": 0, "outputGap": True}, {"exit": 7}, {"exit": 0, "timedOut": True},
                    {"exit": None, "timedOut": True, "stopRequested": True}, {"notStarted": True},
                    {"exit": None, "outputGap": True, "capturedBytes": 3}, {}]
        messages = [{"role": "assistant", "run_id": "r", "calls": json.dumps([{}] * 8)}]
        import uuid
        for receipt in receipts:
            receipt["invocation"] = str(uuid.uuid4())
            messages.append({"role": "tool", "run_id": "r", "calls": "[]", "text": json.dumps(receipt)})
        messages.append(messages[-1])
        manifest = {"runId": "r", "conversationId": "c"}
        page = {"chunks": [], "next": 0, "bytes": 0, "gap": False}
        with tempfile.TemporaryDirectory() as tmp, patch.object(workbench, "api", return_value=page):
            workbench.trace(Path(tmp), manifest, {"messages": messages})
            rows = benchmark.read(Path(tmp) / "trace.json")
            self.assertEqual([json.loads(row["text"]) for row in rows[1:]], receipts + receipts[-1:])
        self.assertEqual(manifest["toolOutcomes"], dict(SUCCESS=1, NONZERO_EXIT=1, TIMEOUT=2, NOT_STARTED=1, UNKNOWN=2))
        self.assertEqual((manifest["toolCalls"], manifest["toolExecuted"], manifest["toolFailures"]), (8, 5, 3))
        self.assertEqual((manifest["toolOutputGaps"], manifest["toolStopRequests"]), (2, 1))

    def test_deadline_stop_attempted_even_when_observation_times_out(self):
        m = dict(runId="r", conversationId="c", transitions=[], submitClock=0, startClock=0, lastClock=2600, lastWall=2600)
        stopped = {"runs": [{"id": "r", "state": "STOPPED", "requests": 1}]}
        with (tempfile.TemporaryDirectory() as tmp,
              patch.object(workbench.time, "monotonic", return_value=2781),
              patch.object(workbench.time, "time", return_value=2781),
              patch.object(workbench, "api", side_effect=[TimeoutError(), None, stopped]) as server):
            self.assertEqual(workbench.observe(m, lambda: None), stopped)
        self.assertEqual(server.call_args_list[1].args, ("/runs/r/stop", {}))
        self.assertEqual(m["outcome"], "TIMEOUT")

    def test_rejected_observation_requests_stop_and_preserves_unknown_status(self):
        m = dict(runId="r", conversationId="c", startClock=1, lastClock=2, lastWall=2)
        err = workbench.HTTPError("http://localhost", 404, "Not Found", {}, None)
        with (tempfile.TemporaryDirectory() as tmp,
              patch.object(workbench.time, "monotonic", return_value=3),
              patch.object(workbench, "api", side_effect=[err, None]) as server,
              self.assertRaisesRegex(RuntimeError, "404")):
            workbench.observe(m, lambda: None)
        self.assertEqual(server.call_args_list[-1].args, ("/runs/r/stop", {}))
        self.assertTrue(m["timingUncertain"])
        self.assertEqual(m["outcome"], "TIMING_UNCERTAIN")

    def test_metadata_and_grader_failure_are_checked_before_admission(self):
        i = next(iter(benchmark.SUITE))
        meta = f"instance_id: {i}\nrepo: wrong\n".encode()
        def asset(repo, command, pointer):
            if pointer.endswith("task.yaml"):
                return meta
            if pointer.endswith("tests.json"):
                return b'{"FAIL_TO_PASS":["case"],"PASS_TO_PASS":[]}'
            return b"fixture"
        with tempfile.TemporaryDirectory() as tmp, patch.object(benchmark, "DATA", Path(tmp)), \
                patch.object(benchmark, "git", asset), self.assertRaisesRegex(ValueError, "metadata"):
            benchmark.task(i)
        for report in ({"resolved": False}, {"infra_failure": True, "resolved": False, "tests_status": {}}):
            with self.assertRaisesRegex(RuntimeError, "no valid test"):
                benchmark.grade_result(report, Path("."))

    def test_interrupted_submission_reuses_original_request_and_freezes_once(self):
        from contextlib import ExitStack
        i = next(iter(benchmark.SUITE))
        pid = "70a01a65-d3e3-4c5e-8a5c-9efb29be894c"
        cid = "e33cfedc-7ea7-41a0-a06f-363529293804"
        rid = "7a686246-b690-4ffb-ac89-27955b708513"
        token = "stable-request"
        with tempfile.TemporaryDirectory() as tmp, ExitStack() as stack:
            root = Path(tmp)
            folder = root / "trials" / i
            prompt = root / "official/tasks" / i / "problem_statement.md"
            prompt.parent.mkdir(parents=True)
            prompt.write_text("Original issue.")
            benchmark.save(root / "checks" / i / "admission.json", {"admitted": True, "task": {"base": "trusted"}})
            config = {"choice": workbench.CHOICE, "requestLimit": 96, "modelInfo": {"identity": "pinned"}}
            benchmark.save(root / "suite-config.json", config)
            benchmark.save(folder / "manifest.json", dict(instanceId=i, trialId="trial", phase="EXECUTE", submitClock=0,
                submitWall=0, lastWall=0, lastClock=0, transitions=[], config=config,
                submittedTextSha256=hashlib.sha256(b"Original issue.").hexdigest(),
                requestId=token, projectId=pid, conversationId=cid, environment={"containerId": "container"},
                task={"base": "trusted"}, graderImage="pinned-image"))
            for key, value in [("DATA", root), ("SERVICE", root / "service")]:
                stack.enter_context(patch.object(runner, key, value))
            for key, result in [("task", {"base": "trusted"}), ("baseline", root / "source"),
                                ("trace", None), ("settle", None), ("verify_stopped", None),
                                ("freeze_config", {}), ("grade", {"resolved": True})]:
                stack.enter_context(patch.object(runner, key, return_value=result))
            def freeze(src, base, work, path):
                path.write_bytes(b"")
                return {"sha256": runner.hashlib.sha256(b"").hexdigest()}
            frozen = stack.enter_context(patch.object(runner, "export", side_effect=freeze))
            def feed(state):
                run = {"id": rid, "state": state, "requests": 3, "allowance": 96,
                       "started_at": "1970-01-01T00:00:00Z", "ended_at": "1970-01-01T00:00:51Z"}
                return {"runs": [run], "messages": [{"role": "user", "run_id": rid, "text": "Original issue."}],
                        "model": workbench.CHOICE, "modelInfo": config["modelInfo"]}
            replies = [[{"id": cid, "title": "trial", "project_id": pid}], feed("RUNNING"), feed("COMPLETED")]
            stack.enter_context(patch.object(workbench, "DATA", root))
            server = stack.enter_context(patch.object(workbench, "api", side_effect=replies))
            submit = stack.enter_context(patch.object(runner, "api", side_effect=AssertionError("unexpected submission")))
            stack.enter_context(patch.object(workbench.time, "monotonic", side_effect=[50, 51]))
            stack.enter_context(patch.object(workbench.time, "time", side_effect=[50, 51]))
            stack.enter_context(patch.object(workbench.time, "sleep"))
            runner.execute(i)
            runner.execute(i)
            submit.assert_not_called()
            self.assertEqual([call.args for call in server.call_args_list],
                             [(f"/projects/{pid}/conversations",), (f"/conversations/{cid}",), (f"/conversations/{cid}",)])
            frozen.assert_called_once()
            final = benchmark.read(folder / "manifest.json")
            self.assertEqual((final["runId"], final["requestId"], final["agentSeconds"]), (rid, token, 51))
            self.assertEqual(final["outcome"], "COMPLETED")

    def test_resume_checks_trial_configuration_even_if_suite_receipt_is_missing(self):
        m = {"modelInfo": {"identity": "original"}, "graderImage": "official", "environment": {"images": {}}}
        with tempfile.TemporaryDirectory() as tmp, patch.object(runner, "revision", return_value={"commit": "fixture"}):
            config = Path(tmp) / "suite.json"
            m["config"] = runner.freeze_config(m, config)
            config.unlink()
            m["graderImage"] = "changed"
            with self.assertRaisesRegex(RuntimeError, "configuration changed"):
                runner.freeze_config(m, config)

    def test_terminal_recovery_uses_server_end_even_after_deadline(self):
        config = {"choice": workbench.CHOICE, "requestLimit": 96, "modelInfo": {"identity": "pinned"}}
        m = dict(trialId="t", runId="r", projectId="p", conversationId="c", transitions=[],
                 submitClock=0, submitWall=0, lastClock=0, lastWall=0, config=config)
        run = {"id": "r", "state": "COMPLETED", "requests": 3, "allowance": 96,
               "started_at": "1970-01-01T00:00:01Z", "ended_at": "1970-01-01T00:00:51Z"}
        feed = {"model": workbench.CHOICE, "modelInfo": config["modelInfo"], "runs": [run]}
        owners = [{"title": "t", "project_id": "p", "id": "c"}]
        with (patch.object(workbench.time, "monotonic", return_value=6000),
              patch.object(workbench.time, "time", return_value=6000),
              patch.object(workbench, "api", side_effect=[owners, feed]) as server):
            workbench.observe(m, lambda: None, recovering=True)
        self.assertEqual([c.args for c in server.call_args_list], [("/projects/p/conversations",), ("/conversations/c",)])
        self.assertEqual((m["outcome"], m["agentSeconds"]), ("COMPLETED", 50))

    def test_missing_or_foreign_run_cannot_be_recovered_or_resubmitted(self):
        m = dict(trialId="t", runId="original", projectId="p", conversationId="c", lastClock=0)
        owners = [{"title": "t", "project_id": "p", "id": "c"}]
        for runs in ([], [{"id": "foreign"}]):
            with patch.object(workbench, "api", side_effect=[owners, {"runs": runs}]) as server:
                with self.assertRaisesRegex(ValueError, "ambiguous"):
                    workbench.observe(m, lambda: None, recovering=True)
            self.assertTrue(m["timingUncertain"])
            self.assertEqual([c.args for c in server.call_args_list],
                             [("/projects/p/conversations",), ("/conversations/c",)])

    def test_unavailable_ownership_on_recovery_requires_intervention(self):
        m = dict(trialId="t", runId="r", projectId="p", conversationId="c",
                 lastClock=0, lastWall=0, startClock=0)
        with (patch.object(workbench.time, "monotonic", return_value=3000),
              patch.object(workbench.time, "time", return_value=3000),
              patch.object(workbench, "api", side_effect=OSError("offline")) as server):
            with self.assertRaisesRegex(RuntimeError, "cannot verify original Run"):
                workbench.observe(m, lambda: None, recovering=True)
        self.assertTrue(m["timingUncertain"])
        server.assert_called_once_with("/projects/p/conversations", timeout=5)

    def test_missing_submission_clock_never_reenters_preparation(self):
        i = next(iter(benchmark.SUITE))
        with tempfile.TemporaryDirectory() as tmp, patch.object(runner, "DATA", Path(tmp)):
            folder = Path(tmp) / "trials" / i
            benchmark.save(folder / "manifest.json", dict(instanceId=i, projectId="p", conversationId="c",
                requestId="original", trialId="t", phase="EXECUTE", transitions=[], config={}))
            with (patch.object(runner, "observe", side_effect=RuntimeError("original Run missing")) as observer,
                  patch.object(runner, "api") as dispatch):
                with self.assertRaisesRegex(RuntimeError, "original Run missing"):
                    runner.execute(i)
            self.assertTrue(observer.call_args.kwargs["recovering"])
            dispatch.assert_not_called()
            self.assertEqual(benchmark.read(folder / "manifest.json")["requestId"], "original")

    def test_unknown_or_foreground_states_never_freeze(self):
        for state in workbench.FOREGROUND | {"QUEUED"}:
            feed = {"runs": [{"id": "r", "state": state}], "activeTools": [], "foregrounds": []}
            with self.assertRaisesRegex(RuntimeError, "unsettled"):
                workbench.settle("p", "r", feed, "generation")
        with self.assertRaises(ValueError):
            workbench.decision({"state": "UNEXPECTED"}, 0, False)

    def test_clock_starts_after_queue_and_timeout_stops_once(self):
        m = dict(runId="r", conversationId="c", transitions=[], submitClock=0, lastClock=0, lastWall=0)
        states = iter(["QUEUED", "QUEUED", "RUNNING", "RUNNING", "STOPPING", "STOPPED"])
        commands = []
        def api(route, body=None, **kwargs):
            if body is not None:
                commands.append(route)
                return
            return {"runs": [{"id": "r", "state": next(states), "requests": 2}], "messages": []}
        times = [0, 2, 3, 6, 7, 8]
        with tempfile.TemporaryDirectory() as tmp, patch.object(workbench, "api", api), \
                patch.object(workbench, "SECONDS", 4), patch.object(workbench.time, "sleep"), \
                patch.object(workbench.time, "monotonic", side_effect=times), patch.object(workbench.time, "time", side_effect=times):
            workbench.observe(m, lambda: None)
        self.assertEqual(commands, ["/runs/r/stop"])
        self.assertEqual(m["outcome"], "TIMEOUT")
        self.assertEqual(m["queueSeconds"], 2)
        self.assertEqual([s["state"] for s in m["transitions"]], ["QUEUED", "RUNNING", "STOPPING", "STOPPED"])

    def test_request_exhaustion_and_other_pause(self):
        row = {"state": "PAUSED", "requests": 96, "detail": "已达到本任务的模型请求总上限"}
        self.assertEqual(workbench.decision(row, 4, False), "REQUEST_LIMIT")
        row["detail"] = "context"
        self.assertEqual(workbench.decision(row, 4, False), "PAUSED")

    def test_interrupted_observation_is_distinct_from_budget_exhaustion(self):
        m = dict(runId="r", conversationId="c", transitions=[], submitClock=0, startClock=0, lastClock=0, lastWall=0)
        def feed(state):
            return {"runs": [{"id": "r", "state": state, "requests": 1}], "messages": []}
        with tempfile.TemporaryDirectory() as tmp, patch.object(workbench.time, "sleep"), \
                patch.object(workbench, "api", side_effect=[feed("RUNNING"), feed("COMPLETED")]) as server, \
                patch.object(workbench.time, "monotonic", side_effect=[15, 16]), \
                patch.object(workbench.time, "time", side_effect=[15, 16]):
            workbench.observe(m, lambda: None)
        self.assertEqual([call.args for call in server.call_args_list], [("/conversations/c",)] * 2)
        self.assertEqual(m["outcome"], "COMPLETED")

    def test_blocked_run_is_reconciled_once_and_never_snapshotted(self):
        m = dict(runId="r", conversationId="c", transitions=[], submitClock=0, lastClock=0, lastWall=0)
        commands = []
        def api(route, body=None, **kwargs):
            if body is not None:
                commands.append(route)
                return
            return {"runs": [{"id": "r", "state": "BLOCKED", "requests": 2}], "messages": []}
        with tempfile.TemporaryDirectory() as tmp, patch.object(workbench, "api", api), \
                patch.object(workbench.time, "sleep"), patch.object(workbench.time, "monotonic", side_effect=[1, 182]), \
                patch.object(workbench.time, "time", side_effect=[1, 182]), self.assertRaisesRegex(RuntimeError, "unresolved"):
            workbench.observe(m, lambda: None)
        self.assertEqual(commands, ["/runs/r/reconcile"])
        self.assertNotIn("snapshot", m)

    def test_ambiguous_creation_is_recovered_or_left_for_inspection(self):
        key = "aceda887-e4f7-4328-a36d-7205cff3ec8c"
        m = {"pending": "projectId"}
        with patch.object(workbench, "api", return_value=[]) as api, self.assertRaises(RuntimeError):
            workbench.identity(m, "projectId", "/projects", "name", "trial", {}, lambda: None)
        api.assert_called_once_with("/projects")
        with patch.object(workbench, "api", return_value=[{"id": key, "name": "trial"}]):
            self.assertEqual(key, workbench.identity(m, "projectId", "/projects", "name", "trial", {}, lambda: None))

    def test_paginated_trace_preserves_gap_and_redacts_across_chunks(self):
        self.assertNotIn("test-token", workbench.redact('{"Authorization":"Bearer test-token"}'))
        invocation = "a780572d-b7ed-4def-ae3c-650e58d0b32d"
        m = {"runId": "r", "conversationId": "c"}
        feed = {"messages": [{"role": "tool", "run_id": "r", "calls": "[]",
                              "text": json.dumps({"exit": 1, "invocation": invocation}), "private": "never"}]}
        chunks = [b"sk-", b"synthetic-test-token"]
        total = sum(map(len, chunks))
        pages = [{"chunks": [{"channel": "stdout", "base64": base64.b64encode(data).decode()}],
                  "bytes": total, "next": len(data) if n == 0 else total, "gap": True}
                 for n, data in enumerate(chunks)]
        with tempfile.TemporaryDirectory() as tmp, patch.object(workbench, "api", side_effect=pages):
            workbench.trace(Path(tmp), m, feed)
            output = benchmark.read(Path(tmp) / "tool-outputs" / (invocation + ".json"))
            self.assertEqual(base64.b64decode(output["base64"]["stdout"]), b"[redacted]")
            self.assertTrue(output["redacted"])
            self.assertTrue(output["gap"])
            self.assertNotIn("private", benchmark.read(Path(tmp) / "trace.json")[0])
            self.assertEqual(m["toolFailures"], 1)

    def test_report_names_graded_and_started_denominators_for_mixed_results(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(runner, "DATA", Path(tmp)):
            ids = list(runner.SUITE)
            for n, i in enumerate(ids):
                benchmark.save(Path(tmp) / "checks" / i / "admission.json", {"admitted": n < 7})
            for i, state, extra in zip(ids, ["COMPLETED", "FAILED", "STOPPED", "COMPLETED", "BLOCKED", "QUEUED"],
                    [{"graded": {"resolved": True}}, {"graded": {"resolved": False}},
                     {"graded": {"resolved": True}, "outcome": "TIMEOUT"}, {"phase": "GRADE", "error": "no grader result"},
                     {"phase": "EXECUTE", "error": "unsettled"}, {"phase": "EXECUTE"}]):
                folder = Path(tmp) / "trials" / i
                benchmark.save(folder / "manifest.json", {"trialId": i, "runId": i, "outcome": state, **extra})
                benchmark.save(folder / "run.json", {"state": state, "requests": int(state != "QUEUED")})
            runner.summarize()
            result = benchmark.read(Path(tmp) / "reports/summary.json")
            markdown = (Path(tmp) / "reports/summary.md").read_text(encoding="utf-8")
        self.assertEqual(result["officialResolved"], {"numerator": 2, "denominator": 3, "rate": 2/3})
        self.assertEqual(result["autonomousSuccess"], {"numerator": 1, "denominator": 5, "rate": 1/5})
        self.assertEqual([result[k] for k in ("candidates", "admitted", "started", "officiallyGraded",
                         "infrastructureInvalid", "incomplete", "unreconciled", "unattempted")], [8, 7, 5, 3, 1, 2, 1, 1])
        self.assertIn("1 / 5", markdown)
        self.assertIn("2 / 3", markdown)

    def test_provider_billing_failure_keeps_started_and_official_patch_result(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(runner, "DATA", Path(tmp)):
            i = next(iter(runner.SUITE))
            folder = Path(tmp) / "trials" / i
            benchmark.save(folder / "manifest.json", {"trialId": i, "outcome": "PAUSED", "graded": {"resolved": True}})
            benchmark.save(folder / "run.json", {"state": "PAUSED", "requests": 1, "detail": "提供方账户余额或付款权限不足。"})
            runner.summarize()
            result = benchmark.read(Path(tmp) / "reports/summary.json")
        self.assertEqual(result["rows"][0]["cause"], "PROVIDER_FAILURE")
        self.assertEqual(result["infrastructureInvalid"], 1)
        self.assertEqual(result["officialResolved"]["rate"], 1)
        self.assertEqual(result["autonomousSuccess"], {"numerator": 0, "denominator": 1, "rate": 0})
