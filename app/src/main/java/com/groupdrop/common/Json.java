package com.groupdrop.common;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outbox·Inbox 페이로드와 웹훅 본문을 다루는 최소 JSON 유틸.
 * Boot 4는 Jackson 3(tools.jackson)이며 여기서는 자체 매퍼를 만들어 쓴다 —
 * 페이로드 표현이 HTTP 직렬화 설정 변화에 끌려다니지 않게 하기 위함이다.
 * 시각은 문자열(ISO-8601)로만 주고받는다.
 */
@Component
public class Json {

    private final JsonMapper mapper = JsonMapper.builder().build();

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    public <T> T read(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }
}
