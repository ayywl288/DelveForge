package com.ayywl.delveforge.app.config;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.application.userdiscovery.exploration.ExploreUserProfileUseCase;
import com.ayywl.delveforge.application.userdiscovery.exploration.ProfileExtraction;
import com.ayywl.delveforge.application.userdiscovery.profile.CreateUserProfileUseCase;
import com.ayywl.delveforge.application.userdiscovery.profile.GetUserProfileUseCase;
import com.ayywl.delveforge.application.userdiscovery.profile.UpdateUserProfileUseCase;
import com.ayywl.delveforge.application.userdiscovery.review.ConfirmUserProfileUseCase;
import com.ayywl.delveforge.application.userdiscovery.review.ContinueDiscoveryUseCase;
import com.ayywl.delveforge.application.userdiscovery.review.ReopenDiscoveryUseCase;
import com.ayywl.delveforge.application.userdiscovery.sufficiency.AssessProfileSufficiencyUseCase;
import com.ayywl.delveforge.application.userdiscovery.sufficiency.ProfileSufficiencyEvaluator;
import com.ayywl.delveforge.application.userdiscovery.workflow.RunUserDiscoveryTurnUseCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * User Profile Use Case 的依赖装配。
 *
 * <p>Use Case 由 Application 拥有，但不依赖 Spring——Application 模块不引入任何
 * Spring 依赖，因此它们不能通过 {@code @Component} 被发现。装配放在 Composition Root
 * 完成（RULE-ARCH-005），Application 侧保持框架无关。
 *
 * <p>{@link UserProfileRepository} 由 Infrastructure 的 Persistence Adapter 提供；
 * {@link AiGateway} 由 Infrastructure 的 Provider Adapter 提供；
 * {@link ObjectMapper} 由 Spring Boot 的 Jackson 自动装配提供。
 *
 * <p>{@link ProfileExtraction} 与 {@link ProfileSufficiencyEvaluator} 是 Application
 * 内部的协作单元，被「单步」Use Case 与「整轮」Use Case 共用，因此也在这里装配——
 * 它们同样不依赖 Spring。
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

    @Bean
    public ProfileExtraction profileExtraction(AiGateway aiGateway, ObjectMapper objectMapper) {
        return new ProfileExtraction(aiGateway, objectMapper);
    }

    @Bean
    public ProfileSufficiencyEvaluator profileSufficiencyEvaluator(AiGateway aiGateway,
                                                                   ObjectMapper objectMapper) {
        return new ProfileSufficiencyEvaluator(aiGateway, objectMapper);
    }

    @Bean
    public ExploreUserProfileUseCase exploreUserProfileUseCase(
            UserProfileRepository userProfileRepository,
            ProfileExtraction profileExtraction) {
        return new ExploreUserProfileUseCase(userProfileRepository, profileExtraction);
    }

    @Bean
    public AssessProfileSufficiencyUseCase assessProfileSufficiencyUseCase(
            UserProfileRepository userProfileRepository,
            ProfileSufficiencyEvaluator profileSufficiencyEvaluator) {
        return new AssessProfileSufficiencyUseCase(userProfileRepository, profileSufficiencyEvaluator);
    }

    @Bean
    public RunUserDiscoveryTurnUseCase runUserDiscoveryTurnUseCase(
            UserProfileRepository userProfileRepository,
            ProfileExtraction profileExtraction,
            ProfileSufficiencyEvaluator profileSufficiencyEvaluator) {
        return new RunUserDiscoveryTurnUseCase(
                userProfileRepository, profileExtraction, profileSufficiencyEvaluator);
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
