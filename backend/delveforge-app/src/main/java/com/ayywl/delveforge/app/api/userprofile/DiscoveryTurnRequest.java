package com.ayywl.delveforge.app.api.userprofile;

import java.util.List;

/** 本轮真实输入与可选的本页已提问题；旧版仅含 input 的请求保持兼容。 */
public record DiscoveryTurnRequest(String input, List<String> previousQuestions) {
}
