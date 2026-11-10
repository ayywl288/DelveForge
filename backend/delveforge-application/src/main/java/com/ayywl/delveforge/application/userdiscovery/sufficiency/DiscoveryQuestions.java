package com.ayywl.delveforge.application.userdiscovery.sufficiency;

import com.ayywl.delveforge.application.port.ai.AiGatewayException;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 问题形状与近似重复检查；问题历史来自服务端，不能作为用户事实保存。 */
public final class DiscoveryQuestions {

    private static final Pattern QUOTED = Pattern.compile("“[^”]*”|「[^」]*」|\"[^\"]*\"");
    private static final Pattern SENTENCE = Pattern.compile("[^。！？.!?；;\\n]+[。！？.!?；;]?");
    private static final Pattern INVITATION = Pattern.compile(
            "请(?:你|您)?(?:说说|想想|选择|举例|描述)|(?:你可以|也可以|不妨)(?:说说|想想|选|举|描述)"
                    + "|(?i:\\b(?:could you|would you|tell me|please describe)\\b)");

    private static final Pattern REASON_QUESTION = Pattern.compile("为什么|为何|什么原因|(?i:\\bwhy\\b)");

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

    /**
     * 比较 ASK 正文中对用户提出的问题与独立追问。逐个分句检查，避免示例或较长的铺垫稀释重复处。
     * 普通陈述与引用保留原样；局部词组重合只是有界筛查，不能判定所有语义等价。
     */
    static void requireDistinctFollowUp(String reply, String question) {
        String prose = QUOTED.matcher(reply).replaceAll(" ");
        if (prose.contains(question)) {
            throw new AiGatewayException("正文与 nextQuestion 重复提问，请将问题只放在 nextQuestion");
        }
        var sentences = SENTENCE.matcher(prose);
        while (sentences.find()) {
            for (String asked : sentences.group().split("[，,]")) {
                if (!asked.endsWith("?") && !asked.endsWith("？") && !INVITATION.matcher(asked).find()) {
                    continue;
                }
                for (String planned : question.split("[。！？!?；;，,]")) {
                    if (overlappingIntent(asked, planned)) {
                        throw new AiGatewayException("正文与 nextQuestion 重复提问。正文只说明背景或举例，追问只放在 nextQuestion");
                    }
                }
            }
        }
    }

    private static boolean overlappingIntent(String asked, String planned) {
        String left = normalize(asked);
        String right = normalize(planned);
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        if (left.equals(right)) {
            return true;
        }
        // 回复可自问自答说明「为什么」；下一问询问选择/做法时，复用主题词本身不构成重复。
        if (REASON_QUESTION.matcher(asked).find() != REASON_QUESTION.matcher(planned).find()) {
            return false;
        }
        Set<String> a = bigrams(left);
        Set<String> b = bigrams(right);
        long shared = a.stream().filter(b::contains).count();
        return shared >= 5 && shared >= 0.40 * Math.min(a.size(), b.size());
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
