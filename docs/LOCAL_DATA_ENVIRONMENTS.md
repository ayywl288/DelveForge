# 本地测试数据归档与环境切换

本说明只提供手动步骤；Pre-UI Closeout 没有执行备份、切库或清理。
旧库 `data/m4-local/delveforge.db` 保留，新的 Task G 数据库使用独立路径。
以下 PowerShell 7 示例从 `E:\develop\DelveForge` 运行；E 盘路径是可替换示例，
不要选原始 SoftwareAsset 仓库或源码目录作为 Workspace Root。

## 1. 先确认没有未决操作，再停止后端

1. 停止提交新的对话、分析、规划或 execute；核对所有标签页的等待和未决执行提示。
2. 对每个有执行请求的项目查询真实 Plan / WorkingCopy，检查 Step 与结果。
   EXECUTING / VERIFYING、current 不等于 lastVerified、结果未知都不能当作正常完成。
   **READY 也不能证明提案准备请求已经结束**。单纯没有端口流量或页面已关闭并不足够。
3. 等待服务器处理完长请求；不能确定时保留原环境定位，不用关进程、换库或删浏览器标记
   来取消任务或制造可重试状态。M5 恢复能力尚未实现。
4. 在手动启动后端的终端按 Ctrl+C，等待进程完全退出；停止其它使用同一数据库的实例、
   SQLite 编辑器及查询连接。不批量强杀 Java 进程。

只读查询示例，ID 必须来自当前项目的真实对象；不要输出完整画像或 Provider 数据：

```powershell
$apiBase = 'http://127.0.0.1:8080'
$planId = Read-Host '需要核对的真实 Plan ID'
$savedPlan = Invoke-RestMethod "$apiBase/api/evolution-plans/$planId"
$savedPlan.steps | Select-Object id, status, baselineRevision
if ($savedPlan.workingCopyId) {
    $savedCopy = Invoke-RestMethod "$apiBase/api/working-copies/$($savedPlan.workingCopyId)"
    $savedCopy | Select-Object id, location, status, sourceRevision, currentRevision, lastVerifiedRevision
}
```

退出后检查监听和进程，仅显示进程身份，不打印可能含凭据的完整 CommandLine：

```powershell
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object LocalPort -eq 8080 |
    Select-Object LocalAddress, LocalPort, OwningProcess
Get-CimInstance Win32_Process |
    Where-Object { $_.Name -match '^java(w)?\.exe$' -and $_.CommandLine -match 'delveforge' } |
    Select-Object ProcessId, Name
```

若改过端口，检查实际端口。这只是辅助检查；仍须确认没有其他数据库连接或长请求。

## 2. 使用 SQLite backup API 保存一致快照

不能只复制正在使用的 `.db` 主文件：WAL 中可能还有已提交的数据。
本机已核对 `Get-Command python` 指向 `E:\develop\python\python.exe`，其标准库 SQLite 支持
`Connection.backup`。下列代码用只读源连接生成一致快照并做 integrity_check；
Python 是此次本机维护工具，不是 DelveForge 产品运行依赖。其它主机没有该工具时先准备
可信的 SQLite backup 工具，不能把复制活动主文件当替代方案。

执行前完成第 1 节。备份含真实业务数据，应留在受控本机目录，不提交 Git、不公开上传。

