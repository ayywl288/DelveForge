package com.ayywl.delveforge.application.port.persistence;

import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import java.util.List;
import java.util.Optional;

/**
 * Product Direction 的持久化边界。
 *
 * <p>本接口由 Application 拥有、由 Infrastructure 实现（RULE-ARCH-003、RULE-ARCH-004）。
 * Application 只依赖本抽象，不得依赖 SQLite、MyBatis-Plus、Mapper、持久化 Entity
 * 等任何具体技术类型。
 *
 * <p>本 Port 不是通用 Repository：方法集合只覆盖当前的最小需求（保存一个方向、整批保存
 * 一次发现的结果、按标识读回、查当前 SELECTED）。按 userProfile 或 repositoryProfile
 * 查列表、分页与历史查询等到出现真实消费者时再补，而不是提前预留。
 *
 * <p>「查当前 SELECTED」之所以在这里出现，是因为 INV-D09 有了真实消费者：用户选择新方向时，
 * 原方向必须在同一次操作中进入 {@code SUPERSEDED}（DOMAIN_MODEL.md §6.2、§7.1），
 * 没有这个查询就无法在 Application 层表达切换。它只覆盖「当前 MVP 全局最多一个 SELECTED」
 * 这一个语义，不是通用过滤能力（{@link #findCurrentSelected()}）。
 *
 * <h2>Product Direction 是可更新的 Entity</h2>
 *
 * <p>与 {@link RepositoryProfileRepository} 不同：Repository Profile 是一次分析结果的
 * 快照，因此只允许写入一次；Product Direction 拥有生命周期（§6.2），同一个标识之后会
 * 因为合法领域行为从 CANDIDATE 变为 SELECTED、REJECTED 或 SUPERSEDED，
 * 因此本 Port 的 {@code save} 按标识覆盖，不是「只写一次」。
 *
 * <p>但覆盖的范围是有限的：
 *
 * <pre>
 * 可以更新    status —— 生命周期状态变化是这条方向自己的领域行为
 * 不得改写    最初生成该方向时的 discovery basis、recommendation content、
 *            candidate assets 与 Evidence（§10.5、RULE-DOM-007）
 * </pre>
 *
 * <p>后者不是靠调用方自觉：同一个标识再次保存时，若内容包括分析来源与已保存的
 * 版本不一致，保存会被拒绝（{@link ProductDirectionContentConflictException}），
 * 而不是静默改写历史，也不是静默忽略这次传入的内容。
 *
 * <p>本 Port 不判断方向内容是否正确或完整：方向是否合法由
 * {@code ProductDirection} Aggregate 决定（RULE-DOM-002），本 Port 只负责把它的
 * 当前状态写下去、按存储重建回来。
 */
public interface ProductDirectionRepository {

    /**
     * 写入一个新建的 Product Direction，或重复保存一条内容未变的方向。
     *
     * <p>该标识尚未保存时写入整条方向；已经保存过时，本次传入的**内容与状态**都必须与
     * 存储中的一致，否则拒绝。
     *
     * <h2>状态变化不走这里</h2>
     *
     * <p>本方法不接受状态变化：一条已经保存过的方向不能通过它把状态改成别的值。生命周期
     * 变化必须经 {@link #saveTransitions}，因为只有那里能声明「这次推进依据的是哪个状态」。
     * 少了那项声明，写入就无从判断自己手上的是不是一份过期副本——而按过期副本覆盖状态，
     * 会把一个已经提交的用户决定静默改掉（详见 {@link ProductDirectionTransition}）。
     *
     * <p>状态更新不得改写最初生成该方向时的 discovery basis、recommendation content、
     * candidate assets 与 Evidence（§10.5）。
     *
     * <p>整个写入必须是一个整体：身份行与内容行不能出现只写入一部分的中间状态。
     *
     * <p>REJECTED / SUPERSEDED 的方向同样会被保存下来，不因为状态而被删除：
     * 历史方向仍需保留（§10.5、RULE-DOM-007）。本 Port 不提供删除操作。
     *
     * @param productDirection 待保存的方向，不得为 {@code null}
     * @throws ProductDirectionContentConflictException 该标识已经保存过，
     *         且本次传入的内容与已保存的 discovery basis / recommendation content /
     *         candidate assets / Evidence 不一致
     * @throws ProductDirectionStatusConflictException 该标识已经保存过，
     *         且本次传入的状态与存储中的不一致；状态变化请改用
     *         {@link #saveTransitions}
     */
    void save(ProductDirection productDirection);

