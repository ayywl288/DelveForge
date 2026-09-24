package com.ayywl.delveforge.application.opportunitydiscovery.direction;

import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStatus;
import java.util.List;

/**
 * Product Direction Discovery 测试共用的输入。
 *
 * <p>两个测试类（解析器与提取编排）必须使用<b>同一份</b>输入才能核对引用编号，
 * 因此这里集中构造，而不是各写一份会悄悄分叉的副本。
 *
 * <p>引用的分配结果：
 *
 * <pre>
 * U-E1  U-E2        用户侧
 * R1-E1 R1-E2       第 1 个 Repository Profile
 * R2-E1             第 2 个 Repository Profile
 * </pre>
 */
final class DirectionDiscoveryFixtures {

    static final UserProfileId USER_PROFILE_ID = new UserProfileId("user-profile-1");

    static final RepositoryProfileId ACCOUNTING_PROFILE_ID =
            new RepositoryProfileId("repository-profile-1");

    static final RepositoryProfileId REPORTING_PROFILE_ID =
            new RepositoryProfileId("repository-profile-2");

    static final SoftwareAssetId ACCOUNTING_ASSET_ID = new SoftwareAssetId("software-asset-1");

    static final SoftwareAssetId REPORTING_ASSET_ID = new SoftwareAssetId("software-asset-2");

    static final Evidence USER_EVIDENCE_1 = new Evidence(
            EvidenceSourceType.USER_INPUT, "用户输入：我一直在用自己写的记账工具",
            "用户长期自己维护记账工具", 0.8, true);

    static final Evidence USER_EVIDENCE_2 = new Evidence(
            EvidenceSourceType.USER_INPUT, "用户输入：但导出报表很麻烦",
            "用户对报表导出的不满", null, false);

    static final Evidence ACCOUNTING_EVIDENCE_1 = new Evidence(
            EvidenceSourceType.REPOSITORY, "src/main/accounting", "已有记账模块", null, false);

    static final Evidence ACCOUNTING_EVIDENCE_2 = new Evidence(
            EvidenceSourceType.REPOSITORY, "src/main/report", "已有报表渲染模块", null, false);

    static final Evidence REPORTING_EVIDENCE = new Evidence(
            EvidenceSourceType.REPOSITORY, "src/main/chart", "已有图表组件", null, false);

    private DirectionDiscoveryFixtures() {
    }

    static UserProfile confirmedUserProfile() {
        return UserProfile.reconstitute(
                USER_PROFILE_ID,
                UserProfileStatus.CONFIRMED,
                3,
                List.of("个人记账", "数据可视化"),
                List.of("长期自己维护小工具"),
                List.of("现有工具的报表导出很麻烦"),
                List.of("Java", "Spring Boot"),
                List.of("做一个自己每天都会用的工具"),
                List.of("只能在业余时间推进"),
                List.of(USER_EVIDENCE_1, USER_EVIDENCE_2));
    }

    static RepositoryProfile accountingProfile() {
        return RepositoryProfile.create(
                ACCOUNTING_PROFILE_ID,
                ACCOUNTING_ASSET_ID,
                "abc123",
                "个人记账工具",
                List.of("Java 21", "Spring Boot"),
                List.of("accounting", "report"),
                List.of("记账", "报表渲染"),
                List.of("报表导出"),
                List.of("没有自动化测试"),
                List.of("模块耦合"),
                List.of(ACCOUNTING_EVIDENCE_1, ACCOUNTING_EVIDENCE_2));
    }

    static RepositoryProfile reportingProfile() {
        return RepositoryProfile.create(
                REPORTING_PROFILE_ID,
                REPORTING_ASSET_ID,
                "def456",
                "图表组件库",
                List.of("TypeScript"),
                List.of("chart"),
                List.of("图表渲染"),
                List.of("折线图组件"),
                List.of("没有导出能力"),
                List.of(),
                List.of(REPORTING_EVIDENCE));
    }

    static DirectionDiscoveryInputs inputs() {
        return DirectionDiscoveryInputs.of(
                confirmedUserProfile(), List.of(accountingProfile(), reportingProfile()));
    }
}