```powershell
Set-Location 'E:\develop\DelveForge'
$sourceDb = (Resolve-Path -LiteralPath '.\data\m4-local\delveforge.db' -ErrorAction Stop).Path
$archiveRoot = Join-Path 'E:\df-data\archives' (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
if (Test-Path -LiteralPath $archiveRoot) { throw '归档目录已存在，请选择新位置。' }
New-Item -ItemType Directory -Path $archiveRoot -ErrorAction Stop | Out-Null
$backupDb = Join-Path $archiveRoot 'delveforge.db'
$pythonExe = (Get-Command python -ErrorAction Stop).Source

$sqliteBackup = @'
import pathlib
import sqlite3
import sys

source = pathlib.Path(sys.argv[1]).resolve(strict=True)
destination = pathlib.Path(sys.argv[2]).resolve()
if source == destination:
    raise SystemExit("Source and destination must differ")
# 独占创建目标，防止覆盖已有归档；失败时保留文件供检查，不自动删除。
with destination.open("xb"):
    pass
with sqlite3.connect(source.as_uri() + "?mode=ro", uri=True, timeout=5) as reader:
    reader.execute("PRAGMA query_only=ON")
    with sqlite3.connect(str(destination)) as writer:
        reader.backup(writer)
        result = writer.execute("PRAGMA integrity_check").fetchall()
        if result != [("ok",)]:
            raise SystemExit("Backup integrity check failed")
print("SQLite backup integrity_check=ok")
'@
& $pythonExe -c $sqliteBackup $sourceDb $backupDb
if ($LASTEXITCODE -ne 0) { throw '备份失败，请保留原库和失败文件，停止切换。' }

$archiveManifest = [ordered]@{
    sourceDatabase = $sourceDb
    backupDatabase = $backupDb
    sha256 = (Get-FileHash -LiteralPath $backupDb -Algorithm SHA256).Hash
    codeRevision = (git rev-parse HEAD)
    profile = 'm4-local'
    newWorkspaceRoot = 'E:\df-workspaces'
    savedAt = (Get-Date).ToString('o')
}
$archiveManifest | ConvertTo-Json |
    Set-Content -LiteralPath (Join-Path $archiveRoot 'manifest.json') -Encoding utf8
```

记录实际原 Workspace Root、existingRoots 和每个重要 WorkingCopy 的 `location`，可另外放入
本机归档备注。清单不包含 Key；本次示例也不复制日志、业务原文或 Git 文件。
SQLite 快照保存数据库关系和验证结果，**不包含 Git 副本**：原有 GS REST / DelveForge
WorkingCopy、源仓库及 `E:\df-m4-smoke` 历史证据须继续原地保留，不能移动或删除。
备份失败或校验不通过时不要启动新环境。原库及其 sidecar 文件不作清理。

## 3. 用独立新库启动 Task G 环境

使用新的 PowerShell 终端，确认只有一个后端使用所选库和 Workspace。
下面显式拒绝已经存在的新库，防止把旧测试数据误称为全新环境：

```powershell
Set-Location 'E:\develop\DelveForge'
$newEnvironmentRoot = 'E:\df-data\task-g'
$newDatabase = Join-Path $newEnvironmentRoot 'delveforge.db'
if (Test-Path -LiteralPath $newDatabase) { throw '目标库已存在；请选择另一个新路径，不要删除它。' }
$env:DELVEFORGE_PERSISTENCE_DATABASE_FILE = $newDatabase
$env:DELVEFORGE_WORKSPACE_ROOT = 'E:\df-workspaces'
$env:DELVEFORGE_LOG_DIR = Join-Path $newEnvironmentRoot 'logs'
# 新库没有旧副本绑定，不需要旧 Root；仅移除当前终端的配置变量，不删除磁盘目录。
Remove-Item Env:DELVEFORGE_WORKSPACE_EXISTING_ROOTS -ErrorAction SilentlyContinue

# 离线 UI / 启动检查可不配置 Key；用户主动进行真实 AI 验收时才安全注入。
Remove-Item Env:DEEPSEEK_API_KEY -ErrorAction SilentlyContinue
# $env:DEEPSEEK_API_KEY = Read-Host 'DeepSeek API Key' -MaskInput
java -jar backend/delveforge-app/target/delveforge-app-0.1.0-SNAPSHOT.jar `
    --spring.profiles.active=m4-local
