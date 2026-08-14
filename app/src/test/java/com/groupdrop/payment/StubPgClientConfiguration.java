package com.groupdrop.payment;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class StubPgClientConfiguration {

    @Bean
    @Primary
    public StubPgClient stubPgClient() {
        return new StubPgClient();
    }
}
