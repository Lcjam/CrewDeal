package com.groupdrop.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.groupdrop.TestcontainersConfiguration;
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
}
