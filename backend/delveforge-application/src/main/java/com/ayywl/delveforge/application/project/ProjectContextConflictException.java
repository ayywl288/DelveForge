package com.ayywl.delveforge.application.project;

/** 上下文版本或显式关联已变化；不会以新导航覆盖既有项目。 */
public final class ProjectContextConflictException extends RuntimeException {
    public ProjectContextConflictException() {
        super("Project context version or association conflict");
    }
}
