package com.ayywl.delveforge.application.opportunitydiscovery.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.EvidenceReference;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import com.ayywl.delveforge.domain.user.UserProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 Product Direction Discovery 的可信输入，以及「模型可以引用什么」的界定。
 *
 * <p>引用编号、可标识资产的集合与输入的校验都在这里固定下来：它们是模型的可见契约，
 * 解析回来的引用能否通过校验完全取决于它们。
 */
class DirectionDiscoveryInputsTest {

    @Test
    void assignsUserEvidenceReferencesInOrder() {
        DirectionDiscoveryInputs inputs = DirectionDiscoveryFixtures.inputs();

        List<DirectionDiscoveryInputs.ReferencedEvidence> userEvidence =
                inputs.userProfileSnapshot().evidence();

        assertEquals(2, userEvidence.size());
        assertEquals(new EvidenceReference("U-E1"), userEvidence.get(0).reference());
        assertEquals(new EvidenceReference("U-E2"), userEvidence.get(1).reference());
        assertEquals(
                DirectionDiscoveryFixtures.USER_EVIDENCE_1, userEvidence.get(0).evidence());
        assertEquals(
                DirectionDiscoveryFixtures.USER_EVIDENCE_2, userEvidence.get(1).evidence());
    }

    /**
     * Repository 侧的引用带上 Profile 的序号，模型据此把依据与它来自哪个 Profile 对上。
     */
    @Test
    void assignsRepositoryEvidenceReferencesPerProfile() {
        DirectionDiscoveryInputs inputs = DirectionDiscoveryFixtures.inputs();

        assertEquals(
                List.of(new EvidenceReference("R1-E1"), new EvidenceReference("R1-E2")),
                inputs.evidenceOf(0).stream()
                        .map(DirectionDiscoveryInputs.ReferencedEvidence::reference)
                        .toList());
        assertEquals(
                List.of(new EvidenceReference("R2-E1")),
                inputs.evidenceOf(1).stream()
                        .map(DirectionDiscoveryInputs.ReferencedEvidence::reference)
                        .toList());
    }

    @Test
    void acceptsEveryReferenceItHandedOut() {
        DirectionDiscoveryInputs inputs = DirectionDiscoveryFixtures.inputs();

        assertTrue(inputs.containsEvidence(new EvidenceReference("U-E1")));
        assertTrue(inputs.containsEvidence(new EvidenceReference("U-E2")));
        assertTrue(inputs.containsEvidence(new EvidenceReference("R1-E1")));
        assertTrue(inputs.containsEvidence(new EvidenceReference("R2-E1")));
    }

    @Test
    void rejectsReferencesItDidNotHandOut() {
        DirectionDiscoveryInputs inputs = DirectionDiscoveryFixtures.inputs();

        assertFalse(inputs.containsEvidence(new EvidenceReference("U-E3")), "用户侧只有两条");
        assertFalse(inputs.containsEvidence(new EvidenceReference("R1-E3")), "第 1 个 Profile 只有两条");
        assertFalse(inputs.containsEvidence(new EvidenceReference("R2-E2")), "第 2 个 Profile 只有一条");
        assertFalse(inputs.containsEvidence(new EvidenceReference("R3-E1")), "只有两个 Profile");
        assertFalse(inputs.containsEvidence(new EvidenceReference("u-e1")), "引用是精确匹配");
        assertFalse(inputs.containsEvidence(null));
    }

    /**
     * 资产白名单不可被外部改动。
     *
     * <p>它是「模型可以标识哪些资产」的判据：只要能被加进一个新条目，
     * 解析器就会接受一个本次输入、Prompt 都没有提供过的资产。
     */
    @Test
    void doesNotExposeAMutableAssetWhitelist() {
        DirectionDiscoveryInputs inputs = DirectionDiscoveryFixtures.inputs();

        assertThrows(UnsupportedOperationException.class,
                () -> inputs.assetIds().add("not-provided"));

        assertFalse(inputs.containsAsset(new SoftwareAssetId("not-provided")),
                "白名单不得被扩大");
        assertEquals(2, inputs.assetIds().size(), "白名单不得被缩小");
    }

    /**
     * 用户侧输入在构造时固定。
     *
     * <p>{@code UserProfile} 是可变的 Aggregate，探索的下一轮会继续改它。如果输入持有的是
     * 它的引用，就会「Prompt 按新内容推荐、引用目录还是旧的」——本次推荐的 userFit 依据
     * 与它实际依据的用户内容对不上。
     */
    @Test
    void fixesTheUserProfileContentAtConstruction() {
        UserProfile profile = DirectionDiscoveryFixtures.confirmedUserProfile();
        DirectionDiscoveryInputs inputs = DirectionDiscoveryInputs.of(
                profile, List.of(DirectionDiscoveryFixtures.accountingProfile()));

        profile.reopenDiscovery();
        profile.updateInterests(List.of("被替换后的兴趣"));
        profile.updatePainPoints(List.of("被追加的痛点"));

        DirectionDiscoveryInputs.UserProfileSnapshot snapshot = inputs.userProfileSnapshot();
        assertEquals(List.of("个人记账", "数据可视化"), snapshot.interests(), "内容必须停在构造时");
        assertEquals(List.of("现有工具的报表导出很麻烦"), snapshot.painPoints());
    }

