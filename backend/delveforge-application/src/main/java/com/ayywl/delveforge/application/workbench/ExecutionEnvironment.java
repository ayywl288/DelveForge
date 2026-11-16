package com.ayywl.delveforge.application.workbench;

import java.util.function.BiConsumer;

/** 通过同一环境准备并执行四种工具。隔离、命令身份与进程组证据由适配器提供。 */
public interface ExecutionEnvironment {
    record Prepared(String container, String generation, String execId) {}

    record End(boolean ended, Integer exit, String detail) {}

    final class TimedOut extends RuntimeException {
        public TimedOut() {
            super("前台命令超时");
        }
    }

    default void maintain(String project, boolean replace) {
        throw new UnsupportedOperationException("此环境不支持维护");
    }

    /** 已退出的维护进程及登记状态都能核实后，才可解除崩溃留下的维护占用。 */
    default boolean maintenanceEnded(String project) {
        return false;
    }

    Prepared prepare(Workbench.Run run, Workbench.Call call, String capability);

    void execute(Workbench.Call prepared, BiConsumer<String, byte[]> capture);

    End reconcile(Workbench.Call call, boolean stop);
}
