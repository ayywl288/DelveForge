package com.ayywl.delveforge.application.userdiscovery.sufficiency;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;

import java.util.List;

/** 本页下一问的有界上下文，不是持久化对话历史，也不能作为用户事实保存。 */
public final class DiscoveryQuestions {

    private DiscoveryQuestions() {
    }

    public static List<String> validate(List<String> previousQuestions) {
        if (previousQuestions == null) {
            return List.of();
        }
        if (previousQuestions.size() > 8 || previousQuestions.stream().anyMatch(
                question -> question == null || question.isBlank() || question.length() > 512)) {
            throw new IllegalArgumentException("previousQuestions 最多八项，每项为不超过 512 字符的非空问题");
        }
        return previousQuestions.stream().map(String::strip).toList();
    }

    public static void requireNewQuestion(String question, List<String> previousQuestions) {
        if (question == null || question.isBlank() || question.length() > 512) {
            throw new AiGatewayException("探索评估未返回有界的下一问");
        }
        if (previousQuestions.stream().anyMatch(previous -> previous.strip().equals(question.strip()))) {
            throw new AiGatewayException("探索评估重复了已提出的问题");
        }
    }
}
