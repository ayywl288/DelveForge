package com.ayywl.delveforge.application.workbench;

import com.ayywl.delveforge.application.agent.AgentContextBudget;
import com.ayywl.delveforge.application.agent.AgentProtocol.Message;
import com.ayywl.delveforge.application.agent.AgentTokenCounter;
import com.ayywl.delveforge.application.workbench.Workbench.Entry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.IntPredicate;

/** 决策提示的保留预算。按实际模板计算边际用量，最终请求仍须连同系统指令和工具整体计数。 */
final class ConversationRetention {
    private static final int USER_PAGE = 32;
    private static final long MAX_ORIGINAL_TOKENS = 4096;
    private static final Message EMPTY_SYSTEM = Message.text("system", "");

    record Originals(List<Message> messages, long newestOmitted) {}

    private final Workbench store;
    private final AgentTokenCounter counter;
    private final long base;
    final long target;
    final long originalBudget;
    final long suffixBudget;

    ConversationRetention(Workbench store, AgentTokenCounter counter, AgentContextBudget budget) {
        this.store = store;
        this.counter = counter;
        this.base = count(List.of(EMPTY_SYSTEM));
        this.target = Math.min(budget.targetTokens(), budget.triggerTokens() * 5 / 6);
        this.originalBudget = Math.min(MAX_ORIGINAL_TOKENS, target / 4);
        this.suffixBudget = target / 2;
    }

    long count(List<Message> view) {
        return counter.countPromptTokens(view, ConversationContext.TOOLS);
    }

    Originals originals(String conversation, long through) {
        var selected = new ArrayList<Message>();
        while (through > 0) {
            var page = store.coveredUsers(conversation, through, USER_PAGE);
            if (page.isEmpty()) {
                break;
            }
            var recent = page.stream().map(Entry::message).toList();
            int keep = recent.size();
            if (rawCost(recent, keep, selected) > originalBudget) {
                keep = first(0, keep, n -> rawCost(recent, n, selected) > originalBudget) - 1;
            }
            selected.addAll(0, recent.subList(0, keep).reversed());
            if (keep < recent.size()) {
                return new Originals(List.copyOf(selected), page.get(keep).seq());
            }
            if (page.size() < USER_PAGE) {
                break;
            }
            through = page.getLast().seq() - 1;
        }
        return new Originals(List.copyOf(selected), 0);
    }

    private long rawCost(List<Message> page, int n, List<Message> newer) {
        var messages = new ArrayList<Message>();
        messages.add(EMPTY_SYSTEM);
        messages.addAll(page.subList(0, n).reversed());
        messages.addAll(newer);
        return count(messages) - base;
    }

    long suffixCost(List<Entry> suffix) {
        return count(view(EMPTY_SYSTEM, List.of(), suffix)) - base;
    }

    static List<Message> view(Message system, List<Message> originals, List<Entry> suffix) {
        var messages = new ArrayList<Message>(1 + originals.size() + suffix.size());
        messages.add(system);
        messages.addAll(originals);
        suffix.forEach(entry -> messages.add(entry.message()));
        return List.copyOf(messages);
    }

    /** 返回可切分的位置（含零和末尾）。缺少回执、孤立回执或批次 ID 不匹配时拒绝重放。 */
    static List<Integer> boundaries(List<Entry> entries) {
        var cuts = new ArrayList<Integer>();
        cuts.add(0);
        for (int i = 0; i < entries.size();) {
            Message message = entries.get(i++).message();
            if (!"user".equals(message.role()) && !"assistant".equals(message.role())) {
                return List.of();
            }
            if (!message.calls().isEmpty()) {
                var outstanding = new HashSet<String>();
                for (var call : message.calls()) {
                    if (!"assistant".equals(message.role()) || call.id() == null || call.id().isBlank()
                            || !outstanding.add(call.id())) {
                        return List.of();
                    }
                }
                while (!outstanding.isEmpty() && i < entries.size()) {
                    Message result = entries.get(i++).message();
                    if (!"tool".equals(result.role()) || !outstanding.remove(result.callId())) {
                        return List.of();
                    }
                }
                if (!outstanding.isEmpty()) {
                    return List.of();
                }
            }
            cuts.add(i);
        }
        return cuts;
    }

    /** 尚未得到任何助手回应的末尾用户消息整体保留，即使超过近期历史的软预算。 */
    static int firstUnanswered(List<Entry> records) {
        int end = records.size();
        while (end > 0 && "user".equals(records.get(end - 1).message().role())) {
            end--;
        }
        return end;
    }

    /** 优先在完整组边界上二分，调用方会复核选定请求的实际 token 数。 */
    static int first(int lo, int hi, IntPredicate condition) {
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (condition.test(mid)) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }
}
