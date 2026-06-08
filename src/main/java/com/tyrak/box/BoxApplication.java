package com.tyrak.box;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BoxApplication {

	public static void main(String[] args) {
		SpringApplication.run(BoxApplication.class, args);
	}

}
