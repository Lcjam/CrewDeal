package com.groupdrop.mockpg;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(MockPgWebhookProperties.class)
public class MockPgApplication {

	public static void main(String[] args) {
		SpringApplication.run(MockPgApplication.class, args);
	}

}
