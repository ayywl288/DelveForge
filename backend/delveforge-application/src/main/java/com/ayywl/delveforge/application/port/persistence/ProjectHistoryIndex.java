package com.ayywl.delveforge.application.port.persistence;

import java.util.List;

/** 分页读取明确身份；禁止按位置、名称或时间推测资源归属。 */
public interface ProjectHistoryIndex {
    List<String> historyKeys(int offset, int limit);

    default List<String> historyKeys(int offset, int limit, boolean deleted) {
        return deleted ? List.of() : historyKeys(offset, limit);
    }

    List<String> resourceIds(ResourceKind kind, int offset, int limit);

    enum ResourceKind { USER_PROFILE, SOFTWARE_ASSET, REPOSITORY_PROFILE }
}
