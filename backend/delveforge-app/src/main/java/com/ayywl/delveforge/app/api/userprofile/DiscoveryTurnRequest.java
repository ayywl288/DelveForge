package com.ayywl.delveforge.app.api.userprofile;

import com.ayywl.delveforge.application.userdiscovery.shared.DiscoveryContext;

import java.util.List;

/** 语境仅用于对话理解，不作为用户事实或授权；旧请求可以省略。 */
public record DiscoveryTurnRequest(String input, List<String> previousQuestions,
        List<DiscoveryContext.Message> recentConversation) {

    public DiscoveryTurnRequest(String input, List<String> previousQuestions) {
        this(input, previousQuestions, null);
    }
}
