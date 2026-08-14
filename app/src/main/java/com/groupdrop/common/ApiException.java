package com.groupdrop.common;

import org.springframework.http.HttpStatus;

/**
 * 도메인 검증·인가 실패를 RFC 9457 Problem Details(type, title, status, detail, code)로
 * 변환하기 위한 공통 예외.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String detail;

    public ApiException(HttpStatus status, String code, String detail) {
        super(code + ": " + detail);
        this.status = status;
        this.code = code;
        this.detail = detail;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getDetail() {
        return detail;
    }
}
