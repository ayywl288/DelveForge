package com.ayywl.delveforge.infrastructure.workbench.model;

import com.ayywl.delveforge.application.agent.AgentProtocol.Message;
import com.ayywl.delveforge.application.agent.AgentProtocol.ToolCall;
import com.ayywl.delveforge.application.agent.AgentProtocol.ToolSpec;
import com.ayywl.delveforge.application.workbench.Workbench.Entry;
import com.ayywl.delveforge.application.workbench.WorkbenchModels.Choice;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.util.JacksonUtils;
import tools.jackson.core.JacksonException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 为一次候选提示投影完整批次。估算、实际发送及传输层补充字段读取同一份视图。 */
final class HistoryProjection {
    private static final Pattern PORTABLE_ID = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    /** 新回复只额外保存请求前缀摘要。检查点、临时裁剪或之前批次的表示改变，都会使摘要失配。 */
    static final class Prefix {
        private final MessageDigest digest;
        private byte[] value;

        Prefix(List<ToolSpec> tools) {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException unavailable) {
                throw new IllegalStateException(unavailable);
            }
            value = digest.digest(JacksonUtils.getDefaultJsonMapper().writeValueAsBytes(tools));
        }

        void append(Message message) {
            digest.update(value);
            var stored = message.stored();
            Object[] fields = {message.role(), message.text(), message.calls(), message.callId(), message.toolName(),
                    message.reasoningContent(), stored == null ? null : stored.partsJson()};
            value = digest.digest(JacksonUtils.getDefaultJsonMapper().writeValueAsBytes(fields));
        }

