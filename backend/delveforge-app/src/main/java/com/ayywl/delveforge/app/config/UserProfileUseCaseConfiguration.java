package com.ayywl.delveforge.app.config;

import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.application.userdiscovery.CreateUserProfileUseCase;
import com.ayywl.delveforge.application.userdiscovery.GetUserProfileUseCase;
import com.ayywl.delveforge.application.userdiscovery.UpdateUserProfileUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}
