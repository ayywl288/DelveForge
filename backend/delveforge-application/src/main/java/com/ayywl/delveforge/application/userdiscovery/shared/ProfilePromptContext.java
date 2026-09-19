package com.ayywl.delveforge.application.userdiscovery.shared;

import com.ayywl.delveforge.domain.user.UserProfile;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把当前 User Profile 的内容组织成 AI 请求上下文。
 *
 * <p>给模型的是结构化内容本身，而不是序列化后的领域对象：模型看到的是六个内容区的值，
 * 不依赖领域类的字段形状。
 */
public final class ProfilePromptContext {

    private ProfilePromptContext() {
    }

    public static Map<String, Object> currentContent(UserProfile profile) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("interests", profile.interests());
        content.put("behaviors", profile.behaviors());
        content.put("painPoints", profile.painPoints());
        content.put("technicalCapabilities", profile.technicalCapabilities());
        content.put("projectGoals", profile.projectGoals());
        content.put("constraints", profile.constraints());
        return content;
    }
}
