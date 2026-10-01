package com.ayywl.delveforge.application.opportunitydiscovery.direction;

import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.application.port.persistence.RepositoryProfileRepository;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.direction.ProductDirectionStatus;
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

    /** {@link ProductDirectionRepository} 的替身：记录单条与整批写入，并能模拟读到的损坏状态。 */
    static final class ProductDirectionRecorder implements ProductDirectionRepository {

        private final Map<ProductDirectionId, ProductDirection> stored = new HashMap<>();

        private final List<List<ProductDirection>> batches = new ArrayList<>();

        private final List<ProductDirection> singleSaves = new ArrayList<>();

        private int batchCalls;

        private int currentSelectedCalls;

        private RuntimeException batchFailure;

        private RuntimeException singleSaveFailure;

        private RuntimeException currentSelectedFailure;

        /** 让后续的整批写入抛出该异常，用来验证调用方不会吞掉持久化失败。 */
        void failBatchesWith(RuntimeException exception) {
            this.batchFailure = exception;
        }

        /** 让后续的单条保存抛出该异常。 */
        void failSavesWith(RuntimeException exception) {
            this.singleSaveFailure = exception;
        }

        /**
         * 让查询当前 SELECTED 方向时抛出该异常。
         *
         * <p>真实实现在读到多于一条 SELECTED 时会失败（存储与领域模型不一致）；替身用它
         * 复现同一个形状——调用方应当原样向上传递，并且不留下任何写入。
         */
        void failCurrentSelectedWith(RuntimeException exception) {
            this.currentSelectedFailure = exception;
        }

        /** 直接放进存储，不经过任何保存入口：用来构造测试需要的初始状态。 */
        void seed(ProductDirection productDirection) {
            store(productDirection);
        }

        @Override
        public void save(ProductDirection productDirection) {
            singleSaves.add(productDirection);
            if (singleSaveFailure != null) {
                throw singleSaveFailure;
            }
            store(productDirection);
        }

        @Override
        public void saveAll(List<ProductDirection> productDirections) {
            batchCalls++;
            if (batchFailure != null) {
                throw batchFailure;
            }
            batches.add(List.copyOf(productDirections));
            for (ProductDirection direction : productDirections) {
                store(direction);
            }
        }

        @Override
        public Optional<ProductDirection> findById(ProductDirectionId id) {
            return stored(id);
        }

        /**
         * 当前 SELECTED 的方向。
         *
         * <p>替身同样遵守 Port 的约定：多于一条时失败，而不是挑一条返回。
         */
        @Override
        public Optional<ProductDirection> findCurrentSelected() {
            currentSelectedCalls++;
            if (currentSelectedFailure != null) {
                throw currentSelectedFailure;
            }
            List<ProductDirectionId> selected = stored.entrySet().stream()
                    .filter(entry -> entry.getValue().status() == ProductDirectionStatus.SELECTED)
                    .map(Map.Entry::getKey)
                    .toList();
            if (selected.size() > 1) {
                throw new IllegalStateException(
                        "存储中存在多于一个当前 SELECTED 的 Product Direction");
            }
            return selected.isEmpty() ? Optional.empty() : stored(selected.get(0));
        }

        /**
         * 写入一份**快照**，而不是存调用方那个对象本身。
         *
         * <p>{@code ProductDirection} 是可变的 Aggregate：如果这里保存引用，调用方在一次
         * 失败的编排里于内存中改过的状态会直接「看起来已经落库」，而真实存储不会有这种
         * 效果——那些改动根本没有提交。保存快照让替身与真实存储的语义一致：
         * 只有真正走完写入入口的改动才可见，「失败不留下持久化副作用」因此是可验证的。
         */
        private void store(ProductDirection productDirection) {
            stored.put(productDirection.id(), copyOf(productDirection));
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

        /** 单条保存被调用过几次。 */
        int singleSaveCalls() {
            return singleSaves.size();
        }

        /** 成功写入过的批次，按写入顺序。 */
        List<List<ProductDirection>> batches() {
            return List.copyOf(batches);
        }

        /** 查询当前 SELECTED 方向被调用过几次。 */
        int currentSelectedCalls() {
            return currentSelectedCalls;
        }

        /**
         * 存储里当前的内容，按标识。
         *
         * <p>返回的是**独立的快照**，不是内部持有的那个对象：真实存储读出来的一定是新的
         * 对象，调用方随后怎么改它都不会影响库里已有的内容。如果这里直接交出内部对象，
         * 一次在内存里改了状态、但最终写入失败的编排就会看起来「已经落库」——
         * 那正是这些测试要排除的情形。
         */
        Optional<ProductDirection> stored(ProductDirectionId id) {
            ProductDirection direction = stored.get(id);
            return direction == null ? Optional.empty() : Optional.of(copyOf(direction));
        }

        /** 存储里处于某个状态的方向数量。 */
        long storedCountWithStatus(ProductDirectionStatus status) {
            return stored.values().stream().filter(d -> d.status() == status).count();
        }

        /** 按已保存的领域事实重建一份独立的快照，见 {@link #store}。 */
        private static ProductDirection copyOf(ProductDirection productDirection) {
            return ProductDirection.reconstitute(
                    productDirection.id(),
                    productDirection.userProfileId(),
                    productDirection.userProfileRevision(),
                    productDirection.repositoryProfileIds(),
                    productDirection.title(),
                    productDirection.problem(),
                    productDirection.targetProduct(),
                    productDirection.userFit(),
                    productDirection.candidateAssetIds(),
                    productDirection.differentiation(),
                    productDirection.technicalValue(),
                    productDirection.estimatedComplexity(),
                    productDirection.risks(),
                    productDirection.evidenceSupport(),
                    productDirection.status());
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