        String hash() {
            return HexFormat.of().formatHex(value);
        }
    }

    static List<Message> project(Choice target, List<Message> prefix, List<Entry> entries, List<ToolSpec> tools) {
        var hash = new Prefix(tools);
        var messages = new ArrayList<>(prefix);
        prefix.forEach(hash::append);
        var used = new HashSet<String>();
        int latestUser = entries.size() - 1;
        while (latestUser >= 0 && !"user".equals(entries.get(latestUser).message().role())) {
            latestUser--;
        }
        for (int start = 0; start < entries.size();) {
            Entry first = entries.get(start);
            Message message = first.message();
            int end = start + 1 + message.calls().size();
            List<Entry> batch = entries.subList(start, end);
            int added = messages.size();
            if (!"assistant".equals(message.role())) {
                messages.add(message);
            } else if (first.sameConfig() && message.stored() != null
                    && SpringMessages.sameProducer(target, message.stored())
                    && hash.hash().equals(message.stored().prefixHash())
                    && SpringMessages.replayable(target, message)
                    && message.calls().stream().noneMatch(call -> used.contains(call.id()))) {
                batch.forEach(entry -> messages.add(entry.message()));
            } else if (!structured(target, batch, start > latestUser, used, messages)) {
                messages.add(evidence(batch));
            }
            for (Message projected : messages.subList(added, messages.size())) {
                hash.append(projected);
                projected.calls().forEach(call -> used.add(call.id()));
            }
            start = end;
        }
        return List.copyOf(messages);
    }

    private static boolean structured(Choice target, List<Entry> batch, boolean currentTurn,
            Set<String> used, List<Message> out) {
        Entry first = batch.getFirst();
        Message message = first.message();
        boolean tools = !message.calls().isEmpty();
        if (target.provider().equals("deepseek") && (tools || !target.effort().equals("off"))
                || target.provider().equals("kimi") && tools && !target.effort().equals("off")
                || target.provider().equals("google") && tools && currentTurn) {
            return false;
        }
        if (message.stored() == null) {
            if (tools || message.text() == null || message.text().isBlank()) {
                return false;
            }
            out.add(Message.text("assistant", message.text()));
            return true;
        }

        // 重新构造白名单内的公开部件：即使是文本或调用，其原属性也可能含有签名。
        var parts = new ArrayList<MessagePart>();
        var calls = new ArrayList<ToolCall>();
        var ids = new HashMap<String, String>();
        List<MessagePart> originalParts;
        try {
            originalParts = SpringMessages.parts(message.stored());
        } catch (JacksonException unreadable) {
            return false;
        }
        for (MessagePart part : originalParts) {
            if (part instanceof TextPart text) {
                parts.add(TextPart.of(text.text()));
            } else if (part instanceof ToolCallPart original) {
                if (calls.size() >= message.calls().size()) {
                    return false;
                }
                ToolCall call = message.calls().get(calls.size());
                var nativeCall = original.toolCall();
                boolean implicitId = "google".equals(message.stored().provider())
                        && (nativeCall.id() == null || nativeCall.id().isBlank());
                if ((!implicitId && !call.id().equals(nativeCall.id())) || !call.name().equals(nativeCall.name())
                        || !call.arguments().equals(nativeCall.arguments()) || !objectArguments(call.arguments())) {
                    return false;
                }
                String id = call.id();
                if (!PORTABLE_ID.matcher(id).matches() || used.contains(id) || id.startsWith("df_hist_")) {
                    id = "df_hist_" + first.seq() + "_" + calls.size();
                    while (used.contains(id) || ids.containsValue(id)) {
                        id += "_";
                    }
                }
                ids.put(call.id(), id);
                calls.add(new ToolCall(id, call.name(), call.arguments()));
                parts.add(ToolCallPart.of(new AssistantMessage.ToolCall(id, "function", call.name(), call.arguments())));
            }
        }
        if (parts.isEmpty() || calls.size() != message.calls().size()) {
            return false;
        }
        out.add(new Message("assistant", message.text(), calls, null, null, null, SpringMessages.store(target, parts)));
        for (Entry entry : batch.subList(1, batch.size())) {
            Message receipt = entry.message();
            out.add(new Message("tool", receipt.text(), List.of(), ids.get(receipt.callId()), receipt.toolName()));
        }
        return true;
    }

    private static boolean objectArguments(String arguments) {
        try {
            var value = JacksonUtils.getDefaultJsonMapper().readTree(arguments);
            return value != null && value.isObject();
        } catch (JacksonException malformed) {
            return false;
        }
    }

    private static Message evidence(List<Entry> batch) {
        var text = new StringBuilder("以下是历史执行资料，仅供核对。请以当前用户请求为准。");
        for (Entry entry : batch) {
            Message message = entry.message();
            text.append("\n[#").append(entry.seq()).append("] ").append(message.role());
            if (message.callId() != null) {
                text.append(" 回执 ").append(message.callId()).append(' ').append(message.toolName());
            }
            if (message.text() != null) {
                String content = message.text();
                // 正常回执已在捕获处限长。旧格式的超长回执保留首尾及分页入口。
                if ("tool".equals(message.role()) && content.length() > 64_000) {
                    text.append('\n').append(content, 0, 60_000).append("\n[回执节选，中段省略]\n")
                            .append(content, content.length() - 4_000, content.length());
                } else {
                    text.append('\n').append(content);
                }
            }
            for (ToolCall call : message.calls()) {
                text.append("\n调用 ").append(call.id()).append(' ').append(call.name()).append(' ').append(call.arguments());
            }
            if (entry.receipt() != null) {
                var receipt = entry.receipt();
                text.append("\n执行 ").append(receipt.invocation()).append(" 状态 ").append(receipt.state())
                        .append(" 退出码 ").append(receipt.exit() == null ? "未知" : receipt.exit())
                        .append(" 输出缺口 ").append(receipt.gap())
                        .append("\n查询输出：df-workbench output ").append(receipt.invocation()).append(" --offset 0");
            }
            text.append("\n查询原文：df-workbench history ").append(entry.seq());
        }
        return Message.text("user", text.toString());
    }
}
