package com.ayywl.delveforge.domain.asset;

import java.util.Optional;

/**
 * Software Asset Aggregate Root（DOMAIN_MODEL.md §3.2、§11.4）。
 *
 * <p>Software Asset 表示「可以作为新产品起点、能力来源或复用对象的软件资产」，
 * 主要回答「这是哪个可利用的软件资产，以及当前是否允许读取和利用它」（§11.4）。
 * MVP 中它只有一种具体形式：用户指定的本地 Git Repository。
 *
 * <p>本类当前只承载 Aggregate 自身的领域语义：
 *
 * <pre>
 * 创建 Software Asset
 * 表达类型、来源、位置、读取权限、许可证信息与使用授权
 * 校验该资产是否可以进入 Repository Analysis（INV-A01）
 * </pre>
 *
 * <p>不包含：Repository Profile，以及任何代码读取或代码修改。Repository Profile 是引用
 * {@code assetId} 的独立 Aggregate，§11.4 明确 Software Asset 不拥有 Repository Profile。
 *
 * <p>资产保持不可变；用户通过 updateAuthorization 提交隔离候选，授权版本递增。
 * 身份、来源与位置不通过此操作修改。授权 CAS 由 Persistence 负责，许可证文本仅记录声明。
 *
 * <p>usageAuthorization 则必须区分三种状态（见 {@link UsageAuthorization}）：
 * 「已明确不允许」与「尚未确认」是两种不同的领域状态，压成同一个取值会丢失领域信息。
 *
 * <p>本类不判断该资产是否允许被复用或作为 Evolution Base。那属于 AssetUsagePolicy
 * （§12.6、INV-A02～INV-A04）：§12.6 明确
 * 「Readable ≠ Reusable」「Analyzable ≠ Authorized Evolution Base」。
 */
public class SoftwareAsset {

    private final SoftwareAssetId id;
    private final SoftwareAssetType type;
    private final SoftwareAssetSource source;
    private final String location;
    private final boolean readPermissionAllowed;
    private final String licenseInfo;
    private final UsageAuthorization usageAuthorization;

    // 授权并发版本，不是 UserProfile 或 Git revision。
    private final long authorizationRevision;

    /**
     * 登记一个 Software Asset。
     *
     * <p>读取权限与使用授权都必须由调用方显式给出，本方法不提供默认值：
     * §3.2 把两者都定义为「当前」的事实，领域模型没有规定「未说明时默认允许」。
     *
     * @param id                  资产标识，不得为 {@code null}
     * @param type                资产类型，不得为 {@code null}
     * @param source              资产来源，不得为 {@code null}
     * @param location            能够定位该资产的引用，不得为 {@code null} 或空白；
     *                            其具体表现形式由资产类型决定（§3.2），领域模型不规定格式
     * @param readPermissionAllowed 当前是否允许系统读取和分析该资产（§3.2 的 readPermission）
     * @param licenseInfo         已知的软件许可证信息；{@code null} 表示当前未知，
     *                            §3.2 只要求该资产「已知的」许可证信息，不要求它必须已知
     * @param usageAuthorization  当前的使用授权状态（§3.2 的 usageAuthorization），
     *                            不得为 {@code null}；「尚未确认」由
     *                            {@link UsageAuthorization#UNCLEAR} 显式表达，
     *                            不是缺省
     * @throws IllegalArgumentException 任一必填参数缺失或取值不合法
     */
    public static SoftwareAsset create(
            SoftwareAssetId id,
            SoftwareAssetType type,
            SoftwareAssetSource source,
            String location,
            boolean readPermissionAllowed,
            String licenseInfo,
            UsageAuthorization usageAuthorization) {

        return new SoftwareAsset(
                id, type, source, location, readPermissionAllowed, licenseInfo, usageAuthorization);
    }

    /**
     * 按已保存的状态重建一个 Software Asset。
     *
     * <p>本入口用于 Persistence 从存储中恢复已有资产，因此调用点表达的是
     * 「恢复已保存的资产」，而不是「登记一个新的资产」——后者请使用
     * {@link #create}。
     *
     * <p>此兼容入口恢复授权版本 0；持久化读取必须使用带 authorizationRevision 的重载。
     *
     * @throws IllegalArgumentException 任一必填参数缺失或取值不合法
     */
    public static SoftwareAsset reconstitute(
            SoftwareAssetId id,
            SoftwareAssetType type,
            SoftwareAssetSource source,
            String location,
            boolean readPermissionAllowed,
            String licenseInfo,
            UsageAuthorization usageAuthorization) {

        return new SoftwareAsset(
                id, type, source, location, readPermissionAllowed, licenseInfo, usageAuthorization);
    }

    private SoftwareAsset(
            SoftwareAssetId id,
            SoftwareAssetType type,
            SoftwareAssetSource source,
            String location,
            boolean readPermissionAllowed,
            String licenseInfo,
            UsageAuthorization usageAuthorization) {
        this(id, type, source, location, readPermissionAllowed, licenseInfo, usageAuthorization, 0);
    }

