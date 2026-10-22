package com.dayoudu.cloud;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
class ApiError {
    static class Failure extends RuntimeException {
        final int status;
        final Object details;
        Failure(int status, String message, Object details) { super(message); this.status = status; this.details = details; }
    }
    static Failure fail(int status, String message) { return new Failure(status, message, Map.of()); }
    @ExceptionHandler(Failure.class)
    ResponseEntity<?> failure(Failure e) {
        return ResponseEntity.status(e.status).body(Map.of("error", e.getMessage(), "details", e.details));
    }
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "请求字段格式无效")); }
}
