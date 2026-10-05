package com.ayywl.delveforge.application.port.persistence;

/**
 * 并发的生命周期或授权变化使本次原子提交的读取依据失效。
 */
public final class EvolutionLifecycleConflictException extends RuntimeException {
    public EvolutionLifecycleConflictException(String message) {
        super(message);
    }

    public EvolutionLifecycleConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
