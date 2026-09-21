package com.github.h3yow.buildreleaseorchestratorjava.exception;

/**
 * 回滚被拒绝：目标制品不存在、不可用或校验和不一致。
 */
public class RollbackRejectedException extends OrchestratorException {
    public RollbackRejectedException(String code, String message) {
        super(code, message);
    }
}
