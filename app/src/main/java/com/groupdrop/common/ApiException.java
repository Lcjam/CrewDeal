package com.groupdrop.common;

import org.springframework.http.HttpStatus;

/**
 * 도메인 검증·인가 실패를 {code, detail} 응답으로 변환하기 위한 공통 예외.
 * RFC 9457 공통 포맷 정비는 별도 작업 범위 — 이번 주는 간단한 {code, detail}만 사용한다.
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
