package com.ayywl.delveforge.domain.asset;

/** 授权版本已变化，必须重新读取元数据后由用户决定。 */
public final class AssetAuthorizationConflictException extends RuntimeException {
    public AssetAuthorizationConflictException() {
        super("SoftwareAsset authorization changed");
    }
}
