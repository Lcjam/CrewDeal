package com.groupdrop.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * {@link ApiException}을 RFC 9457 Problem Details로 변환한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException exception, HttpServletRequest request) {
        return problem(exception.getStatus(), exception.getCode(), exception.getDetail(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationException(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        FieldError fieldError = exception.getBindingResult().getFieldError();
        String detail = fieldError == null ? "요청값이 유효하지 않습니다."
                : fieldError.getField() + ": " + fieldError.getDefaultMessage();
        return problem(org.springframework.http.HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableMessage(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        return problem(org.springframework.http.HttpStatus.BAD_REQUEST, "MALFORMED_JSON",
                "요청 본문을 읽을 수 없습니다.", request);
    }

    private ResponseEntity<ProblemDetail> problem(org.springframework.http.HttpStatus status, String code,
                                                  String detail, HttpServletRequest request) {
        ProblemDetail body = ProblemDetails.of(status, code, detail, request);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
