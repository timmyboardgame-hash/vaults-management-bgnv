package com.vault.exception;

import org.springframework.http.HttpStatus;

/**
 * error ที่มีรหัสตายตัว — ใช้กับ API ที่แอปภายนอกเรียก
 *
 * แอปฝั่ง boardgame ต้องเขียนเงื่อนไขจาก code ไม่ใช่จากข้อความ
 * เพราะข้อความไทยแก้คำได้ตลอด แต่ code เป็นสัญญา
 */
public class ApiCodeException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final String field;

    public ApiCodeException(String code, HttpStatus status, String message, String field) {
        super(message);
        this.code = code;
        this.status = status;
        this.field = field;
    }

    public String getCode() { return code; }
    public HttpStatus getStatus() { return status; }
    public String getField() { return field; }

    public static ApiCodeException badRequest(String code, String message, String field) {
        return new ApiCodeException(code, HttpStatus.BAD_REQUEST, message, field);
    }

    public static ApiCodeException notFound(String code, String message, String field) {
        return new ApiCodeException(code, HttpStatus.NOT_FOUND, message, field);
    }

    public static ApiCodeException conflict(String code, String message, String field) {
        return new ApiCodeException(code, HttpStatus.CONFLICT, message, field);
    }
}
