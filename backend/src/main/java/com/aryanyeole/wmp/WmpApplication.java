package com.aryanyeole.wmp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WmpApplication {

	public static void main(String[] args) {
		SpringApplication.run(WmpApplication.class, args);
	}

}
