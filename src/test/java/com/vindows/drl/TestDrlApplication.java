package com.vindows.drl;

import org.springframework.boot.SpringApplication;

public class TestDrlApplication {

	public static void main(String[] args) {
		SpringApplication.from(DrlApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
