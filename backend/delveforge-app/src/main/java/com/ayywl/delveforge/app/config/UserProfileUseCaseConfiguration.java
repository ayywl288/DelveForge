package com.ayywl.delveforge.app.config;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.ayywl.delveforge.application.userdiscovery.exploration.ExploreUserProfileUseCase;
import com.ayywl.delveforge.application.userdiscovery.profile.CreateUserProfileUseCase;
import com.ayywl.delveforge.application.userdiscovery.profile.GetUserProfileUseCase;
import com.ayywl.delveforge.application.userdiscovery.profile.UpdateUserProfileUseCase;
import com.ayywl.delveforge.application.userdiscovery.review.ConfirmUserProfileUseCase;
import com.ayywl.delveforge.application.userdiscovery.review.ContinueDiscoveryUseCase;
import com.ayywl.delveforge.application.userdiscovery.review.ReopenDiscoveryUseCase;
import com.ayywl.delveforge.application.userdiscovery.sufficiency.AssessProfileSufficiencyUseCase;

/**
 * User Profile Use Case 的依赖装配。
 *
 * <p>Use Case 由 Application 拥有，但不依赖 Spring——Application 模块不引入任何
 * Spring 依赖，因此它们不能通过 {@code @Component} 被发现。装配放在 Composition Root
 * 完成（RULE-ARCH-005），Application 侧保持框架无关。
 *
 * <p>{@link UserProfileRepository} 由 Infrastructure 的 Persistence Adapter 提供。
 */
@Configuration(proxyBeanMethods = false)
public class UserProfileUseCaseConfiguration {

    @Bean
    public CreateUserProfileUseCase createUserProfileUseCase(UserProfileRepository userProfileRepository) {
        return new CreateUserProfileUseCase(userProfileRepository);
    }

    @Bean
    public GetUserProfileUseCase getUserProfileUseCase(UserProfileRepository userProfileRepository) {
        return new GetUserProfileUseCase(userProfileRepository);
    }

    @Bean
    public UpdateUserProfileUseCase updateUserProfileUseCase(UserProfileRepository userProfileRepository) {
        return new UpdateUserProfileUseCase(userProfileRepository);
    }

    /**
     * {@link AiGateway} 由 Infrastructure 的 Provider Adapter 提供；
     * {@link ObjectMapper} 由 Spring Boot 的 Jackson 自动装配提供，用于构造请求上下文与
     * 解析模型输出。
     */
    @Bean
    public ExploreUserProfileUseCase exploreUserProfileUseCase(
            UserProfileRepository userProfileRepository,
            AiGateway aiGateway,
            ObjectMapper objectMapper) {
        return new ExploreUserProfileUseCase(userProfileRepository, aiGateway, objectMapper);
    }

    @Bean
    public AssessProfileSufficiencyUseCase assessProfileSufficiencyUseCase(
            UserProfileRepository userProfileRepository,
            AiGateway aiGateway,
            ObjectMapper objectMapper) {
        return new AssessProfileSufficiencyUseCase(userProfileRepository, aiGateway, objectMapper);
    }

    @Bean
    public ConfirmUserProfileUseCase confirmUserProfileUseCase(UserProfileRepository userProfileRepository) {
        return new ConfirmUserProfileUseCase(userProfileRepository);
    }

    @Bean
    public ContinueDiscoveryUseCase continueDiscoveryUseCase(UserProfileRepository userProfileRepository) {
        return new ContinueDiscoveryUseCase(userProfileRepository);
    }

    @Bean
    public ReopenDiscoveryUseCase reopenDiscoveryUseCase(UserProfileRepository userProfileRepository) {
        return new ReopenDiscoveryUseCase(userProfileRepository);
    }
}
