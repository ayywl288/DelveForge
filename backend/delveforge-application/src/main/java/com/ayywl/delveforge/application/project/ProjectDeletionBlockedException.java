package com.ayywl.delveforge.application.project;

/** 存在已授权、执行中、未验证或无法核对的业务记录时，不能隐藏项目。 */
public final class ProjectDeletionBlockedException extends RuntimeException {
    public ProjectDeletionBlockedException() {
        super("Project execution state does not allow navigation deletion");
    }
}