```

缺失的数据库父目录由 `SqliteDataSourceConfiguration` 创建；Flyway 在新库从 V1 顺序初始化
至当前 V17。日志目录按 `DELVEFORGE_LOG_DIR` 写入。当前已有最新 verify 打包产物，不需要
为了切库重新构建。环境变量只影响从该终端启动的后端；更改 DB / Root 需要停机并重启。

在另一个终端只读核对连通、实际 Root 和空工作台：

```powershell
Invoke-RestMethod 'http://127.0.0.1:8080/api/system/connectivity'
Invoke-RestMethod 'http://127.0.0.1:8080/api/system/workspace-configuration'
Invoke-RestMethod 'http://127.0.0.1:8080/api/projects?offset=0&limit=50'
```

连通性端点不证明 Provider 可用。Root 配置回读不证明磁盘可写；新副本仍由正常 Prepare
进行边界、链接/junction、权限与冲突检查，失败不回退到 C 盘。新 Root 只影响后续新建。
可从 `GET /api/working-copies/{id}` 的 `location` 或验证演化页确认实际新副本位置。

## 4. 浏览器必须对应正确的数据库

前端不保存完整业务内容，但同一 origin 的 localStorage 保存三个键：
`delveforge.project.v1`（活动项目 ID）、`delveforge.session.v1`（对象 ID）、
`delveforge.execution.v1`（planId / stepId / startedAt 未决执行标记）。它们不包含数据库身份。
新库不能解析旧 ID；切库不会把旧项目迁移过来。

**推荐：**关闭旧环境页面，保留其普通浏览器存储；新环境使用独立浏览器配置文件或新的
InPrivate / 无痕会话打开 Vite 页面。不要同时保留能向同一端口提交操作的新旧页面。
前端仍可按 `frontend/README.md` 正常启动，既有 `/api` Proxy 无需改变。

如选择沿用普通窗口，只能在已确认旧请求结束后，于浏览器站点存储中定向移除旧 project / session
键并重新载入；不能把“清除站点数据”当成取消任务。未决 execution 标记须先在旧环境查明真实
结果，未查明时不得清除或靠新浏览器绕过其执行保护。已确认终态时优先让原界面查询并正常
解除标记。刷新不会恢复未保存的聊天或模型问题。

## 5. 需要回看归档时

先停止新环境，仍按第 1 节检查未决请求。不可同时让两套数据库中的同一历史记录操作同一个
物理 WorkingCopy。回看也不表示可以重放旧 execute 或用快照撤销已发生的 Git 修改。

保留归档不变：复制第 2 节**已通过校验的独立 backup 文件**到新的可写回看目录，再启动。
这里复制的是停止写入的备份文件，不是活动源数据库，也不复制或覆盖当前新库：

```powershell
Set-Location 'E:\develop\DelveForge'
$archiveRoot = Read-Host '第 2 节生成的归档目录绝对路径'
$manifest = Get-Content -LiteralPath (Join-Path $archiveRoot 'manifest.json') -Raw | ConvertFrom-Json
$backupDb = Join-Path $archiveRoot 'delveforge.db'
if ((Get-FileHash -LiteralPath $backupDb -Algorithm SHA256).Hash -ne $manifest.sha256) {
    throw '归档哈希不匹配，请停止。'
}
$replayRoot = Join-Path 'E:\df-data\archive-replay' (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
if (Test-Path -LiteralPath $replayRoot) { throw '回看目录已存在，请选择新位置。' }
New-Item -ItemType Directory -Path $replayRoot -ErrorAction Stop | Out-Null
$replayDatabase = Join-Path $replayRoot 'delveforge.db'
Copy-Item -LiteralPath $backupDb -Destination $replayDatabase -ErrorAction Stop
$env:DELVEFORGE_PERSISTENCE_DATABASE_FILE = $replayDatabase
$env:DELVEFORGE_WORKSPACE_ROOT = 'E:\df-workspaces'
$env:DELVEFORGE_LOG_DIR = Join-Path $replayRoot 'logs'
# 仅在确实需要访问该历史 Root 时设置；其它历史根按原 location 逐一核对。
$env:DELVEFORGE_WORKSPACE_EXISTING_ROOTS = Join-Path $env:USERPROFILE '.delveforge\m4-local\workspaces'
Remove-Item Env:DEEPSEEK_API_KEY -ErrorAction SilentlyContinue
java -jar backend/delveforge-app/target/delveforge-app-0.1.0-SNAPSHOT.jar `
    --spring.profiles.active=m4-local
```

`Join-Path` 只拼接目录字符串，不移动文件。existingRoots 允许检查已有副本，不在其中新建；
需要访问 `E:\dfw4` 等其它历史根时，按实际保存路径显式补充，不能猜测或列入整个磁盘。
回看使用当前代码，Flyway 只迁移可写副本；原库和归档均保持原样。普通窗口可以找回原 IDs，
也可在空浏览器直接从工作台选择历史。查看和恢复导航不调用 AI / Mutation / Maven；
软删除恢复、SUPERSEDED、共享画像版本和原执行守卫仍有效。

回看界面不是只读服务：本步骤不提供执行授权。尤其不能从较早备份继续推进已经在物理副本
上发生过的操作；有库 / Git 不一致时保留证据并停止。若需要再次访问最新原环境，可以停机后
将数据库变量改回原库路径，同时恢复其实际 Root 配置；不覆盖任何数据库。
