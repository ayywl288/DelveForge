package com.ayywl.delveforge.application.repositoryanalysis.map;

/**
 * 一个文件按扩展名识别出的语言。
 *
 * <h2>这是启发式，不是事实</h2>
 *
 * <p>它只由扩展名决定，不看内容、不做语法判断。因此：
 *
 * <pre>
 * UNKNOWN 不是「这个文件没有语言」   而是「它的扩展名不在下面的映射表里」
 * 取值也可能不准                     .h 同时是 C 与 C++ 的头文件；
 *                                    一个 .json 可能其实是某个工具的自有格式
 * </pre>
 *
 * <p>它存在的原因是让 Repository Map 的描述符能回答「这是不是源码、哪种源码」，
 * 供后续阶段取舍。它不构成对文件内容的任何断言，也不能作为领域事实。
 *
 * <p>当前映射表覆盖：M1 已识别的源码扩展名、Java 工程常见的配置与标记语言、
 * 以及真实验证仓库（黑马点评）中出现的全部类型。没有出现真实需要时不要继续扩充——
 * 未识别一律是 {@link #UNKNOWN}，这比猜一个更诚实。
 */
public enum RepositoryLanguage {

    JAVA, KOTLIN, SCALA, GROOVY,

    PYTHON, JAVASCRIPT, TYPESCRIPT, VUE,

    GO, RUST, C, CPP, C_SHARP, RUBY, PHP, SWIFT, DART, LUA,

    /** Shell 与 Windows 批处理脚本。 */
    SHELL,

    /** SQL：既可能是 schema，也可能是查询脚本，由 material kind 区分。 */
    SQL,

    XML, YAML, JSON, TOML, PROPERTIES, MARKDOWN, TEXT,

    /** 扩展名不在映射表中的文件。 */
    UNKNOWN
}