    /**
     * 一次写入一批方向，要么全部写入，要么全都不写入。
     *
     * <p>它存在的理由不是「少调几次」，而是<b>整批的原子性</b>：一次 Product Direction
     * Discovery 产生的候选方向是一组结果，其中任意一条写入失败都意味着这次发现没有完成。
     * 逐条调用 {@link #save} 会让先写入的那几条留在库里，留下一批「只出现了一部分」的候选
     * ——用户看到的就不再是模型这次发现的东西。
     *
     * <p>因此实现必须保证：任一元素写入失败时，这一批已经写入的部分一并回滚。
     * 调用方不需要、也无法自己拼出这个保证。
     *
     * <p>每一条的写入语义与 {@link #save} 相同。
     *
     * <h2>批次内的写入顺序就是列表顺序</h2>
     *
     * <p>这不是实现细节，而是调用方需要的一条契约：一次方向切换要在同一个批次里写两条
     * 方向——原 {@code SELECTED} 先变 {@code SUPERSEDED}，新方向再变 {@code SELECTED}
     * （INV-D09）。存储层只允许存在一个当前 {@code SELECTED}，因此这两条**必须按这个先后
     * 写入**；反过来写会在中间态撞上那条唯一约束。列表顺序是调用方表达这个先后的唯一方式，
     * 实现因此有义务保持它，而不是自行重排。
     *
     * @param productDirections 待保存的方向，按写入顺序排列；不得为 {@code null}，
     *                          元素不得为 {@code null}，可以为空（空批次不做任何事）
     * @throws ProductDirectionContentConflictException 某条方向已经保存过，
     *         且内容与已保存的不一致
     */
    void saveAll(List<ProductDirection> productDirections);

    /**
     * 在一次事务里写入若干次生命周期转换。
     *
     * <p>这是唯一可以改变状态写入路径。每一条转换都携带它依据的起始状态，实现必须在
     * **写入的同一条语句**里核对该状态是否仍然成立：
     *
     * <pre>
     * 仍然成立   写入这次转换
     * 已经变化   整批失败，抛 ProductDirectionStatusConflictException，一条都不写
     * </pre>
     *
     * <p>「整批」是必须的：一次方向切换就是两次转换（原方向离开 {@code SELECTED}，目标方向
     * 进入 {@code SELECTED}），它们是一次业务操作的两个半边。只写入其中一半会留下一个
     * 用户从未表达过的中间状态——原方向已经被取代，却没有任何方向被选中。
     *
     * <p>批次内的写入顺序与列表顺序一致，原因见 {@link #saveAll}。切换必须把「离开
     * SELECTED」的那一条排在前面。
     *
     * <p>本方法不写入 discovery basis、recommendation content、candidate assets 与
     * Evidence：它只改状态，因此状态变化不可能顺带改写方向当初凭什么被推荐（§10.5）。
     *
     * @param transitions 待写入的转换，按写入顺序排列；不得为 {@code null}，元素不得为
     *                    {@code null}，可以为空（空批次不做任何事）
     * @throws ProductDirectionStatusConflictException 某条转换依据的起始状态已经不是存储中
     *         的状态，或写入没有拿到存储的写锁；此时整批不生效
     * @throws ProductDirectionSelectionConflictException 写入 {@code SELECTED} 时已经存在
     *         另一个当前 {@code SELECTED} 方向（INV-D09）；此时整批不生效
     */
    void saveTransitions(List<ProductDirectionTransition> transitions);

    /**
     * 按标识查找 Product Direction。
     *
     * <p>恢复出来的方向包含它保存时的完整 discovery basis、recommendation content、
     * candidate assets、Evidence 与 lifecycle status。
     *
     * @param id 方向标识，不得为 {@code null}
     * @return 对应的方向；不存在时为空
     */
    Optional<ProductDirection> findById(ProductDirectionId id);

    /**
     * 查找当前处于 {@code SELECTED} 的 Product Direction。
     *
     * <p>它服务于 INV-D09：当前 MVP 只支持一个活动演化流程，因此系统全局最多只能存在一个
     * 当前 {@code SELECTED} 的方向。用户选择新方向时，原方向必须在同一次选择操作中进入
     * {@code SUPERSEDED}（DOMAIN_MODEL.md §6.2、§7.1），调用方据此拿到「要取代谁」。
     *
     * <p>刻意不提供按 User Profile、revision、Repository Profile 或候选资产收窄的版本：
     * 领域模型没有为「同一条演化流程」定义任何持久化身份，按这些维度收窄等于替它发明一个
     * （INV-D09 明确把 {@code EvolutionFlow} 身份排除在当前 MVP 之外）。因此这里查的是
     * <b>全局</b>那一个。
     *
     * <h2>多于一条不是「随便挑一条」</h2>
     *
     * <p>存储层由唯一约束保证最多一条（见 V7 migration）。读到多条说明存储状态与领域模型
     * 不一致——可能来自更早的数据、被绕过的写入，或迁移中的中间态。此时本方法失败，
     * 而不是返回其中一条：静默挑一条会让调用方以为系统里只有一个当前方向，
     * 而它刚刚取代的那个可能是有依据的另一个。
     *
     * @return 当前的 SELECTED 方向；没有时为空
     * @throws ProductDirectionIntegrityConflictException 存储里存在多于一条
     *         {@code SELECTED} 的方向
     */
    Optional<ProductDirection> findCurrentSelected();
}
