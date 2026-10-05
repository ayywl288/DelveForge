package com.ayywl.delveforge.application.port.persistence;

/** Concurrent lifecycle/authorization changes invalidate the atomic commit's read basis. */
public final class EvolutionLifecycleConflictException extends RuntimeException {
    public EvolutionLifecycleConflictException(String message) { super(message); }
    public EvolutionLifecycleConflictException(String message, Throwable cause) { super(message, cause); }
}
