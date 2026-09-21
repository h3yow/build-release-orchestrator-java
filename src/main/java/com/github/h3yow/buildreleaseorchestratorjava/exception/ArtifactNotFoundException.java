package com.github.h3yow.buildreleaseorchestratorjava.exception;

/**
 * 制品不存在或不可用。
 */
public class ArtifactNotFoundException extends OrchestratorException {
    public ArtifactNotFoundException(String message) {
        super("ARTIFACT_NOT_FOUND", message);
    }
}
