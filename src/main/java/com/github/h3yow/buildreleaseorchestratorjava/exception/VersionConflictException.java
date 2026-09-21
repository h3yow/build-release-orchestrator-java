package com.github.h3yow.buildreleaseorchestratorjava.exception;

/**
 * 版本冲突：区分“版本已存在且内容一致”与“版本已存在但校验和不一致”。
 */
public class VersionConflictException extends OrchestratorException {
    public static final String VERSION_ALREADY_EXISTS = "VERSION_ALREADY_EXISTS";
    public static final String CHECKSUM_MISMATCH = "CHECKSUM_MISMATCH";

    public VersionConflictException(String code, String message) {
        super(code, message);
    }
}
