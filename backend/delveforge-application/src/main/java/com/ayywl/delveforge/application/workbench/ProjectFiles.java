package com.ayywl.delveforge.application.workbench;

/** 导入现有目录或创建空项目，返回已发布的宿主工作副本路径。 */
public interface ProjectFiles {
    String prepare(String id, String source);

    void open(String id);
}
