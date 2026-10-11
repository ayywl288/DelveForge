package com.ayywl.delveforge.application.port.workspace;

/**
 * 只读比较本地仓库位置，不读取源码、许可证或 Git，也不产生使用授权。
 * 新输入必须为绝对路径；旧记录仅用于重复登记比较。
 */
public interface LocalRepositoryIdentity {
    String key(String location, boolean existingRecord);
}
