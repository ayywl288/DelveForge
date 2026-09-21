package com.ayywl.delveforge.domain.repositoryprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepositoryProfileTest {

    private static final RepositoryProfileId PROFILE_ID = new RepositoryProfileId("profile-1");

    private static final SoftwareAssetId ASSET_ID = new SoftwareAssetId("software-asset-1");

    private static final String REVISION = "abc123";

    private static final String PURPOSE = "个人记账工具";

    private static final Evidence EVIDENCE = new Evidence(
            EvidenceSourceType.REPOSITORY, "pom.xml", "项目使用 Spring Boot", 0.9, true);

    @Test
    void createsProfileWithIdentityAssetRevisionAndAnalysisContent() {
        RepositoryProfile profile = createProfile(
                PROFILE_ID, ASSET_ID, REVISION, PURPOSE);

        assertEquals(PROFILE_ID, profile.id());
        assertEquals(ASSET_ID, profile.assetId());
        assertEquals(REVISION, profile.analyzedRevision());
        assertEquals(PURPOSE, profile.purpose());
        assertEquals(List.of("Java 21", "Spring Boot"), profile.techStack());
        assertEquals(List.of("accounting", "reporting"), profile.modules());
        assertEquals(List.of("记账"), profile.capabilities());
        assertEquals(List.of("报表导出"), profile.reusableAssets());
        assertEquals(List.of("无自动化测试"), profile.limitations());
        assertEquals(List.of("模块耦合"), profile.risks());
        assertEquals(List.of(EVIDENCE), profile.evidence());
    }

    /**
     * 分析结论确实可以没有内容（一个很小的仓库可能没有可复用资产），
     * 但 purpose 必须给出：没有用途描述的快照无法回答它描述的是什么。
     */
    @Test
    void allowsEmptyAnalysisContentButRequiresPurposeAndRevision() {
        RepositoryProfile profile = RepositoryProfile.create(
                PROFILE_ID, ASSET_ID, REVISION, PURPOSE,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        assertEquals(List.of(), profile.techStack());
        assertEquals(List.of(), profile.capabilities());
        assertEquals(List.of(), profile.evidence());
    }

    /**
     * 同一个 Software Asset 在不同 revision 上分析，得到的是两个各自独立的历史结果
     * （DOMAIN_MODEL.md §10.4）：新快照的产生不修改旧快照，旧快照仍然声称自己
     * 描述的是它当时的那个 revision。
     */
    @Test
    void keepsOneProfilePerAnalyzedRevisionOfTheSameAsset() {
        RepositoryProfile first = createProfile(
                PROFILE_ID, ASSET_ID, "abc123", "旧版本：个人记账工具");

        RepositoryProfile second = RepositoryProfile.create(
                new RepositoryProfileId("profile-2"),
                ASSET_ID,
                "def456",
                "新版本：个人记账工具 + 报表",
                List.of("Java 21"),
                List.of("accounting", "reporting"),
                List.of("记账", "报表"),
                List.of("报表导出"),
                List.of(),
                List.of("模块耦合"),
                List.of(EVIDENCE));

        assertEquals(ASSET_ID, first.assetId());
        assertEquals(ASSET_ID, second.assetId());
        assertNotEquals(first.id(), second.id());
        assertNotEquals(first.analyzedRevision(), second.analyzedRevision());

        assertEquals("abc123", first.analyzedRevision());
        assertEquals("旧版本：个人记账工具", first.purpose());
        assertEquals(List.of("记账"), first.capabilities(),
                "新快照不得改变旧快照的内容");
        assertEquals(List.of("记账", "报表"), second.capabilities());
    }

    /**
     * Snapshot 语义的另一种表述：Profile 不提供任何修改入口，因此源 Repository 后续
     * 变化（或再次分析）都不会让它「隐式更新」——它始终报告自己创建时的那次分析。
     */
    @Test
    void keepsItsOwnAnalysisWhenTheAssetMovesOn() {
        RepositoryProfile profile = createProfile(PROFILE_ID, ASSET_ID, "abc123", PURPOSE);

        // 源 Repository 之后产生了新的 revision，同一资产重新分析得到另一个 Profile
        createProfile(new RepositoryProfileId("profile-2"), ASSET_ID, "def456", "后来重新分析");

        assertEquals("abc123", profile.analyzedRevision(), "旧 Profile 的 revision 不得变化");
        assertEquals(PURPOSE, profile.purpose());
        assertEquals(List.of("记账"), profile.capabilities());
        assertEquals(List.of(EVIDENCE), profile.evidence());
    }

    /**
     * Evidence 属于产生它的那个 Profile：它是该次分析结论的依据，
     * 不跨 Profile 共享，也不在 Aggregate 之间自动传播。
     */
    @Test
    void keepsEvidenceOfItsOwnAnalysisOnly() {
        Evidence otherEvidence = new Evidence(
                EvidenceSourceType.REPOSITORY, "src/main/App.java", "入口类只有几十行", null, false);

        RepositoryProfile first = createProfile(PROFILE_ID, ASSET_ID, "abc123", PURPOSE);
        RepositoryProfile second = RepositoryProfile.create(
                new RepositoryProfileId("profile-2"), ASSET_ID, "def456", "后来重新分析",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(otherEvidence));

        assertEquals(List.of(EVIDENCE), first.evidence());
        assertEquals(List.of(otherEvidence), second.evidence());
    }

    @Test
    void rejectsMissingIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> createProfile(null, ASSET_ID, REVISION, PURPOSE));
    }

    @Test
    void rejectsMissingAsset() {
        assertThrows(IllegalArgumentException.class,
                () -> createProfile(PROFILE_ID, null, REVISION, PURPOSE));
    }

    /**
     * INV-D03：Repository Profile 必须绑定确定的 Software Asset 与 analyzedRevision。
     */
    @Test
    void rejectsMissingAnalyzedRevision() {
        assertThrows(IllegalArgumentException.class,
                () -> createProfile(PROFILE_ID, ASSET_ID, null, PURPOSE));
        assertThrows(IllegalArgumentException.class,
                () -> createProfile(PROFILE_ID, ASSET_ID, "  ", PURPOSE));
    }

    @Test
    void rejectsMissingPurpose() {
        assertThrows(IllegalArgumentException.class,
                () -> createProfile(PROFILE_ID, ASSET_ID, REVISION, null));
        assertThrows(IllegalArgumentException.class,
                () -> createProfile(PROFILE_ID, ASSET_ID, REVISION, "  "));
    }

    @Test
    void rejectsMissingAnalysisSection() {
        assertThrows(IllegalArgumentException.class, () -> RepositoryProfile.create(
                PROFILE_ID, ASSET_ID, REVISION, PURPOSE,
                null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void rejectsBlankEntryInAnalysisSection() {
        assertThrows(IllegalArgumentException.class, () -> RepositoryProfile.create(
                PROFILE_ID, ASSET_ID, REVISION, PURPOSE,
                List.of("Java 21", " "), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of()));
    }

    @Test
    void rejectsMissingOrNullEvidence() {
        assertThrows(IllegalArgumentException.class, () -> RepositoryProfile.create(
                PROFILE_ID, ASSET_ID, REVISION, PURPOSE,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null));

        List<Evidence> withNull = new ArrayList<>();
        withNull.add(EVIDENCE);
        withNull.add(null);

        assertThrows(IllegalArgumentException.class, () -> RepositoryProfile.create(
                PROFILE_ID, ASSET_ID, REVISION, PURPOSE,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), withNull));
    }

    @Test
    void doesNotRetainCallerOwnedContentList() {
        List<String> callerOwned = new ArrayList<>(List.of("记账"));
        RepositoryProfile profile = RepositoryProfile.create(
                PROFILE_ID, ASSET_ID, REVISION, PURPOSE,
                List.of(), List.of(), callerOwned, List.of(), List.of(), List.of(),
                List.of(EVIDENCE));

        callerOwned.add("调用方后续追加");

        assertEquals(List.of("记账"), profile.capabilities(),
                "创建后 Profile 的内容不得再被调用方改动");
    }

    @Test
    void exposesContentAsUnmodifiableList() {
        RepositoryProfile profile = createProfile(PROFILE_ID, ASSET_ID, REVISION, PURPOSE);

        List<String> capabilities = profile.capabilities();

        assertThrows(UnsupportedOperationException.class, () -> capabilities.add("追加"));
        assertThrows(UnsupportedOperationException.class, () -> profile.evidence().add(EVIDENCE));
    }

    @Test
    void rejectsBlankProfileId() {
        assertThrows(IllegalArgumentException.class, () -> new RepositoryProfileId("  "));
        assertThrows(IllegalArgumentException.class, () -> new RepositoryProfileId(null));
    }

    private static RepositoryProfile createProfile(
            RepositoryProfileId id,
            SoftwareAssetId assetId,
            String analyzedRevision,
            String purpose) {
        return RepositoryProfile.create(
                id,
                assetId,
                analyzedRevision,
                purpose,
                List.of("Java 21", "Spring Boot"),
                List.of("accounting", "reporting"),
                List.of("记账"),
                List.of("报表导出"),
                List.of("无自动化测试"),
                List.of("模块耦合"),
                List.of(EVIDENCE));
    }
}
