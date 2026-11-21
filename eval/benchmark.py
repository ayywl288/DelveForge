"""Pinned task admission and official grading. All grader inputs stay on the host."""
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import time
import uuid
from pathlib import Path

from artifacts import export, git, prepare


HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
DATA = ROOT / "target/eval-assets"
TASKS = "6e08cbcd763c869abd7aae4bcb9e1b3991180777"
GRADER = "02e7a74ffd0b707aab73d203fe87bdc7c76afc8e"
SUITE = json.loads((HERE / "suite.json").read_text())
ASSETS = ("task.yaml", "tests.json", "problem_statement.md", "gold.patch", "test.patch", "eval.sh", "Dockerfile")


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix("." + uuid.uuid4().hex + ".tmp")
    try:
        with temporary.open("x", encoding="utf-8") as stream:
            json.dump(value, stream, indent=2, ensure_ascii=True)
            stream.flush()
            os.fsync(stream.fileno())
        for delay in (0.025, 0.05, None):
            try:
                temporary.replace(path)
                break
            except PermissionError as error:
                if delay is None or getattr(error, "winerror", None) not in (5, 32, 33):
                    raise
                time.sleep(delay)
    finally:
        temporary.unlink(missing_ok=True)


def docker(*args):
    return subprocess.check_output(["docker", *map(str, args)], text=True, encoding="utf-8").strip()


def image_ref(instance):
    return "registry-1.docker.io/" + SUITE[instance]["image"]


def task(instance):
    pin = SUITE[instance]
    repo = DATA / "tasks"
    target = DATA / "official/tasks" / instance
    target.mkdir(parents=True, exist_ok=True)
    assets = {}
    for name in ASSETS:
        content = git(repo, "show", f"{TASKS}:tasks/{instance}/{name}")
        if not content.strip():
            raise ValueError("missing official asset: " + name)
        (target / name).write_bytes(content)
        assets[name] = hashlib.sha256(content).hexdigest()
    metadata = (target / "task.yaml").read_text().splitlines()
    required = ["instance_id: " + instance, "repo: " + pin["repo"], "base_commit: " + pin["base"],
                "image: " + pin["image"].split("@")[0] + ":latest", "- SWE-bench/SWE-bench_Multilingual",
                "split: test"]
    tests = read(target / "tests.json")
    if not all(v in metadata for v in required) or not tests["FAIL_TO_PASS"] or "PASS_TO_PASS" not in tests:
        raise ValueError("task metadata disagrees with suite")
    if git(repo, "ls-tree", "-r", "--name-only", TASKS, f"tasks/{instance}/test_assets"):
        raise ValueError("task requires additional binary assets")
    return {"taskRevision": TASKS, "graderRevision": GRADER, **pin, "assets": assets}


def baseline(instance):
    pin = SUITE[instance]
    path = DATA / "base" / instance
    prepare(path, "https://github.com/" + pin["repo"] + ".git", pin["base"])
    return path


def reference_roundtrip(instance, source, folder):
    # 参考补丁仅进入宿主评分夹具；工作台始终只导入 source。
    with tempfile.TemporaryDirectory(dir=folder) as tmp:
        work = Path(tmp) / "reference"
        git(source, "clone", "-q", "--no-local", "--", str(source), str(work))
        patch = (DATA / "official/tasks" / instance / "gold.patch").read_bytes()
        git(work, "apply", "--index", "--binary", "--whitespace=nowarn", "-", data=patch)
        expected = git(work, "write-tree").decode().strip()
        snapshot = export(source, SUITE[instance]["base"], work, folder / "reference.patch")
        if snapshot["tree"] != expected:
            raise ValueError("reference patch replay mismatch")
        return snapshot


def grade(instance, mode, folder, patch=None, helper=None):
    task(instance)
    folder.mkdir(parents=True, exist_ok=True)
    if patch is not None:
        shutil.copyfile(patch, folder / "model.patch")
    image = image_ref(instance)
    docker("image", "inspect", image, "--format", "{{.Id}}")
    run_id = "df-" + uuid.uuid4().hex
    helper = docker("image", "inspect", helper or "df-eval/grader:" + GRADER[:7], "--format", "{{.Id}}")
    # 客户端独占 Docker 权限。官方测试容器继承评分器的原有隔离方式。
    invocation = ["docker", "run", "--rm", "--network", "none",
                  "-v", "/var/run/docker.sock:/var/run/docker.sock",
                  "-v", f"{DATA / 'official'}:/assets:ro", "-v", f"{HERE / 'grade.py'}:/grade.py:ro",
                  "-v", f"{folder.resolve()}:/evidence", "-w", "/evidence", helper, "python", "/grade.py",
                  instance, image, mode, run_id]
    begin = time.monotonic()
    with (folder / "client.log").open("wb") as log:
        result = subprocess.run(invocation, stdout=log, stderr=subprocess.STDOUT)
    info = {"runId": run_id, "seconds": time.monotonic() - begin, "exit": result.returncode, "graderImage": helper}
    save(folder / "invocation.json", info)
    if result.returncode:
        raise RuntimeError(f"official {mode} failed; see {folder / 'client.log'}")
    report = read(folder / "official.json")[instance]
    result = grade_result(report, folder)
    log = folder / "logs/evaluation" / run_id / "delveforge" / instance / "test_output.txt"
    output = log.read_text(encoding="utf-8")
    exit_codes = re.findall(r"^>>>>> Test Exit Code: (-?\d+)\s*$", output, flags=re.MULTILINE)
    result["testExitCode"] = int(exit_codes[-1]) if exit_codes else None
    result["reportedReason"] = report.get("infra_failure_reason")
    if mode == "noop" and instance == "gin-gonic__gin-1957" and not result["hasTestStatuses"]:
        # 固定基线缺少测试所调用的两个方法，Go 在运行单个测试前就会退出。
        required = ("Applied patch context_test.go cleanly.", "c.BindHeader undefined",
                    "c.ShouldBindHeader undefined", "[build failed]", ">>>>> Test Exit Code: 1")
        if all(marker in output for marker in required):
            result["baselineCompileFailure"] = "official context_test.go requires BindHeader and ShouldBindHeader"
    return {**info, **result}


def grade_result(report, folder):
    if report.get("infra_failure") is not False or type(report.get("resolved")) is not bool:
        raise RuntimeError(f"official grader produced no valid test result; see {folder / 'official.json'}")
    statuses = report.get("tests_status", {})
    return {"resolved": report["resolved"], "hasTestStatuses": bool(statuses),
            "failedAcceptanceTests": statuses.get("FAIL_TO_PASS", {}).get("failure", [])}
