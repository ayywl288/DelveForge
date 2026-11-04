# ADR-0020：仓库保留、可见、执行和修改能力

Date: 2026-11-02

Status: Accepted for the explicitly authorized admission task; acceptance results recorded separately.

## 问题与约束

路径排除同时承担秘密保护、仓库准入、源传输和编辑授权，导致公开模板也无法接入。
仅放行 Git 预检不足以通过后续 Python 快照与 Candidate 捕获。原始 Git 身份、正式 WorkingCopy、
逐 Step 授权、不可变 Candidate 及确定性 Delivery 必须保持，不以秘密扫描证明任意内容无害。

## 决定

Infrastructure 的一个确定性内容能力策略产生三种内部处置：

| 处置 | 保留原始 Git | 模型内容 | 执行材料 | Agent 修改 |
| --- | --- | --- | --- | --- |
| SOURCE | 是 | 现有净化后可见 | 是 | 现有行保护及 Step 边界 |
| PUBLIC_RESOURCE | 是 | 不可见 | 校验原摘要后可用 | 禁止 |
| PRESERVED_ONLY | 是 | 不可见 | 不传入容器 | 禁止 |

公开资源只能由小型正向语法确认：配置的空值/环境引用/受限公共值、受控 npm registry 设置、
可解析且仅含公有证书的材料。名称不是安全证明；未知配置、私钥或已识别的凭据内容采用 PRESERVED_ONLY。
普通源码继续采用 ADR-0017 的明确识别范围，命中保护的原内容不进入执行视图；不增加语义/数据流秘密扫描。
未知形态仍不承诺识别。危险路径、大小写冲突、symlink、submodule 和不支持的 Git 类型继续拒绝。

本轮实际诊断发现：Fetch 的 `credentials = 'include'` 与模板里的完整 `${object.field}` 被旧直接字面量规则误判。
共用凭据策略仅补充这两种封闭、无实际凭据取值的形态：JS/Vue 反引号头部模板内带引号的完整引用，及 Fetch 枚举；普通引号中的 `${...}` 仍保护。credentials 只接受
[Fetch 标准](https://fetch.spec.whatwg.org/#requestcredentials)的 omit/same-origin/include，引用不含调用、运算或默认值。
已知密钥格式独立生效，password=include、硬编码示例口令和带秘密的注释仍受保护；不增加 AST 或数据流分析。
若被保留文件是可执行源码、测试或构建描述，当前视图不允许产生检查 PASS，防止移除测试后剩余测试制造成功。

2026-11-04 实际续跑另发现 Java 三元运算的引号操作数被误当作凭据属性：
`condition ? "generated-password" : "provided-password"`。仅在 Java 的确定代码位置排除这类
紧邻问号的完整操作数形态；注释、字符串、含 Unicode 转义或文本块的文件及其他语言不取得该豁免，已知密钥格式仍独立生效。
这不放行直接凭据赋值或硬编码测试口令；实际安全阻塞与验证见
[原随机试验续跑](../validation/random-fullstack-resumption-20261104.md)。

正式仓库和可信主机 Git 对象完整保留 R。发送到容器的树 E 从 R 的独立 index 精确删除 PRESERVED_ONLY 条目，
不创建准备提交、不改正式源码。E 是执行投影，不能称为字节相同的完整 checkout。
Java 产生绑定 path/mode/SHA-256/size 的能力清单；容器不重新推断内容安全。
快照包含实际 E 的摘要和不可物化条目的基线身份，明确记录 sourceView；未物化文件必须不存在，公开资源必须原字节不变。
最终捕获从 R 重放修改：被保留条目只能沿用原 blob/mode，不能新增、删除或改写；Delivery 仍核对完整 R、D、B、C。
检查证据记录执行视图，不把缺失资源、不可用检查、失败或未执行的测试转换成 PASS。

## 取舍与边界

允许保留敏感文件而不把它交给模型或项目进程，避免全仓库拒绝，也避免修改源库来消除风险。
依赖这些文件的构建可能失败；系统不会补入凭据或静默创建替代文件。
公共配置在执行副本中只读使用；包安装仍采用筛选 manifest、冻结锁、固定源和禁用脚本，不执行仓库 .npmrc/hooks。
本次不支持链接解析，不增加审批生命周期、领域 Aggregate、数据库结构、包管理器或自由命令。
与每层复制一套文件名白名单相比，摘要清单增加少量传输及捕获规则，但政策由单一 Java 实现负责。
