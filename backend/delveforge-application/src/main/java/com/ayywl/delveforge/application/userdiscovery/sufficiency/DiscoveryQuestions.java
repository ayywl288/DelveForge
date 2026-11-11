package com.ayywl.delveforge.application.userdiscovery.sufficiency;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 问题形状与近似重复检查；问题历史来自服务端，不能作为用户事实保存。 */
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
        if (previousQuestions.stream().anyMatch(previous -> equivalent(previous, question))) {
            throw new AiGatewayException("探索评估重复了已提出的问题");
        }
    }

    /** 仅识别独立的不确定答复；复杂语境仍交给模型理解，不据此制造个人事实或拒答。 */
    public static boolean isUncertainAnswer(String input) {
        String value = input == null ? "" : input.strip().replaceAll("[\\s，。！？,.!?]", "");
        return Set.of("不知道", "我不知道", "不清楚", "我不清楚", "不确定", "暂时不确定", "暂时不知道",
                "还不知道", "还不确定", "还没想好", "没想好").contains(value);
    }

    /** 有限的近似重复检查；语义重复仍由模型结合已讨论话题判断。 */
    public static boolean equivalent(String first, String second) {
        String left = normalize(first);
        String right = normalize(second);
        if (left.equals(right)) {
            return true;
        }
        if (left.length() < 6 || right.length() < 6) {
            return false;
        }
        Set<String> a = bigrams(left);
        Set<String> b = bigrams(right);
        long shared = a.stream().filter(b::contains).count();
        return shared * 1.0 / (a.size() + b.size() - shared) >= 0.72;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{Z}\\s]", "").replaceAll("[你您呢呀吗]", "");
    }

    private static Set<String> bigrams(String text) {
        Set<String> values = new HashSet<>();
        for (int i = 0; i + 1 < text.length(); i++) {
            values.add(text.substring(i, i + 2));
        }
        return values;
    }

}
