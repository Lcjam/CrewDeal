package com.groupdrop.common;

import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** 목록 API의 CHECK 값 상태 필터를 한 계약으로 검증한다. */
public final class StatusFilter {

    private StatusFilter() { }

    public static List<String> parse(String value, Set<String> allowed, List<String> defaults) {
        if (value == null || value.isBlank()) {
            return defaults;
        }
        List<String> statuses = List.of(value.split(",", -1));
        if (statuses.stream().anyMatch(status -> !allowed.contains(status))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS_FILTER", "허용되지 않은 상태 필터입니다.");
        }
        return statuses;
    }
}
