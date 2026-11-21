"""Linux entry point in the trusted grader client, outside both testbed and Agent."""
import json
import sys
from pathlib import Path

import docker
from swebench.harness.run_evaluation import run_instance
from swebench.harness.utils import make_test_spec
from swebench.task.repo import load_task


def main(instance, image, mode, run_id):
    task = load_task(Path("/assets/tasks") / instance)
    spec = make_test_spec(task)
    spec.image = image
    client = docker.from_env()
    # 镜像摘要和镜像内的基线都要与固定任务一致。
    head = client.containers.run(image, ["git", "-c", "safe.directory=/testbed", "-C", "/testbed", "rev-parse", "HEAD"],
                                 remove=True, network_disabled=True).decode().strip()
    if head != task["base_commit"]:
        raise ValueError("official image baseline mismatch: " + head)
    patch = task["patch"] if mode == "oracle" else ""
    if mode == "trial":
        patch = Path("model.patch").read_bytes().decode("utf-8")
    prediction = dict(instance_id=instance, model_name_or_path="delveforge", model_patch=patch)
    result = run_instance(spec, prediction, client, run_id, timeout=1800,
                          skip_patch=not patch, task_repo="/assets")
    if not result or not result[1]:
        raise RuntimeError("official grader produced no report")
    Path("official.json").write_text(json.dumps(result[1], indent=2))


if __name__ == "__main__":
    main(*sys.argv[1:])
