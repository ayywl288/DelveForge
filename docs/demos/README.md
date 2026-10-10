# Demo Catalog

三套可还原的 Java/Maven 演示基线，供 DelveForge 分析、发现方向和规划使用。
**验证级别均为 BUILD_READY，尚未通过 AI 演化；没有新的 EVOLUTION_VERIFIED 案例。**
源码、Git 历史、Bundle 和原始测试证据均在 DelveForge 主仓库外。

## 下载与本地位置

GitHub Release 附件**待发布**。当前没有公开下载链接，也没有自动 push。
本机完整包：`E:\df-demo-pack\20261011`；Bundle 位于其中 `bundles` 目录。
未来附件清单为三份 `.bundle` 与 `manifest.json`，发布须由用户另行确认。

[manifest.json](manifest.json) 保存精确 baseline、来源、许可、大小、SHA-256 与还原测试结果；
它和外部包根目录的 manifest 内容一致。不要把 README 内的方向建议当作真实模型结果。

| 文件（待发布） | 固定来源 | 许可 | Java 编译目标 / 实测 JDK | 真实测试 |
|---|---|---|---|---|
| `expense-tracker.bundle` | [ExpenseTracker @ d1b1678](https://github.com/akshitap30/ExpenseTracker/tree/d1b1678caef82f1ae034daaf0f4986814ff5a2f1) | MIT；Wrapper 为 Apache-2.0 | 21 / 21.0.12.1 | 12，通过 |
| `todolist-api.bundle` | [TodoList API @ 15353cd](https://github.com/EddyPBR/todolist-api/tree/15353cdd6fd7b3f743e9ed47ee5a4f5152339ff5) | MIT；Wrapper 为 Apache-2.0 | 17 / 21.0.12.1 | 4，通过 |
| `gs-rest-service.bundle` | [Spring REST @ 3f4cef0](https://github.com/spring-guides/gs-rest-service/tree/3f4cef01152596c19bc4d37939409358812b1421)，仅 complete Maven 工程 | 代码 Apache-2.0 | 17 / 21.0.12.1 | 3，通过 |

三套均采用 only-script Maven Wrapper 3.3.4 / Maven 3.9.14。JDK 21 上成功运行并不要求将
Java 17 项目的编译目标改成 21。首次构建需要下载依赖；本次离线还原测试使用已准备的缓存。
Spring 教程文字的 `LICENSE.writing.txt` 为 BY-ND，原样保留，不复制或修改上游教程正文。

## 每套演示适合做什么

### ExpenseTracker

现有 Java 后端含登录、按用户管理费用、搜索、分类、分页和收据。适配只增加本地 H2 demo
配置、调整 H2 依赖范围、隔离测试上传目录并补充真实 JPA 回归。新增测试覆盖实际 CRUD
持久化、查询范围和跨用户拒绝；邮件与文件存储使用替身，不证明真实邮件/收据流程通过。

可尝试的两个新方向：每月分类预算与超限提示；周期性支出规则与生成记录。两者都未预先实现。
若手动运行上游服务，demo profile 仍需要 `DEMO_JWT_SECRET`，注册邮件仍需本地 SMTP。
建议首先只构建/分析，不将它当作已部署的完整记账产品。

### TodoList API

现有代码包含 BCrypt/Basic 认证、用户注册、任务 CRUD、日期校验和按用户查询。
保留 Java 17 编译目标，补充 H2/MockMvc 测试，实际通过认证过滤器验证归属与日期规则。
上游 README 所称的完成/分类字段未出现在实际模型中。

可尝试的两个新方向：完成状态和筛选；按日期范围、优先级筛选。两者都未预先实现。
原 API 直接使用实体，响应可能暴露密码哈希，更新字段没有 DTO 白名单；本次没有做安全
重构。只使用合成数据，若启动服务必须限制为 loopback，不作为生产账号系统使用。
保留旧 wrapper.jar 文件，当前 only-script 启动器不执行它。

### Spring REST

最小的官方 REST 示例：`GET /greeting` 接收 name，返回递增 id 与问候文本。保留两个
HTTP 测试，补充 id 递增及 Unicode 姓名测试。没有复制 Gradle/Kotlin 或上游测试脚本。

可尝试的两个新方向：带支持语言校验的语言参数；有数量上限的批量问候接口。均未实现。
适合先理解 DelveForge 的确认、Scope 与验证流程，但它的业务复杂度明显低于另外两套。

## 从 Bundle 校验、还原与测试

在 DelveForge 根目录使用 PowerShell 7。以下使用本机包；附件发布后将 bundleDirectory
改为自己保存附件的目录。恢复目标选择短路径，以减少 Windows 嵌套 Maven 的路径压力。
目标必须不存在，不覆盖已有仓库。

```powershell
$bundleDirectory = 'E:\df-demo-pack\20261011\bundles'
$manifest = Get-Content .\docs\demos\manifest.json -Raw | ConvertFrom-Json
$demo = $manifest.demos | Where-Object id -eq 'todolist-api' # 可换另外两项
$bundle = Join-Path $bundleDirectory $demo.bundleFile
if ((Get-Item -LiteralPath $bundle).Length -ne $demo.sizeBytes) {
    throw 'Bundle 大小与 manifest 不符'
}
if ((Get-FileHash -LiteralPath $bundle -Algorithm SHA256).Hash.ToLowerInvariant() -ne $demo.sha256) {
    throw 'Bundle SHA-256 与 manifest 不符'
}

$demoRoot = Join-Path 'C:\df-demo' $demo.id
if (Test-Path -LiteralPath $demoRoot) { throw '目标已存在，请选择新的恢复目录' }
git -c core.autocrlf=false clone $bundle $demoRoot
if ($LASTEXITCODE -ne 0) { throw 'clone 失败' }
git -C $demoRoot bundle verify $bundle
if ($LASTEXITCODE -ne 0) { throw 'Bundle 不完整' }
if ((git -C $demoRoot rev-parse HEAD) -ne $demo.baselineRevision) { throw 'HEAD 不符' }
if (git -C $demoRoot status --porcelain) { throw '初始工作树不干净' }

Push-Location $demoRoot
try {
    .\mvnw.cmd verify # 新机器允许下载依赖；缓存准备好后可用 -o verify
    if ($LASTEXITCODE -ne 0) { throw 'Maven 验证失败' }
} finally { Pop-Location }
if (git -C $demoRoot status --porcelain) { throw '构建修改了未忽略的文件，请先检查' }
```

Bundle 为自包含本地 Git 历史，不依赖上游 clone 仍然存在。查看每套 `ORIGIN.json` 中的
importedRevision 后，可直接检查适配：

```powershell
$origin = Get-Content (Join-Path $demoRoot 'ORIGIN.json') -Raw | ConvertFrom-Json
git -C $demoRoot diff --stat $origin.importedRevision $demo.baselineRevision
git -C $demoRoot diff $origin.importedRevision $demo.baselineRevision -- pom.xml src/test .gitattributes
```

每套 `src/main/java` 均与原始导入一致。额外测试、Wrapper、配置和说明不是 AI 生成的
Candidate。C3 还原时发现 TodoList / Spring POM 的原 CRLF blob 与新增强制 LF 规则不匹配；
去掉不适用的规则后另行提交，原 POM 保持不变。早期 Bundle 和失败 clone 保留在外部证据
中；最终版本以本 manifest 为准，不用 reset 或放松 Git guard 获得洁净状态。

## 注册与体验 DelveForge

按 [本地启动](../M4_LOCAL_USAGE.md) 配置独立 SQLite / Workspace、后端 API Key，然后启动
前端。浏览器不保存 Key，也不能直接读取任意本机目录。将上述 demoRoot 的绝对路径填到
Repository Understanding 页面，阅读对应许可后**明确授权读取及使用**。

也可在确认授权后，仅通过正常 API 登记（登记本身不调用 Provider）：

```powershell
$registration = @{
    location = $demoRoot
    readPermissionAllowed = $true
    licenseInfo = "$($demo.license); Apache-2.0 Maven Wrapper; see included license files"
    usageAuthorization = 'ALLOWED'
}
$asset = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/software-assets' `
    -Method Post -ContentType 'application/json' -Body ($registration | ConvertTo-Json)
$asset.id # 使用服务端实际返回的 ID，不使用示例 UUID
```

随后使用五阶段界面：回答需求问题并审阅/确认本版画像 → 触发仓库分析 → 阅读并选择方向
→ 审阅 Plan 并准备 WorkingCopy → 确认当前 Step，再单独同意执行。分析、聊天、方向、规划
与提案都会产生 Provider 费用。不会因登记或确认画像而自动运行演化。

这里没有给出预选方向、固定 Plan、业务数据库或旧 Smoke 的 ID/revision。建议用户第一次
选择单点功能和真实回归测试，检查生成 Scope/Criteria 是否适用于该项目后才授权 execute。
请求断开后查询实际检查点，不能自动重复 execute。

## 当前限制及证据

只验证 JDK 21 / Maven / Windows。DelveForge 当前支持 BUILD、完整/指定 Java 测试和 Git
diff 技术检查，不验证任意业务标准或外部服务；不能把 unsupported criterion 降级为通过。
正常 Maven 插件和测试仍以宿主权限运行，**没有 OS 沙箱**，仅使用已授权、可审查的可信仓库。
依赖、测试和 build script 变化后，应重新审查和构建，不能沿用这份 baseline 的结果。

本次没有付费 Provider 调用，三个演示都未获得真实 CodingProposal / Candidate / Verified B。
`m4-local` 的成功高预算作用于所有 AI 阶段，默认配置没有同等真实成功证据；跨仓库输出质量
与补丁成功率需要真实手动验收。M5 未实现，失败 Candidate 的恢复能力有限，不可手工改库
或 reset 活跃 Candidate。

- [C1 交互一致性验证](../validation/task-c1-user-discovery-consistency.md)
- [C2 来源、适配与构建验证](../validation/task-c2-demo-build-readiness.md)
- [C3 Bundle / README 验证](../validation/task-c3-demo-bundle-delivery.md)
- 外部 `evidence/<demo>/`：adaptation.patch、原/还原 Surefire XML、历史与 Bundle 检查。
- 外部 `evidence/<demo>-restore-verify.log`：最终还原后的离线 Maven 构建。
- 外部 `upstream/`、`licenses/`：固定来源和原许可；不与 GitHub Release 发布混同。
