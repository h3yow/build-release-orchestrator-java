package com.github.h3yow.buildreleaseorchestratorjava.model;

import com.github.h3yow.buildreleaseorchestratorjava.error.ErrorCode;

public record ErrorView(ErrorCode code, String message) {
}
