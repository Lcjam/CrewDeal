package com.groupdrop.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StatusFilterTest {

    @Test
    void 기본값과_콤마_다중값만_허용한다() {
        assertThat(StatusFilter.parse(null, Set.of("OPEN", "CLOSED"), List.of("OPEN"))).containsExactly("OPEN");
        assertThat(StatusFilter.parse("OPEN,CLOSED", Set.of("OPEN", "CLOSED"), List.of())).containsExactly("OPEN", "CLOSED");
        assertThatThrownBy(() -> StatusFilter.parse("DRAFT", Set.of("OPEN"), List.of()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_STATUS_FILTER"));
    }
}
