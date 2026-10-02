/**
 * 仓库来源的凭据边界：一条政策，两个执行点（ADR-0006）。
 *
 * <pre>
 * RepositorySecretPolicy          政策本身：路径判定 + 内容净化
 *         │
 *         ├── ① 读取之前            读取规划器用 excludes(path) 把整份凭据文件挡在材料之外
 *         └── ② 交给模型之前        理解阶段末尾用 sanitize(material) 替换内容里的凭据字面量
 *
 * DeterministicRepositorySecretPolicy   规则写死在代码里的确定性实现
 * SanitizedRepositoryMaterial           净化后的材料 + 替换计数（模型可见的那一份）
 * RepositorySecretBoundaryException     边界无法安全完成时的失败出口
 * </pre>
 *
 * <h2>它管的是什么</h2>
 *
 * <p>「仓库里受版本控制的凭据不要被送给外部 AI Provider，也不要因此被复述进
 * RepositoryProfile / Evidence / 后续 Prompt」。这个问题与 ADR-0002（日志里不出现运行时
 * 自由文本）是**两个出口**，本包不重新设计日志。
 *
 * <h2>它保证什么，不保证什么</h2>
 *
 * <pre>
 * 保证   命中已实现规则的路径不被读取；命中已实现规则的字面量不会被原样发给模型
 * 不保证 未知格式 / 自研格式 / 拼接 / 编码分片构造的凭据；
 *        路径本身（它是 Evidence 的定位依据，始终原样发送）；
 *        二进制内容的文本形态（只有路径能识别它）
 * </pre>
 *
 * <p>因此不要把本包读成「已经确保没有凭据」。它缩小的是**已知形态**的暴露面，
 * 并让这件事可测试、可复述。
 *
 * <h2>本包不做什么</h2>
 *
 * <pre>
 * 不读文件                 路径判定只吃一条字符串，因此它无法在自己这一层读东西
 * 不调用 AI                没有任何 AI 能力，也不持有它
 * 不写日志                 没有 logger，因此边界自己不可能把看到的取值写进日志
 * 不持久化                 它只在一次分析内存在
 * 不判断真相               处于凭据位置的值一律替换，不尝试分辨「示例」与「真凭据」
 * </pre>
 *
 * <p>规则是**代码定义的**安全政策，不做成可配置项（ADR-0006）：可配置就等于可以被
 * 静默放宽，而那次分析看起来仍然正常。
 */
package com.ayywl.delveforge.application.repositoryanalysis.secret;
