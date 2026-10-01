package com.ayywl.delveforge.application.repositoryanalysis.scout;

import static com.ayywl.delveforge.application.repositoryanalysis.scout.ScoutFixtures.CONTROLLER;
import static com.ayywl.delveforge.application.repositoryanalysis.scout.ScoutFixtures.CONTROLLER_REF;
import static com.ayywl.delveforge.application.repositoryanalysis.scout.ScoutFixtures.ENTITY;
import static com.ayywl.delveforge.application.repositoryanalysis.scout.ScoutFixtures.ENTITY_REF;
import static com.ayywl.delveforge.application.repositoryanalysis.scout.ScoutFixtures.MAPPER;
import static com.ayywl.delveforge.application.repositoryanalysis.scout.ScoutFixtures.MAPPER_REF;
import static com.ayywl.delveforge.application.repositoryanalysis.scout.ScoutFixtures.SERVICE_IMPL;
import static com.ayywl.delveforge.application.repositoryanalysis.scout.ScoutFixtures.SERVICE_IMPL_REF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryFileReference;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证引用校验：模型返回的 {@code RF-*} 必须解析回建立本次调用时那张 Map 里的描述符。
 *
 * <p>这是「模型输出中的路径永远不进入读取调用」这条约束的实现处——解析回来的路径来自 Map，
 * 不来自模型。
 */
class RepositoryScoutProposalResolverTest {

    private final RepositoryScoutProposalResolver resolver = new RepositoryScoutProposalResolver();

    private final RepositoryScoutInputs inputs = ScoutFixtures.inputs();

    @Test
    void resolvesEveryReferenceBackToItsMapEntry() {
        RepositoryInspectionPlan plan = resolver.resolve(ScoutFixtures.validProposal(), inputs);

        assertEquals(ScoutFixtures.REVISION, plan.analyzedRevision());
        assertEquals(3, plan.areaCount());
        assertEquals(List.of("对外接口", "领域模型", "服务实现"),
                plan.areas().stream().map(RepositoryInspectionArea::label).toList());
        assertEquals(List.of(CONTROLLER), pathsOf(plan, 0));
        assertEquals(List.of(ENTITY, MAPPER), pathsOf(plan, 1));
        assertEquals(List.of(SERVICE_IMPL), pathsOf(plan, 2));
    }

    /**
     * 解析回来的是 Map 里那些描述符本身。
     *
     * <p>不是按值相等的副本——将来的读取要拿到真实的路径与 revision，
     * 而不是一个碰巧内容相同的对象。
     */
    @Test
    void restoresTheVeryEntriesHeldByTheMap() {
        RepositoryInspectionPlan plan = resolver.resolve(ScoutFixtures.validProposal(), inputs);

        RepositoryMapEntry controller = plan.areas().get(0).entries().get(0);
        assertSame(inputs.catalog().get(0), controller);
        assertEquals(CONTROLLER, controller.relativePath());
        assertEquals("RF-4", controller.reference().value());
    }

    // ---------------------------------------------------------------------
    // 拒绝：引用不属于本次调用
    // ---------------------------------------------------------------------

    @Test
    void rejectsUnknownReference() {
        AiGatewayException failure = assertThrows(AiGatewayException.class,
                () -> resolve(area("编造的编号", ref(99))));

        assertEquals(true, failure.getMessage().contains("RF-99"),
                "错误信息应当指出编号: " + failure.getMessage());
    }

    /**
     * 引用落在 Foundation 上：编号真实存在，但本次没有作为源码候选提供。
     *
     * <p>这与「编造编号」是两种不同的越界，因此错误信息也分开——诊断时能看出模型是凭空
     * 造了一个编号，还是把基础材料当成了源码候选。
     */
    @Test
    void rejectsReferenceToFoundationEntry() {
        AiGatewayException failure = assertThrows(AiGatewayException.class,
                () -> resolve(area("基础材料", ref(ScoutFixtures.POM_REF))));

        assertEquals(true, failure.getMessage().contains(ScoutFixtures.POM),
                "错误信息应当指出那个文件: " + failure.getMessage());
    }

    @Test
    void rejectsReferenceToNoneEntry() {
        AiGatewayException failure = assertThrows(AiGatewayException.class,
                () -> resolve(area("测试代码", ref(ScoutFixtures.TEST_REF))));

        assertEquals(true, failure.getMessage().contains(ScoutFixtures.TEST),
                "错误信息应当指出那个文件: " + failure.getMessage());
    }

    /**
     * 只要有一个引用不合法，整次侦察失败——不丢掉那个区域、不保留其余部分。
     *
     * <p>部分成功会让调用方以为「模型只指出了这几处」，而真实情况是它的输出不可用。
     */
    @Test
    void oneInvalidReferenceFailsTheWholeResult() {
        AiRepositoryScoutProposal proposal = new AiRepositoryScoutProposal(List.of(
                new AiRepositoryScoutFocusArea("合法", List.of(ref(CONTROLLER_REF))),
                new AiRepositoryScoutFocusArea("非法", List.of(ref(ScoutFixtures.POM_REF))),
                new AiRepositoryScoutFocusArea("也合法", List.of(ref(ENTITY_REF)))));

        assertThrows(AiGatewayException.class, () -> resolver.resolve(proposal, inputs));
    }

