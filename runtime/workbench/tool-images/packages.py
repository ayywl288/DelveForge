"""宿主发送到受控容器的只读清单、签名 APT 下载和离线验证操作。"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlsplit


def output(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.DEVNULL)


def inventory():
    manual = set(output("apt-mark", "showmanual").splitlines())
    fmt = r"${db:Status-Abbrev}\t${Package}\t${Version}\t${Architecture}\n"
    packages = []
    for line in output("dpkg-query", "-W", "-f", fmt).splitlines():
        status, name, version, arch = line.split("\t")
        if status.startswith("ii"):
            packages.append(dict(name=name, version=version, arch=arch, manual=name in manual or name + ":" + arch in manual))
    local = Path("/usr/local/bin")
    return dict(distribution=distribution(), packages=packages,
                local=sorted(p.name for p in local.iterdir() if p.is_file() and os.access(p, os.X_OK)))


def distribution():
    values = dict(line.split("=", 1) for line in Path("/etc/os-release").read_text().splitlines() if "=" in line)
    return values["ID"].strip('"') + "/" + values["VERSION_CODENAME"].strip('"')


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest() if hasattr(hashlib, "file_digest") else hashlib.sha256(stream.read()).hexdigest()


def package_name(item):
    name, version, arch = (item[k] for k in ("name", "version", "arch"))
    if not (re.fullmatch(r"[a-z0-9][a-z0-9+.-]{1,127}", name)
            and re.fullmatch(r"[0-9][a-zA-Z0-9.+:~\-]{0,159}", version)
            and re.fullmatch(r"[a-z0-9][a-z0-9-]{0,24}", arch)):
        raise ValueError("无效的包坐标")
    return name + ":" + arch + "=" + version


def apt(*args):
    cmd = ["apt-get", "-o", "Acquire::Retries=2", "-o", "Acquire::http::Timeout=25",
           "-o", "Acquire::https::Timeout=25", "-o", "APT::Get::AllowUnauthenticated=false", *args]
    subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True, timeout=210)


def download(specs):
    # 只使用干净基础镜像的签名源与归档。项目文件和项目 APT 配置均不进入构建目录。
    if distribution() != "ubuntu/noble":
        raise ValueError("该发行版尚未配置受信软件源")
    apt("-o", "APT::Update::Error-Mode=any", "update")
    names = [package_name(s) for s in specs]
    apt("-y", "--no-install-recommends", "--download-only", "install", *names)
    folder = Path("/artifacts")
    archives = []
    selected = {s["name"] for s in specs if s.get("selected")}
    for deb in sorted(Path("/var/cache/apt/archives").glob("*.deb")):
        name, version, arch = (output("dpkg-deb", "-f", str(deb), k).strip() for k in ("Package", "Version", "Architecture"))
        item = dict(name=name, version=version, arch=arch)
        qualified = package_name(item)
        sha = digest(deb)
        metadata = output("apt-cache", "show", qualified)
        if not re.search(r"^SHA256: " + sha + "$", metadata, re.M):
            raise ValueError("签名索引与归档不匹配")
        uri = output("apt-get", "--print-uris", "download", qualified).split("'")[1]
        parsed = urlsplit(uri)
        if parsed.scheme not in ("http", "https") or parsed.hostname not in ("archive.ubuntu.com", "security.ubuntu.com", "ports.ubuntu.com"):
            raise ValueError("归档来源未受信")
        item.update(sha256=sha, origin=parsed.hostname, distribution=distribution())
        shutil.copyfile(deb, folder / (sha + ".deb"))
        if name in selected:
            extract = Path("/tmp/tool-" + name)
            subprocess.run(["dpkg-deb", "-x", str(deb), str(extract)], check=True)
            subprocess.run(["dpkg-deb", "-e", str(deb), str(extract / "DEBIAN")], check=True)
            binaries = sorted(p for d in ("usr/bin", "bin", "usr/sbin", "sbin") for p in (extract / d).glob("*")
                              if p.is_file() and not p.is_symlink() and os.access(p, os.X_OK))
            if not binaries:
                raise ValueError("包内没有可验证的命令")
            preferred = [p for p in binaries if p.name == name] or [p for p in binaries if p.name.startswith(name + "-")]
            binary = (preferred or binaries)[0]
            command = "/" + str(binary.relative_to(extract))
            item.update(command=command, executableSha=digest(binary), filesDigest=digest(extract / "DEBIAN/md5sums"))
        archives.append(item)
    for frozen in specs:
        found = next((a for a in archives if a["name"] == frozen["name"] and a["arch"] == frozen["arch"]), None)
        if not found or found["version"] != frozen["version"] or frozen.get("sha256", found["sha256"]) != found["sha256"]:
            raise ValueError("指定的历史归档或依赖版本不可用")
    return archives


def inspect_installed(item, execute):
    package_name(item)
    if output("dpkg-query", "-W", "-f", "${Version}", item["name"] + ":" + item["arch"]) != item["version"]:
        return False
    manifest = output("dpkg-query", "--control-path", item["name"] + ":" + item["arch"], "md5sums").strip()
    if digest(Path(manifest)) != item["filesDigest"] or digest(Path(item["command"])) != item["executableSha"]:
        return False
    if not execute:
        # 从非受信项目只取布尔结果。拒绝使用发行版命令来源不明的本地包安装作为推广依据。
        lines = Path("/var/log/apt/history.log")
        if not lines.is_file():
            return False
        return any(re.search(r"^(Install|Upgrade): .*\b" + re.escape(item["name"]) + r":[a-z0-9-]+ \(", block, re.M)
                   and not re.search(r"^Commandline: .*\.deb(\s|$)", block, re.M) for block in lines.read_text(errors="replace").split("\n\n"))
    command = item["command"]
    probe = ["-I1"] if item["name"] == "figlet" else ["--version"]
    return subprocess.run([command, *probe], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                          stderr=subprocess.DEVNULL, timeout=8).returncode == 0


if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "inventory":
        result = inventory()
    elif mode == "download":
        result = download(json.loads(sys.argv[2]))
    else:
        result = all(inspect_installed(spec, mode == "verify") for spec in json.loads(sys.argv[2]))
    print(json.dumps(result, ensure_ascii=True))
