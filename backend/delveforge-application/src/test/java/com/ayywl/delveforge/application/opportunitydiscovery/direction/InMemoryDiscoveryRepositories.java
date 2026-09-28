package com.ayywl.delveforge.application.opportunitydiscovery.direction;

import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.application.port.persistence.RepositoryProfileRepository;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 本包测试用的 Persistence 替身。
 *
 * <p>Application 测试在 Port 边界使用替身，从而不依赖 Spring、SQLite、MyBatis-Plus 或任何
 * Infrastructure 实现（AGENTS.md §10.2）。
 *
 * <p>它们同时记录调用次数与写入顺序——「保存只发生一次、且在全部校验之后」这类编排性质
 * 只能通过观察调用来验证。
 */
final class InMemoryDiscoveryRepositories {

    private InMemoryDiscoveryRepositories() {
    }

    /** {@link RepositoryProfileRepository} 的替身：只提供按标识读取。 */
    static final class RepositoryProfileStub implements RepositoryProfileRepository {

        private final Map<RepositoryProfileId, RepositoryProfile> profiles = new HashMap<>();

        void put(RepositoryProfile profile) {
            profiles.put(profile.id(), profile);
        }

        @Override
        public void save(RepositoryProfile profile) {
            put(profile);
        }

        @Override
        public Optional<RepositoryProfile> findById(RepositoryProfileId id) {
            return Optional.ofNullable(profiles.get(id));
        }
    }

    /** {@link ProductDirectionRepository} 的替身：记录整批写入。 */
    static final class ProductDirectionRecorder implements ProductDirectionRepository {

        private final Map<ProductDirectionId, ProductDirection> stored = new HashMap<>();

        private final List<List<ProductDirection>> batches = new ArrayList<>();

        private int batchCalls;

        private RuntimeException batchFailure;

        /** 让后续的整批写入抛出该异常，用来验证调用方不会吞掉持久化失败。 */
        void failBatchesWith(RuntimeException exception) {
            this.batchFailure = exception;
        }

        @Override
        public void save(ProductDirection productDirection) {
            stored.put(productDirection.id(), productDirection);
        }

        @Override
        public void saveAll(List<ProductDirection> productDirections) {
            batchCalls++;
            if (batchFailure != null) {
                throw batchFailure;
            }
            batches.add(List.copyOf(productDirections));
            for (ProductDirection direction : productDirections) {
                save(direction);
            }
        }

        @Override
        public Optional<ProductDirection> findById(ProductDirectionId id) {
            return Optional.ofNullable(stored.get(id));
        }

        /** 成功写入过几批。 */
        int batchCount() {
            return batches.size();
        }

        /**
         * 整批写入被调用过几次。
         *
         * <p>与 {@link #batchCount()} 分开：写入失败时调用发生过、但没有成功的批次，
         * 「有没有重试」只能看调用次数。
         */
        int batchCalls() {
            return batchCalls;
        }

        /** 至今写入过的全部分方向。 */
        List<ProductDirection> saved() {
            List<ProductDirection> all = new ArrayList<>();
            for (List<ProductDirection> batch : batches) {
                all.addAll(batch);
            }
            return List.copyOf(all);
        }

        /** 写入过的方向标识是否互不相同。 */
        boolean savedIdentitiesAreDistinct() {
            Set<ProductDirectionId> seen = new HashSet<>();
            for (ProductDirection direction : saved()) {
                if (!seen.add(direction.id())) {
                    return false;
                }
            }
            return true;
        }
    }
}
