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

    Prepared prepare(Workbench.Run run, Workbench.Call call, String capability);

    void execute(Workbench.Call prepared, BiConsumer<String, byte[]> capture);

    End reconcile(Workbench.Call call, boolean stop);
}
