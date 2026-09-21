package com.ayywl.delveforge.application.repositoryanalysis.workflow;

import com.ayywl.delveforge.application.port.persistence.RepositoryProfileAlreadyExistsException;
import com.ayywl.delveforge.application.port.persistence.RepositoryProfileRepository;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@link RepositoryProfileRepository} 的测试替身。
 *
 * <p>保留真实 Port 的写入语义：同一个标识只写入一次，重复写入抛
 * {@link RepositoryProfileAlreadyExistsException}，因此替身不会比真实 Adapter 宽松。
 */
final class InMemoryRepositoryProfileRepository implements RepositoryProfileRepository {

    private final Map<RepositoryProfileId, RepositoryProfile> profiles = new LinkedHashMap<>();

    private int saveCount;

    @Override
    public void save(RepositoryProfile profile) {
        if (profiles.containsKey(profile.id())) {
            throw new RepositoryProfileAlreadyExistsException(profile.id());
        }
        profiles.put(profile.id(), profile);
        saveCount++;
    }

    @Override
    public Optional<RepositoryProfile> findById(RepositoryProfileId id) {
        return Optional.ofNullable(profiles.get(id));
    }

    int saveCount() {
        return saveCount;
    }

    /** 当前已保存的快照数量。 */
    int size() {
        return profiles.size();
    }
}
