package com.ayywl.delveforge.app.api.userprofile;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/** 只读下一问请求；问题上下文有界，只用于避免重复，不作为用户输入提取。 */
public record DiscoveryQuestionRequest(
        @JsonDeserialize(using = UserProfileRevisionDeserializer.class) Integer revision,
        List<String> previousQuestions) {
}
