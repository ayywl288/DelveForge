package com.ayywl.delveforge.application.repositoryanalysis.secret;

import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositorySourceFile;
import java.util.List;

/**
 * 仓库来源的凭据政策：一条政策，两个执行点（ADR-0006）。
 *
 * <pre>
 * 仓库描述符
 *         ↓  ① excludes(path)              —— 读取之前，在读取规划器里
 * 保留下来的文件被读取
 *         ↓  ② sanitize(material)           —— 交给模型之前，在理解阶段末尾
 * 模型可见的仓库材料
 * </pre>
 *
 * <h2>两个执行点是同一条政策</h2>
 *
 * <p>拆成两处不是两套系统：同一个实现同时回答「这份文件该不该读」与「这段内容哪些地方
 * 不能给外部模型看」。之所以必须有两个点，是因为它们能做的事情不同——
 *
 * <pre>
 * ① 在读取之前   可以整份不读。二进制凭据（.p12 / .jks）在文本层无从识别，
 *                只有路径能识别；而没读进来的文件不可能出现在请求里。
 * ② 在读取之后   只能改内容。application.yml 这类配置是最可能的凭据位置，
 *                同时也是最有价值的分析材料——不能整份丢掉，只能替换其中的取值。
 * </pre>
 *
 * <p>任缺一处都会留下明显的洞：只有 ① 会漏掉源码与配置里的凭据，只有 ② 会漏掉
 * 那些内容层无从识别的整份凭据文件。
 *
 * <h2>它不保证「所有凭据都不会泄漏」</h2>
 *
 * <p>规则是**枚举 + 形态判断**：命中什么就保护什么。未知令牌格式、自研格式、
 * 被拼接 / 编码 / 分片构造的凭据都可能不命中。这一点写在 ADR-0006 的「不保证」一节里，
 * 不应被读成「已经确保没有凭据」。
 *
 * <h2>它不读文件、不调用 AI、不写日志</h2>
 *
 * <p>两个方法都是对**已经拿到的数据**的纯判定与纯文本处理：不持有 Workspace 能力、
 * 不持有 {@code AiGateway}、没有 logger。因此它无法读取它本不该读的东西，
 * 也无法把看到的内容写进日志——「边界自己不漏」不需要额外证明。
 */
public interface RepositorySecretPolicy {

    /**
     * 这份描述符是否属于「按定义就是凭据载体」的路径，因而整份不进入材料。
     *
     * <p>判定只看路径，不看内容——调用方在**读取之前**用它，因此此刻还没有内容可看。
     * 这是本政策唯一一处可以整份排除文件的地方。
     *
     * @param relativePath 仓库内相对路径，不得为 {@code null}
     * @return 该文件是否应当被排除
     */
    boolean excludes(String relativePath);

    /**
     * 把读到的材料转成**模型可见**的材料：替换掉识别出的凭据字面量。
     *
     * <p>相对路径不变——它是 Evidence 定位文件的依据，改了就不可追溯。
     *
     * @param files 本次分析读到的材料，不得为 {@code null}，元素不得为 {@code null}
     * @return 净化后的材料与替换计数
     * @throws RepositorySecretBoundaryException 边界无法安全完成这次净化
     */
    SanitizedRepositoryMaterial sanitize(List<RepositorySourceFile> files);
}
