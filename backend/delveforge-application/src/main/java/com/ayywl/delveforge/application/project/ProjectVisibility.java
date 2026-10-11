package com.ayywl.delveforge.application.project;

import java.time.Instant;

/** 工作台可见性及并发版本，不是领域状态或执行权限。 */
public record ProjectVisibility(String key, long version, Instant deletedAt) {
}
