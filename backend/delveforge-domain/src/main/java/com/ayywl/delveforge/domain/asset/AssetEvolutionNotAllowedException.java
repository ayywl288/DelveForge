package com.ayywl.delveforge.domain.asset;

/** Evolution Base 授权拒绝的具体原因；不包含用户许可证文本。 */
public class AssetEvolutionNotAllowedException extends RuntimeException {
    public enum Reason {
        ASSET_TYPE_UNSUPPORTED, READ_PERMISSION_REQUIRED, USAGE_AUTHORIZATION_REQUIRED, LICENSE_INFORMATION_REQUIRED
    }

    private final Reason reason;

    public AssetEvolutionNotAllowedException(Reason reason) {
        super("Asset evolution denied: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
