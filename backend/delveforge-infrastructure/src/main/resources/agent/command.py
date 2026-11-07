"""配置已安装工具的环境后执行原生命令；不解释仓库的构建/验证语义。"""
import base64
import json
import os
import pathlib
import re
import shutil
import sys


def main():
    # Base64 仅作为跨 Windows/Docker argv 的无损运输；命令始终在隔离容器内解释。
    command = base64.b64decode(sys.argv[1], validate=True).decode('utf-8')
    for parent in (pathlib.Path.cwd(), *pathlib.Path.cwd().parents):
        if not parent.is_relative_to('/workspace'):
            break
        manifest = parent / 'package.json'
        lock = parent / 'pnpm-lock.yaml'
        declared = ''
        if manifest.is_file() and manifest.stat().st_size <= 262144:
            try:
                declared = str(json.loads(manifest.read_text()).get('packageManager', ''))
            except (ValueError, OSError):
                pass  # 原生命令负责报告无效 manifest；不能阻止无关的发现/修复命令。
        legacy = declared.startswith('pnpm@8.')
        if not declared and lock.is_file() and lock.stat().st_size <= 2097152:
            legacy = bool(re.search(r"(?m)^lockfileVersion:\s*['\"]?6(?:\.0)?['\"]?\s*$", lock.read_text()))
        if legacy:
            os.environ['PATH'] = '/opt/pnpm8/bin:' + os.environ['PATH']
        if declared or lock.is_file():
            break
    # 包括原生 Maven Wrapper 使用的 Maven：设置独立 HOME，绝不读取宿主 settings/凭据。
    settings = pathlib.Path.home() / '.m2/settings.xml'
    settings.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile('/opt/delveforge/offline-settings.xml', settings)
    os.execv('/bin/sh', ['sh', '-c', command])


if __name__ == '__main__':
    main()
