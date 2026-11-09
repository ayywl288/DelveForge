package com.ayywl.delveforge.app.api.userprofile;

import com.ayywl.delveforge.application.userdiscovery.sufficiency.DialogueAction;

import java.util.List;

/** 一次只读评估与下一问，绑定请求版本；不表示创建了任何新用户事实。 */
public record DiscoveryQuestionResponse(
        int revision,
        boolean sufficient,
        List<String> missingAreas,
        String nextQuestion,
        String assistantReply,
        DialogueAction nextAction) {
}
