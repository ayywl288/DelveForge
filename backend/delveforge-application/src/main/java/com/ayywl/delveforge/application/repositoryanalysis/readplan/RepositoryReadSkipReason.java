package com.ayywl.delveforge.application.repositoryanalysis.readplan;

/**
 * 一个候选文件被跳过、不进入可读集合的原因。
 *
 * <p>这些是**规划诊断**，不是失败：被跳过的候选不影响同一通道里其它候选被选中，
 * 规划照常继续。记录它们只是为了回答「我明明看到这个文件，为什么分析里没有它」。
 *
 * <p>它们不被持久化，也不表示任何领域事实。
 */
public enum RepositoryReadSkipReason {

    /**
     * 已被选中（或已被 Scout 指出），但超过单文件上限。
     *
     * <p>不做截断、不做分块：半份内容会让模型基于缺失的部分下结论，而系统并不知道缺了什么。
     */
    SELECTED_BUT_TOO_LARGE,

    /**
     * 加入它会超出本通道剩余的总量预算。
     *
     * <p>跳过之后仍然继续考察同一通道里更小的候选——先遇到的那个放不下，不代表后面的也放不下。
     */
    EXCEEDS_REMAINING_TOTAL_BYTES
}
