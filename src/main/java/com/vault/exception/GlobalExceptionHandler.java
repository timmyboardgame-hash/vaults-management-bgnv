package com.vault.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

// Apply only to @RestController — NOT to @Controller (Thymeleaf views)
@RestControllerAdvice(annotations = RestController.class)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // business/validation error ที่คาดไว้ — log ระดับ warn ไม่มี stack trace (ไม่ใช่ bug)
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("[API] Bad request: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        var errors = e.getBindingResult().getFieldErrors()
            .stream()
            .collect(Collectors.toMap(
                fe -> fe.getField(),
                fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid"
            ));
        log.warn("[API] Validation failed: {}", errors);
        return ResponseEntity.badRequest().body(Map.of("error", "Validation failed", "fields", errors));
    }

    // error ที่ไม่คาดคิด (bug/NPE/DB) — log พร้อม stack trace เต็ม เพื่อตามสาเหตุจาก log ได้
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneral(Exception e) {
        log.error("[API] Unhandled exception", e);
        return ResponseEntity.internalServerError().body(Map.of("error", "Internal server error"));
    }
}
