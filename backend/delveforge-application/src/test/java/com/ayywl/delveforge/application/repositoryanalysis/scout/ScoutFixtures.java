package com.ayywl.delveforge.application.repositoryanalysis.scout;

import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryFileReference;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMap;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryPathClassifier;
import java.util.List;

/**
 * Scout 测试共用的 Maps 与路径。
 *
 * <p>一张 Map 里同时放齐三组候选，这样每个测试都能直接断言「哪一组被排除了」，
 * 而不是靠单独构造一张只有源码的 Map 来回避问题。
 *
 * <pre>
 * RF-1  pom.xml                                    BUILD_METADATA   FOUNDATION
 * RF-2  …/HmDianPingApplication.java               CONFIG_BOOTSTRAP FOUNDATION
 * RF-3  …/config/MvcConfig.java                    CONFIG_BOOTSTRAP FOUNDATION
 * RF-4  …/controller/ShopController.java           API_ENTRY        SCOUT_SOURCE
 * RF-5  …/entity/Shop.java                         DOMAIN_MODEL     SCOUT_SOURCE
 * RF-6  …/mapper/ShopMapper.java                   PERSISTENCE      SCOUT_SOURCE
 * RF-7  …/service/impl/ShopServiceImpl.java        APPLICATION_SERVICE SCOUT_SOURCE
 * RF-8  src/test/java/com/hmdp/ShopTest.java       TEST_CODE        NONE
 * </pre>
 */
final class ScoutFixtures {

    static final String REVISION = "18e6b63cf218eca717cd00ecf4e3e0a12dccf5b4";

    static final String POM = "pom.xml";
    static final String APPLICATION = "src/main/java/com/hmdp/HmDianPingApplication.java";
    static final String CONFIG = "src/main/java/com/hmdp/config/MvcConfig.java";
    static final String CONTROLLER = "src/main/java/com/hmdp/controller/ShopController.java";
    static final String ENTITY = "src/main/java/com/hmdp/entity/Shop.java";
    static final String MAPPER = "src/main/java/com/hmdp/mapper/ShopMapper.java";
    static final String SERVICE_IMPL = "src/main/java/com/hmdp/service/impl/ShopServiceImpl.java";
    static final String TEST = "src/test/java/com/hmdp/ShopTest.java";

    /** 各路径在这张 Map 中的编号（编号由位置决定，见 RepositoryMap）。 */
    static final int POM_REF = 1;
    static final int APPLICATION_REF = 2;
    static final int CONFIG_REF = 3;
    static final int CONTROLLER_REF = 4;
    static final int ENTITY_REF = 5;
    static final int MAPPER_REF = 6;
    static final int SERVICE_IMPL_REF = 7;
    static final int TEST_REF = 8;

    private ScoutFixtures() {
    }

    static RepositoryMap map() {
        return RepositoryMap.of(REVISION, List.of(
                entry(POM_REF, POM, 4_096),
                entry(APPLICATION_REF, APPLICATION, 900),
                entry(CONFIG_REF, CONFIG, 1_500),
                entry(CONTROLLER_REF, CONTROLLER, 3_000),
                entry(ENTITY_REF, ENTITY, 700),
                entry(MAPPER_REF, MAPPER, 400),
                entry(SERVICE_IMPL_REF, SERVICE_IMPL, 12_000),
                entry(TEST_REF, TEST, 2_200)));
    }

    static RepositoryScoutInputs inputs() {
        return RepositoryScoutInputs.of(map());
    }

    /** 只有 Foundation 与 NONE 的 Map：拿不出任何源码候选。 */
    static RepositoryMap mapWithoutScoutSource() {
        return RepositoryMap.of(REVISION, List.of(
                entry(1, POM, 4_096),
                entry(2, TEST, 2_200)));
    }

    static RepositoryMapEntry entry(int position, String relativePath, long sizeInBytes) {
        RepositoryPathClassifier.Classification classification =
                RepositoryPathClassifier.classify(relativePath);
        return new RepositoryMapEntry(
                RepositoryFileReference.of(position),
                relativePath,
                sizeInBytes,
                classification.language(),
                classification.materialKind(),
                classification.roleHints());
    }

    static RepositoryFileReference ref(int position) {
        return RepositoryFileReference.of(position);
    }

    /** 一个结构合法的响应：三个区域，引用都落在源码候选内。 */
    static String validResponse() {
        return """
                {
                  "focusAreas": [
                    { "label": "对外接口", "fileRefs": ["RF-4"] },
                    { "label": "领域模型", "fileRefs": ["RF-5", "RF-6"] },
                    { "label": "服务实现", "fileRefs": ["RF-7"] }
                  ]
                }
                """;
    }

    /** 解析后的提议，等价于 {@link #validResponse()}。 */
    static AiRepositoryScoutProposal validProposal() {
        return new AiRepositoryScoutProposal(List.of(
                new AiRepositoryScoutFocusArea("对外接口", List.of(ref(CONTROLLER_REF))),
                new AiRepositoryScoutFocusArea("领域模型",
                        List.of(ref(ENTITY_REF), ref(MAPPER_REF))),
                new AiRepositoryScoutFocusArea("服务实现", List.of(ref(SERVICE_IMPL_REF)))));
    }
}
