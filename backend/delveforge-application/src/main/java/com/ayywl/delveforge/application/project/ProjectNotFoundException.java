package com.ayywl.delveforge.application.project;

public final class ProjectNotFoundException extends RuntimeException {
    public ProjectNotFoundException() {
        super("Project context or historical plan not found");
    }
}
