"""Prepare a single-commit import and export the stopped project's actual files."""
import hashlib
import os
import re
import shutil
import stat
import subprocess
import tempfile
from pathlib import Path


def git(repo, *args, data=None, env=None):
    settings = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
    settings.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull, GIT_TERMINAL_PROMPT="0")
    settings.update(env or {})
    return subprocess.run(["git", "-C", str(repo), "-c", "core.autocrlf=false", "-c", "core.longpaths=true",
                           "-c", "core.hooksPath=" + os.devnull, *args], input=data, stdout=subprocess.PIPE,
                          check=True, env=settings).stdout


def names(raw):
    return [item.decode("utf-8") for item in raw.split(b"\0") if item]


def index(repo, alternate=None):
    entries = {}
    for line in names(git(repo, "ls-files", "--stage", "-z", env=alternate)):
        meta, name = line.split("\t", 1)
        mode, oid, stage = meta.split()
        if mode not in ("100644", "100755") or stage != "0":
            raise ValueError("unsupported link, submodule or unmerged path: " + name)
        entries[name] = (mode, oid)
    return entries


def verify_base(repo, base):
    if git(repo, "rev-parse", "HEAD").decode().strip() != base:
        raise ValueError("baseline HEAD mismatch")
    reachable = {line.split()[0] for line in git(repo, "rev-list", "--objects", base).splitlines()}
    objects = set(git(repo, "cat-file", "--batch-all-objects", "--batch-check=%(objectname)").splitlines())
    if (objects != reachable or git(repo, "rev-list", "--all").splitlines() != [base.encode()]
            or git(repo, "remote").strip() or (repo / ".git/objects/info/alternates").exists()
            or git(repo, "status", "--porcelain", "--ignored", "-z")
            or set(git(repo, "reflog", "--all", "--format=%H").splitlines()) - {base.encode()}):
        raise ValueError("baseline has unexpected objects, references or working files")
    index(repo)


def prepare(repo, url, base):
    if not repo.exists():
        repo.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=repo.parent) as tmp:
            source = Path(tmp) / "source"
            source.mkdir()
            git(source, "init", "-q", "-b", "main")
            git(source, "fetch", "-q", "--depth=1", "--no-tags", str(url), base)
            git(source, "checkout", "-q", "-B", "main", "FETCH_HEAD")
            verify_base(source, base)
            source.rename(repo)
    verify_base(repo, base)


def regular(path):
    info = path.lstat()
    if (stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 1024
            or not (stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode))):
        raise ValueError("unsupported link or special file: " + path.name)


def files(work, staging, private_index, baseline, agent, excluded):
    # 使用受信 Git 配置和原工作树里的忽略规则，索引与未跟踪文件共同确定交付范围。
    args = ("--git-dir", str(staging / ".git"), "--work-tree", str(work), "ls-files", "-z", "--others",
            "--exclude-standard")
    env = {"GIT_INDEX_FILE": str(private_index)}
    loose = names(git(work, *args, env=env))
    ignored = names(git(work, *args, "--ignored", "--directory", "--no-empty-directory", env=env))
    excluded.update(dict.fromkeys(ignored, "Git-ignored and untracked"))
    checked_dirs = {work}
    for name in sorted(set(agent).union(loose)):
        relative = Path(name)
        if name.endswith("/") or relative.is_absolute() or ".." in relative.parts or any(
                part.lower() == ".git" for part in relative.parts):
            raise ValueError("unsupported nested repository or unsafe path: " + name)
        path = work / relative
        try:
            for parent in reversed(relative.parents):
                directory = work / parent
                if directory not in checked_dirs:
                    regular(directory)
                    checked_dirs.add(directory)
            regular(path)
        except (FileNotFoundError, NotADirectoryError):
            if name not in agent:
                raise ValueError("source changed during snapshot: " + name)
            continue
        if path.is_dir():
            if (path / ".git").exists():
                raise ValueError("nested Git repository: " + name)
            continue
        leaf = path.name
        if name not in baseline and (leaf == ".env" or leaf.startswith(".env.") and leaf != ".env.example"
                                     or leaf.endswith((".pem", ".p12", ".key"))
                                     or leaf in ("id_rsa", "id_ed25519", ".netrc", ".npmrc", ".pypirc")):
            raise ValueError("review potentially sensitive new file: " + name)
        yield name, path


