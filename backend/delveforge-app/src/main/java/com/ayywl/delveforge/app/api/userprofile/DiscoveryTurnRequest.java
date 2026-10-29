package com.ayywl.delveforge.app.api.userprofile;

import com.ayywl.delveforge.application.userdiscovery.shared.DiscoveryContext;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/** 语境仅用于对话理解，不作为用户事实或授权；旧请求可以省略。 */
public record DiscoveryTurnRequest(String input, List<String> previousQuestions,
        List<DiscoveryContext.Message> recentConversation, String requestId,
        @JsonDeserialize(using = UserProfileRevisionDeserializer.class)
        Integer revision, String projectId) {

    public DiscoveryTurnRequest(String input, List<String> previousQuestions,
            List<DiscoveryContext.Message> recentConversation) {
        this(input, previousQuestions, recentConversation, null, null, null);
    }

    public DiscoveryTurnRequest(String input, List<String> previousQuestions) {
        this(input, previousQuestions, null);
    }
}
