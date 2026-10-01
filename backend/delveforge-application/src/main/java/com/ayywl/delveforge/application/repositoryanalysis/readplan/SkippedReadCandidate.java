package com.ayywl.delveforge.application.repositoryanalysis.readplan;

import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;

/**
 * 一个被跳过、不进入可读集合的候选，以及跳过它的原因。
 *
 * <p>它是一份**诊断**，不是结果的一部分：被跳过的文件不会被读取，也不构成任何领域事实。
 * 存在的意义是当「某个文件明明在候选里，为什么分析没看到它」被问起时，能给出一个确定的答案，
 * 而不是让人去猜预算还是大小把它挡掉了。
 *
 * <p>{@code entry} 是已经解析过的描述符，因此诊断里带着真实路径——但它是**说明**，
 * 不是读取目标：可读集合只有 {@link RepositoryReadPlan} 里那些条目。
 *
 * <p>本类型不持久化。
 *
 * @param entry  被跳过的候选，不得为 {@code null}
 * @param reason 跳过它的原因，不得为 {@code null}
 */
public record SkippedReadCandidate(RepositoryMapEntry entry, RepositoryReadSkipReason reason) {

    public SkippedReadCandidate {
        if (entry == null) {
            throw new IllegalArgumentException("SkippedReadCandidate 必须指定 entry");
        }
        if (reason == null) {
            throw new IllegalArgumentException("SkippedReadCandidate 必须指定 reason");
        }
    }
}
