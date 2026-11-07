# MODEL_INCOMPLETE：完成失败诊断与检查汇总修复

日期：2026-11-06（UTC-8）。基于本地 main `5159b15`。
用户在换号后明确授权继续修复，保留常规测试；真实产品端到端流程由用户手动验收。

## 证据与问题边界

本次只读核对数据库、已有日志、正式 WorkingCopy 的 Git 对象与
[640 条持久事件](evidence/model-incomplete-0b450d5f.json)。未修改产品数据库、原始资产或正式 WorkingCopy，未启动付费 Run。
完整身份和时间线见[原交接](model-incomplete-handoff-20261106.md)。

Run `0b450d5f-1cd5-47e7-9632-13c14ad3c48f` 已在一次完成检查被拒后继续编辑与检查，
最终由模型无工具回复的 `status=failed` 分支结束；不是平台再次因为补丁冲突次数直接终止。

sequence 1808 的拒绝只反馈一个测试哈希，没有比较失败的具体条件或源文件：
`7ec56b43a61092c0ff5deb4e9bdc81d77a6705fe4a644aa48ea7f536c05f1135`。
按照现有报告身份算法，核对基线测试后可定位为：

```text
backend/bookstore:com.dorian.bookstore.BookstoreApplicationTests#contextLoads
backend/bookstore/src/test/java/com/dorian/bookstore/BookstoreApplicationTests.java
```

最终同一源码 snapshot 的前端测试 13 项通过；后端 17 项中仍有 3 errors；前端 build 通过。
页面原来只取最后一项，显示“构建通过，未运行测试”，掩盖了后端测试失败。

旧实现还丢弃了模型 `status=failed` 的 summary，只保存 `MODEL_INCOMPLETE` 分类。
Run 临时目录已经不存在，事件没有完整逐例失败清单，无法恢复最后说明，
也无法证明最终 3 errors 与基线 3 errors 的失败指纹、关联源码完全相同。
因此本次没有把数量相同当成历史债务，也不宣称旧 Run 的全部失败根因已经查清。

原交接中 instruction 乱码的怀疑已排除：数据库与导出 JSON 内容一致，Unicode 中文完整、无替换字符。
终端显示异常不代表模型收到的指令损坏。

## 修复

- 基线比较保持原接受规则，拒绝时反馈具体比较条件、检查工程、测试源文件和测试 ID；
  若涉及已修改关联源码，同时提供该路径。模型可以据此定位并调整，避免只有不透明哈希。
  条件覆盖新增失败、结果变化、失败指纹变化、测试定义变化、关联源码变化及修改测试未通过。
- 模型报告无法完成时，先净化并保存最多 1,200 字符的说明，再走既有失败和资源释放流程。
  页面以“模型说明”展示，并在详情中标明未经独立验证；旧记录不补造说明。
- 检查汇总按最近检查的源码 snapshot，分别追踪工程、目标、模块与选测范围，优先显示未通过项。
  前端 build 和单项选测成功不能覆盖后端全量失败；同范围重新通过可更新显示。
  新源码 snapshot 不沿用旧版本的通过或失败结果。选测范围只记录指纹，不保存原始选择表达式。
- 完成检查拒绝原因可在活动详情查看，文本按普通文字渲染。

不新增自动重试次数或总预算限制，不改变完成判定、基线债务、授权、Candidate 或 Delivery 语义。
历史检查汇总仍是诊断记录，不是最终交付验证结论。

## 验证

针对性回归覆盖失败比较的具体原因、关联路径、模型说明净化和事件顺序、
真实问题对应的同版本跨工程检查、选测与全量区别、版本变化、旧记录回退和文字转义。
先观察旧实现失败，再验证修复；完整回归另发现活动记录不能保存原始选测参数，已改用范围指纹并保留原隐私断言。

最终 `mvnw.cmd verify` 于 23:21:53（UTC-8）BUILD SUCCESS：本次调用共 1,931 项，
1,871 项通过、60 项按条件跳过，0 失败/错误，四模块构建与应用打包通过。
计数来自本次日志，不累加 target 中残留的旧测试 XML。
57 项依赖显式启用的 Docker、外部仓库或真实模型环境，本次未启用；另 3 项为单独启用的全新公开仓库克隆验收。
日志：`target/completion-diagnostics-verify.log`。

前端 `pnpm test`：22 个文件、316 项通过；`pnpm typecheck`、`pnpm build` 均成功。
日志分别为 `target/completion-frontend-test.log`、`completion-frontend-typecheck.log`、`completion-frontend-build.log`。
没有启动真实模型或产品端到端流程。

局部组件预览直接加载上述真实事件，并单独标识合成的新说明示例。
1440、720、420 宽度均无横向溢出或浏览器控制台错误；真实记录显示后端 17 项 / 3 errors。
这是组件渲染检查，不是产品端到端或真实模型验收。
本地截图和测量保存在 `target/completion-diagnostics/ui/`，样式检查无新增发现。

## 使用

合并后重启本地后端并刷新前端。此次未修改执行镜像，不需要重新构建镜像。
旧 Run 保持 FAILED；新说明仅对使用新后端的后续运行有效。
本次没有自动重跑用户任务，真实端到端验收留给用户。
