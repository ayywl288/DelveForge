# 工具镜像与项目升级验收

本阶段从 `27e1581ebf2699e793347eac4b120e703a5ea54a` 开始，阶段提交可用 `git log -1 --format=%H -- docs/WORKBENCH_RUNTIME_STAGE_2.md` 定位。真实 Docker 测试使用独立数据目录、确定性模型和 Ubuntu Noble 的签名 APT 仓库。

## 复验

```powershell
$env:DF_WORKBENCH_TOOL_FIXTURE = Join-Path (Get-Location) ("target/tool-images-" + [guid]::NewGuid())
.\runtime\workbench\Initialize-Environment.ps1 -DataDirectory $env:DF_WORKBENCH_TOOL_FIXTURE
.\mvnw.cmd '-Dtest=ProjectToolImagesDockerTest,SourceFolderSelectionTest,LocalWorkbenchRequestsTest' '-Dsurefire.failIfNoSpecifiedTests=false' package
```

测试需要能通过受控出口访问 Ubuntu 镜像站的新目录。首次使用的项目均以该目录登记的基础镜像启动。

## 验收范围

- 真实 Agent 工具在 A 安装 `figlet 2.2.5-3 amd64` 并跨 Run 使用，B 在显式升级前保持原容器。推广预览确定归档 SHA-256 为 `029a54c786ebfe32fd60f00d188925cf243cf8b2647ffd597335a547faffed61`，干净测试容器实际执行了该包的命令。
- 用户确认后本机生成 v1，新项目首次启动即可使用。项目私有脚本、工作副本文件、HOME 标记和原系统层均未进入镜像。失效预览、归档损坏及不可用的升级目标分别在发布或切换前拒绝。
- B 升级后仍有原工作副本，回退恢复原容器 ID 和软件状态；A 的原服务在 B 维护期间继续可访问。
- 浏览器验收使用实际前端、固定接口响应和 Edge，检查 1280、390 像素下的原生折叠、包来源、升级确认、取消后的键盘焦点和横向溢出。真实 APT、镜像构建与回退由上述 Docker 用例验证。
- 非 APT、第三方源、找不到原版本、没有可单独验证的命令或缺少安装来源记录的包保留在私有项目。手动命令清单只覆盖相对镜像新增的 `/usr/local/bin` 项。

本机最终验收通过 4 项后端测试（1 项真实 Docker 全流程）及 408 项前端测试，跳过 0 项。类型检查及生产构建通过。本轮独立测试目录发布的镜像为 `sha256:165552d95fb59d7e7644ebed84c8b28a1c13317bb1922dab1465a6d3a4034ff4`。原始记录见 `target/project-isolation-20261115/s2-final.log` 和 `s2-browser.json`。
