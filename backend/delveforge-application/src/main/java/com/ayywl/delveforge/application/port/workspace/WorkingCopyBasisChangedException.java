package com.ayywl.delveforge.application.port.workspace;

/** The physical source no longer represents the requested planning baseline. */
public class WorkingCopyBasisChangedException extends RuntimeException {
    public WorkingCopyBasisChangedException(String message) { super(message); }
}
