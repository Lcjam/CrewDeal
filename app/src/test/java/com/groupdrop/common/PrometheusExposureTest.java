package com.groupdrop.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupdrop.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** 16.4의 1주차 완료 기준: Prometheus endpoint가 인증 없이 노출된다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PrometheusExposureTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void prometheus_endpoint가_노출되고_requestId를_반환한다() throws Exception {
        mockMvc.perform(get("/actuator/prometheus").header(RequestIdFilter.HEADER_NAME, "metrics-request"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("jvm_memory")))
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, "metrics-request"));
    }

    /**
     * 16.4가 이름까지 고정한 도메인 지표는 <b>해당 사건이 한 번도 없었어도</b> 노출돼야 한다.
     * 지연 등록으로 두면 정상 운영 중인 앱에서 지표 자체가 사라져, 대시보드·경보가 "지표 없음"과
     * "값 0"을 구분하지 못한다. 실제로 {@code reconciliation_mismatch_total}이 태그 지연 등록 탓에
     * 빠져 있었고, Grafana 쪽 fallback({@code or vector(0)})이 그 부재를 가려 검증이 무력화됐다.
     */
    @Test
    void 기획서_16_4가_고정한_도메인_지표가_사건_없이도_전부_노출된다() throws Exception {
        String body = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> missing = new java.util.ArrayList<>();
        for (String metric : List.of(
                "inventory_reservation_success_total",
                "inventory_sold_out_total",
                "inventory_update_duration_seconds",
                "payment_attempt_total",
                "payment_unknown_total",
                "payment_duplicate_prevented_total",
                "webhook_duplicate_total",
                "outbox_pending_count",
                "outbox_oldest_event_age",
                "ledger_unbalanced_total",
                "reconciliation_mismatch_total",
                "settlement_failed_total")) {
            if (!body.matches("(?s).*(?m)^" + metric + ".*")) {
                missing.add(metric);
            }
        }
        assertThat(missing).as("16.4 필수 지표가 사건 발생 전에는 노출되지 않습니다").isEmpty();
    }
}