    // ---------------------------------------------------------------------
    // 顺序与重复
    // ---------------------------------------------------------------------

    /**
     * 区域顺序与区域内顺序都保持模型给出的样子。
     *
     * <p>顺序是模型表达优先级的方式（越靠前越应该先看），因此既不能排序也不能重排。
     */
    @Test
    void preservesAreaAndFileOrdering() {
        AiRepositoryScoutProposal proposal = new AiRepositoryScoutProposal(List.of(
                new AiRepositoryScoutFocusArea("第三", List.of(ref(SERVICE_IMPL_REF))),
                new AiRepositoryScoutFocusArea("第一", List.of(ref(MAPPER_REF), ref(CONTROLLER_REF))),
                new AiRepositoryScoutFocusArea("第二", List.of(ref(ENTITY_REF)))));

        RepositoryInspectionPlan plan = resolver.resolve(proposal, inputs);

        assertEquals(List.of("第三", "第一", "第二"),
                plan.areas().stream().map(RepositoryInspectionArea::label).toList());
        assertEquals(List.of(SERVICE_IMPL), pathsOf(plan, 0));
        assertEquals(List.of(MAPPER, CONTROLLER), pathsOf(plan, 1));
        assertEquals(List.of(ENTITY), pathsOf(plan, 2));
    }

    /**
     * 跨区域重复引用是允许的，且**不做合并**：同一个文件出现在两个区域里，
     * 两个区域里都保留它。合并属于后续阶段。
     */
    @Test
    void allowsTheSameFileInDifferentAreas() {
        AiRepositoryScoutProposal proposal = new AiRepositoryScoutProposal(List.of(
                new AiRepositoryScoutFocusArea("缓存视角", List.of(ref(SERVICE_IMPL_REF))),
                new AiRepositoryScoutFocusArea("接口视角", List.of(ref(CONTROLLER_REF))),
                new AiRepositoryScoutFocusArea("实现视角", List.of(ref(SERVICE_IMPL_REF)))));

        RepositoryInspectionPlan plan = resolver.resolve(proposal, inputs);

        assertEquals(List.of(SERVICE_IMPL), pathsOf(plan, 0));
        assertEquals(List.of(SERVICE_IMPL), pathsOf(plan, 2));
        assertSame(plan.areas().get(0).entries().get(0), plan.areas().get(2).entries().get(0));
    }

    /**
     * 区域内重复由**解析层**拒绝，并且失败类型是 AI 边界失败。
     *
     * <p>本类接受的是「不可信提议」这一类型，不假设它一定来自解析器——直接构造一份带重复的
     * 提议同样必须被拒绝。若把这个判断留给可信类型的构造器，抛出的是参数异常，
     * 语义就变成「调用方传错了东西」，而真实责任是模型没有认真作答。
     */
    @Test
    void rejectsDuplicateReferenceInsideOneArea() {
        AiRepositoryScoutProposal proposal = new AiRepositoryScoutProposal(List.of(
                new AiRepositoryScoutFocusArea("重复",
                        List.of(ref(CONTROLLER_REF), ref(ENTITY_REF), ref(CONTROLLER_REF))),
                new AiRepositoryScoutFocusArea("另一个", List.of(ref(MAPPER_REF))),
                new AiRepositoryScoutFocusArea("再一个", List.of(ref(SERVICE_IMPL_REF)))));

        AiGatewayException failure = assertThrows(AiGatewayException.class,
                () -> resolver.resolve(proposal, inputs));

        assertEquals(true, failure.getMessage().contains("RF-4"),
                "错误信息应当指出重复的编号: " + failure.getMessage());
    }

    /**
     * 可信类型本身也拒绝区域内重复——最后一道，不依赖上游。
     */
    @Test
    void trustedAreaRejectsDuplicateReferences() {
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryInspectionArea("重复",
                        List.of(entry(CONTROLLER_REF), entry(CONTROLLER_REF))));
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(null, inputs));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(ScoutFixtures.validProposal(), null));
    }

    // ---------------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------------

    private RepositoryInspectionPlan resolve(AiRepositoryScoutFocusArea area) {
        return resolver.resolve(new AiRepositoryScoutProposal(List.of(
                area,
                new AiRepositoryScoutFocusArea("另一个", List.of(ref(CONTROLLER_REF))),
                new AiRepositoryScoutFocusArea("再一个", List.of(ref(ENTITY_REF))))), inputs);
    }

    private static AiRepositoryScoutFocusArea area(String label, RepositoryFileReference reference) {
        return new AiRepositoryScoutFocusArea(label, List.of(reference));
    }

    private static RepositoryFileReference ref(int position) {
        return ScoutFixtures.ref(position);
    }

    private static RepositoryMapEntry entry(int position) {
        return ScoutFixtures.entry(position, CONTROLLER, 1_000);
    }

    private static List<String> pathsOf(RepositoryInspectionPlan plan, int areaIndex) {
        return plan.areas().get(areaIndex).entries().stream()
                .map(RepositoryMapEntry::relativePath)
                .toList();
    }
}