def blob(path):
    size = path.stat().st_size
    digest = hashlib.sha1(f"blob {size}\0".encode())
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def export(base_repo, base, work, destination):
    verify_base(base_repo, base)
    regular(work)
    work = work.resolve()
    # 只解析工作副本的索引条目。宿主 Git 不加载 Agent 修改的配置、钩子或过滤器。
    regular(work / ".git")
    regular(work / ".git/index")
    with tempfile.TemporaryDirectory(dir=destination.parent.resolve()) as tmp:
        staging = Path(tmp) / "tree"
        git(base_repo, "clone", "-q", "--no-local", "--no-checkout", "--", str(base_repo), str(staging))
        git(staging, "read-tree", base)
        trusted = index(staging)
        private_index = Path(tmp) / "agent-index"
        shutil.copyfile(work / ".git/index", private_index)
        agent = index(staging, {"GIT_INDEX_FILE": str(private_index)})
        local_exclude = work / ".git/info/exclude"
        has_local_exclude = local_exclude.exists()
        if has_local_exclude:
            regular(local_exclude.parent)
            regular(local_exclude)
            shutil.copyfile(local_exclude, staging / ".git/info/exclude")
        expected = {}
        excluded = {}
        for name, path in files(work, staging, private_index, trusted, agent, excluded):
            target = staging / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
            mode = agent.get(name, trusted.get(name, ("100644", "")))[0]
            if os.name != "nt":
                mode = "100755" if path.stat().st_mode & 0o111 else "100644"
            expected[name] = (mode, blob(target))
        # 即使 Agent 提交过修改，比较的起点仍为受信 base。保留新增且已被跟踪的忽略文件。
        git(staging, "add", "-A", "-f", "--", ".")
        staged = index(staging)
        for name, (mode, oid) in staged.items():
            wanted, content = expected[name]
            if content != oid:
                raise ValueError("Git filter or line ending changed exported bytes: " + name)
            if trusted.get(name, ("", ""))[1] != oid:
                content_bytes = (staging / name).read_bytes()
                if re.search(rb"\bsk-(?:proj-)?[A-Za-z0-9_-]{24,}|-----BEGIN [A-Z ]*PRIVATE KEY-----"
                             rb"|\b[0-9a-f]{32}\.[A-Za-z0-9_-]{12,}", content_bytes):
                    raise ValueError("potential credential in changed file: " + name)
            if wanted != mode:
                git(staging, "update-index", "--chmod=" + ("+x" if wanted == "100755" else "-x"), "--", name)
        if set(staged) != set(expected):
            raise ValueError("staged paths differ from Git-visible working files")
        checked = {}
        current = {n: blob(p) for n, p in files(work, staging, private_index, trusted, agent, checked)}
        if (blob(work / ".git/index") != blob(private_index)
                or has_local_exclude != local_exclude.exists()
                or (has_local_exclude and blob(local_exclude) != blob(staging / ".git/info/exclude"))
                or current != {n: v[1] for n, v in expected.items()}
                or checked != excluded):
            raise ValueError("source changed during snapshot")
        tree = git(staging, "write-tree").strip()
        patch = git(staging, "diff", "--cached", "--binary", "--full-index", "--no-ext-diff", "--no-textconv",
                    "--find-renames", base, "--")
        patch.decode("utf-8")  # 官方 prediction 字段要求可无损表示的文本。
        git(staging, "read-tree", base)
        if patch:
            git(staging, "apply", "--cached", "--binary", "--whitespace=nowarn", "-", data=patch)
        if git(staging, "write-tree").strip() != tree:
            raise ValueError("replayed Git tree differs from frozen files")
        # 补丁与固定基线共同形成可重建、按内容校验的快照。
        if destination.exists():
            if destination.read_bytes() != patch:
                raise ValueError("existing frozen patch differs")
        else:
            temporary = Path(tmp) / "model.patch"
            temporary.write_bytes(patch)
            temporary.rename(destination)
            destination.chmod(stat.S_IREAD)
        return {"tree": tree.decode(), "sha256": hashlib.sha256(patch).hexdigest(), "bytes": len(patch),
                "ignoredUntracked": sorted(excluded), "excludedPaths": excluded}
