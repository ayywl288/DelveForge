package com.ayywl.delveforge.application.port.persistence;

/** 同一本地仓库已经登记；不覆盖既有身份或授权记录。 */
public final class SoftwareAssetAlreadyRegisteredException extends RuntimeException {
    public SoftwareAssetAlreadyRegisteredException() {
        super("Local repository is already registered");
    }
}
