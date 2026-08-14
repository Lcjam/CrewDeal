package com.groupdrop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GroupdropApplication {

	public static void main(String[] args) {
		SpringApplication.run(GroupdropApplication.class, args);
	}

}