    /**
     * Evidence 目录与内容快照必须来自同一时点：后续追加 Evidence 不得只让目录变一半。
     */
    @Test
    void fixesTheEvidenceDirectoryAtConstruction() {
        UserProfile profile = DirectionDiscoveryFixtures.confirmedUserProfile();
        DirectionDiscoveryInputs inputs = DirectionDiscoveryInputs.of(
                profile, List.of(DirectionDiscoveryFixtures.accountingProfile()));

        profile.reopenDiscovery();
        profile.recordEvidence(new Evidence(
                EvidenceSourceType.USER_INPUT, "用户输入：后来又补了一句", "构造之后才出现的依据",
                null, false));

        assertEquals(2, inputs.userProfileSnapshot().evidence().size(), "目录停在三处，不是四处");
        assertFalse(inputs.containsEvidence(new EvidenceReference("U-E3")),
                "构造之后产生的依据不属于本次输入");
        assertEquals(
                DirectionDiscoveryFixtures.USER_EVIDENCE_2,
                inputs.userProfileSnapshot().evidence().get(1).evidence());
    }

    /** 快照同时固定 id 与 revision：后续记录到 Product Direction 上的必须是本次依据的那一版。 */
    @Test
    void fixesTheUserProfileRevisionAtConstruction() {
        UserProfile profile = DirectionDiscoveryFixtures.confirmedUserProfile();
        DirectionDiscoveryInputs inputs = DirectionDiscoveryInputs.of(
                profile, List.of(DirectionDiscoveryFixtures.accountingProfile()));

        profile.reopenDiscovery();
        profile.updateInterests(List.of("改过的兴趣"));

        assertEquals(DirectionDiscoveryFixtures.USER_PROFILE_ID,
                inputs.userProfileSnapshot().userProfileId());
        assertEquals(3, inputs.userProfileSnapshot().userProfileRevision(),
                "快照的 revision 必须停在构造时");
        assertEquals(4, profile.revision(), "原 Profile 确实已经推进到了新版本");
    }

    /** 快照的集合同样不可修改。 */
    @Test
    void doesNotExposeMutableSnapshotCollections() {
        DirectionDiscoveryInputs.UserProfileSnapshot snapshot =
                DirectionDiscoveryFixtures.inputs().userProfileSnapshot();

        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.interests().add("追加"));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.evidence().add(null));
    }

    /** 可以标识的资产来自这些 Repository Profile，按身份去重。 */
    @Test
    void offersTheAssetsOfTheGivenRepositoryProfiles() {
        DirectionDiscoveryInputs inputs = DirectionDiscoveryFixtures.inputs();

        assertTrue(inputs.containsAsset(new SoftwareAssetId("software-asset-1")));
        assertTrue(inputs.containsAsset(new SoftwareAssetId("software-asset-2")));
        assertFalse(inputs.containsAsset(new SoftwareAssetId("software-asset-999")));
        assertFalse(inputs.containsAsset(null));
    }

    /**
     * 同一个 Software Asset 在不同 revision 上被分析过多次时，模型仍然只看到一个可选的资产：
     * 它们是跨 Aggregate 的身份引用，不是两次分析结果。
     */
    @Test
    void offersEachAssetOnceEvenWhenAnalyzedMoreThanOnce() {
        RepositoryProfile atOldRevision = DirectionDiscoveryFixtures.accountingProfile();
        RepositoryProfile atNewRevision = RepositoryProfile.create(
                new com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId("profile-2"),
                DirectionDiscoveryFixtures.ACCOUNTING_ASSET_ID,
                "def456",
                "个人记账工具 + 报表",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        DirectionDiscoveryInputs inputs = DirectionDiscoveryInputs.of(
                DirectionDiscoveryFixtures.confirmedUserProfile(),
                List.of(atOldRevision, atNewRevision));

        assertEquals(1, inputs.assetIds().size());
        assertEquals(List.of("software-asset-1"), List.copyOf(inputs.assetIds()));
    }

    /**
     * INV-D05 要求每个方向至少能追溯到一个 Repository Profile；没有任何 Profile 时
     * 不可能产出合法方向，因此这里直接拒绝而不是让模型凭空作答。
     */
    @Test
    void rejectsInputsWithoutAnyRepositoryProfile() {
        UserProfile profile = DirectionDiscoveryFixtures.confirmedUserProfile();

        assertThrows(IllegalArgumentException.class,
                () -> DirectionDiscoveryInputs.of(profile, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> DirectionDiscoveryInputs.of(profile, null));
        assertThrows(IllegalArgumentException.class,
                () -> DirectionDiscoveryInputs.of(null, List.of(
                        DirectionDiscoveryFixtures.accountingProfile())));
    }

    @Test
    void rejectsRepositoryProfileListContainingNull() {
        List<RepositoryProfile> withNull = new java.util.ArrayList<>();
        withNull.add(DirectionDiscoveryFixtures.accountingProfile());
        withNull.add(null);

        assertThrows(IllegalArgumentException.class,
                () -> DirectionDiscoveryInputs.of(
                        DirectionDiscoveryFixtures.confirmedUserProfile(), withNull));
    }
}
