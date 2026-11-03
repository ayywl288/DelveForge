# 隔离项目脚本与测试证据：工程验证

日期：2026-11-03。分支 `codex/sandbox-script-evidence`，起点 `c55d62b`。**ENGINEERING_READY=YES。**
本页仅记录工程门槛；真实 DeepSeek 四步试验与产品验收另行记录，不能用自有夹具替代真实产品成功。

## 根因与实现

旧实现用有限的 Vitest/Vite 脚本文本识别承担执行准入，合法 Node 原生测试因此没有启动；未启动的配置拒绝又被记成了未决测试失败。继续追加字符串例外无法覆盖正常项目包装。
新实现保留目录和目标接口，由固定 npm/pnpm 在无网络一次性容器执行声明脚本；联网准备仍只有净化后的依赖元数据、冻结锁和禁用生命周期的安装器。报告采集与执行授权分开：Node JUnit、Vitest JSON 及 runner 退出记录共同归一化实际计数，无法证明执行的成功脚本保持 TEST_EXECUTION_UNVERIFIED。
evidenceVersion=3 区分 NOT_STARTED/EXECUTED/UNKNOWN，按工程、目标、runner 和最终 snapshot 追踪。配置拒绝保留历史但不伪装为失败测试；真实失败只能由匹配的当前通过解决。旧证据保守兼容，删除脚本、无关构建/Maven 或过期结果均不能解决测试失败。
原 TargetState 上下文已存在并保留；仅补充报告、Node 测试和包装约定的提示。无新模块、依赖、数据库迁移、Agent Loop 重写或 Delivery 实现。设计取舍见 [ADR-0021](../decisions/0021-sandbox-enforced-project-scripts-and-test-evidence.md)。

## 实际执行

| 检查 | 结果 |
| --- | --- |
| `mvnw.cmd -q verify` | 1839 项，0 failure/error，43 conditional skip；实际执行 1796 项 |
| 单独启用 Docker 的交付闭环 | Maven、npm/Vitest、pnpm/Vitest、npm/Node、pnpm/Node 共 5 条全部 COMPLETED，0 skip |
| 最终 Node 夹具与取消/秘密读取/链接逃逸 | 2 + 3 项通过，0 skip |
| Java 协议回归 | 17 项，16 执行通过，1 旧镜像环境条件跳过；包含实际新镜像检查 |
| 最终镜像 Python | 82 项通过，含真实 Node 包装、断网、只读路径、detached 子进程、超时和 Node 堆耗尽 |
| 实际镜像 Python 协议 | 7 项通过 |
| DelveForge 前端 `pnpm --dir frontend test/typecheck/build` | 306 项测试、类型检查、生产构建通过 |
| 打包一致性 | JAR 中 sandbox.py/frontend.py/frontend-report.cjs/proxy.py 与最终源字节一致 |

后端模块计数为 domain 209、application 862、infrastructure 546、app 222。条件跳过不是实际 Docker 通过；真实执行的 JUnit 已独立保存，未被后续普通 verify 的跳过记录替代。
真实 Docker 命令通过 Maven Wrapper，指定最终 `DELVEFORGE_AGENT_IMAGE_21/17` 并设置 `DELVEFORGE_AGENT_DOCKER=true`，执行 FullStackAgentSmokeTest、NativeFrontendDeliverySmokeTest 和三项 DockerAgentSandboxTest。协议另指定 DELVEFORGE_SANDBOX_PROTOCOL_IMAGE。完整命令及日志保存在证据根目录。

## 实际失败、修正和交付

固定 Node 22.16.0 原样执行 `node --test tests/` 实际 MODULE_NOT_FOUND，而不是配置白名单拒绝；有效文件入口随后真实执行两个测试，一通过一失败；修正源码后，三个测试通过（含实际隔离检查），再构建、冻结和交付。夹具没有 POM 或 Vitest 依赖，构建使用项目 Node 包装调用 Vite，测试使用嵌套 npm/pnpm 包装。
第一次工程运行的 pnpm 场景保留失败：一条触发安全拒绝，另一条因 pnpm 运行前的隐式重装丢失 Vite。最小实际探针确认 pnpm 12 的 verifyDepsBeforeRun 会重装依赖；同时 packageManager 可触发自动下载。现明确关闭这两个动作，使用已经冻结的准备结果；没有绕过源码守卫或更新锁摘要。最终所有场景均通过，旧失败日志保留。

| 场景 | Candidate | Delivery revision |
| --- | --- | --- |
| fullstack-maven | `7c76ca53-7c39-49c5-bcc8-a3d584404fd3` | `a21426044302fe7f4aa582157a0d38392df0c93b` |
| fullstack-npm | `8530da32-2e49-482c-a751-bb2c4827549a` | `62872f9ce95b5a85089c15295607099713b68fa7` |
| fullstack-pnpm | `b4289d01-27ab-4ff2-8816-a031ceedb22c` | `fe977adf0da0d188bf4cca43710f22ff8addc3ff` |
| native-npm | `5f6cb66c-4bd1-4c7b-9d12-aac3ecb0df26` | `ddc47c45ec3c2472c7948c427d78bb1d45d824f0` |
| native-pnpm | `95bd8d3b-f162-409f-b66a-9b61d8ac554d` | `3d2a81a0474d5bb5e4a4ca6cb88a45f71f2953cc` |

每个 Node 场景的前两条失败仍在证据中并标为 stale，最终 Node 结果为 3 executed/0 failed，build 为 0 executed。匹配重验才解决历史失败。交付前正式副本仍为 R；接受的 C 验证 parent=R、tree=Candidate.expectedTree；原资产 revision/状态及受保护资源保持。

## 安全和边界

容器非 root、根只读、cap-drop、no-new-privileges、网络 none、CPU/内存/PID/输出/超时限制保持。前端不挂载 Maven 缓存；复用依赖卷只读，不给项目进程 API key 或 Docker socket。禁用 core dump；PID 1 在核对报告和源码前杀死、回收剩余进程，包括 detached 子进程；取消/失败还由 Java 销毁容器与一次性卷。检查前后 snapshot 及权威源再次核对，不回传检查副本源码、依赖或生成物。
锁不一致、错误完整性摘要、pnpm hooks/workspace/私有源限制，报告缺失/损坏、零测试、不同 runner/工程/目标、stale、删除脚本等负例均有回归。堆耗尽测试是实际 Node 进程内存失败，不称 Docker OOM 测试。报告不是断言业务充分性的认证。
仍仅支持既有 Vue 3/Vite 单工程、公共 registry、npm/pnpm 锁格式和固定运行时；安装期脚本、自定义工具链/私有凭据、无法采集的程序化 runner/覆盖 NODE_OPTIONS 的包装仍可能不可用或未验证。技术测试不替代独立产品验收。

JDK21 镜像：`sha256:ba7a6e488c932bc5947635a7c1e73663eef0a367477e6c55c2e9f6609c16c5dc`。
JDK17 镜像：`sha256:0f5ab81f34eb647cfee71d4ed256684491b751c330883b0914b8d332a29d3944`。
应用 JAR SHA-256：`8a6eba7f57ff4677dd4f43e5d75551e8cee2f3f89e08aa35551e060a06c7348b`。

结构化记录见 [工程证据](sandbox-script-evolution-20261103.json)。本地原始证据：`target/script-evidence-20261103/`。没有推送或合并，没有修改 frontend/design/、grilling/ 或历史验证报告。
