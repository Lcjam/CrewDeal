package com.groupdrop;

import org.springframework.boot.SpringApplication;

public class TestGroupdropApplication {

	public static void main(String[] args) {
		SpringApplication.from(GroupdropApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