    private SoftwareAsset(SoftwareAssetId id, SoftwareAssetType type, SoftwareAssetSource source,
            String location, boolean readPermissionAllowed, String licenseInfo, UsageAuthorization usageAuthorization,
            long authorizationRevision) {
        if (authorizationRevision < 0) {
            throw new IllegalArgumentException("Authorization revision must be nonnegative");
        }

        if (id == null) {
            throw new IllegalArgumentException("Software Asset 必须指定 id");
        }
        if (type == null) {
            throw new IllegalArgumentException("Software Asset 必须指定 type");
        }
        if (source == null) {
            throw new IllegalArgumentException("Software Asset 必须指定 source");
        }
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("Software Asset 的 location 不能为空");
        }
        if (licenseInfo != null && licenseInfo.isBlank()) {
            throw new IllegalArgumentException(
                    "Software Asset 的 licenseInfo 要么给出已知内容，要么留为 null 表示未知");
        }
        if (usageAuthorization == null) {
            throw new IllegalArgumentException(
                    "Software Asset 必须指定 usageAuthorization：尚未确认请显式给出 UNCLEAR");
        }

        this.id = id;
        this.type = type;
        this.source = source;
        this.location = location;
        this.readPermissionAllowed = readPermissionAllowed;
        this.licenseInfo = licenseInfo;
        this.usageAuthorization = usageAuthorization;
        this.authorizationRevision = authorizationRevision;
    }

    /**
     * 校验该资产当前允许被读取，因而可以进入 Repository Analysis
     * （DOMAIN_MODEL.md §8.3 的前置条件「系统必须具备读取该资产所需权限」、
     * §12.6 的 Analysis 判定、INV-A01）。
     *
     * <p>该判定只取决于 {@code readPermission}。使用授权与许可证信息都不参与其中：
     * §12.6 明确「Readable ≠ Reusable」「Analyzable ≠ Authorized Evolution Base」，
     * 一个只允许分析的资产不能因为在别处被授权复用就变得可读，反之亦然。
     *
     * <p>因此通过本校验只说明该资产可以被读取和分析，不表示：
     *
     * <pre>
     * 允许复用它的代码
     * 允许它作为 Evolution Base
     * 允许修改它的任何内容
     * </pre>
     *
     * <p>后面这些判断属于 AssetUsagePolicy（§12.6、INV-A02～INV-A04），不能由分析许可替代。
     *
     * <p>本方法不修改任何状态：它只回答「现在能不能读」，不产生任何授权或记录。
     *
     * @throws SoftwareAssetNotReadableException 该资产当前不允许读取
     */
    public void requireAnalysisAllowed() {
        if (!readPermissionAllowed) {
            throw new SoftwareAssetNotReadableException(
                    "Software Asset 当前不允许读取，不能进入 Repository Analysis: " + id.value());
        }
    }

    public static SoftwareAsset reconstitute(SoftwareAssetId id, SoftwareAssetType type, SoftwareAssetSource source,
            String location, boolean readPermissionAllowed, String licenseInfo, UsageAuthorization usageAuthorization,
            long authorizationRevision) {
        return new SoftwareAsset(id, type, source, location, readPermissionAllowed, licenseInfo,
                usageAuthorization, authorizationRevision);
    }

    /** 返回隔离候选；CAS 失败不会修改已加载的资产，身份与位置保持不变。 */
    public SoftwareAsset updateAuthorization(long expectedRevision, boolean readPermissionAllowed,
            String licenseInfo, UsageAuthorization usageAuthorization) {
        if (expectedRevision != authorizationRevision) {
            throw new AssetAuthorizationConflictException();
        }
        return reconstitute(id, type, source, location, readPermissionAllowed, licenseInfo, usageAuthorization,
                Math.addExact(authorizationRevision, 1));
    }

    public long authorizationRevision() {
        return authorizationRevision;
    }

    public SoftwareAssetId id() {
        return id;
    }

    public SoftwareAssetType type() {
        return type;
    }

    public SoftwareAssetSource source() {
        return source;
    }

    public String location() {
        return location;
    }

    /** 当前是否允许系统读取和分析该资产（§3.2 的 {@code readPermission}）。 */
    public boolean readPermissionAllowed() {
        return readPermissionAllowed;
    }

    /**
     * 已知的软件许可证信息。
     *
     * <p>返回空表示系统当前不知道该资产的许可证信息（§3.2 记录的是「已知的」信息），
     * 不表示该资产没有许可证限制；本类不对许可证内容做任何解释或判断。
     */
    public Optional<String> licenseInfo() {
        return Optional.ofNullable(licenseInfo);
    }

    /**
     * 当前的使用授权状态（§3.2 的 {@code usageAuthorization}）。
     *
     * <p>ALLOWED、DENIED 与 UNCLEAR 是三种不同的领域状态；本类只表达这一事实，
     * 不据此得出任何「允许复用」或「允许演化」的结论。
     */
    public UsageAuthorization usageAuthorization() {
        return usageAuthorization;
    }
}
