package com.aryanyeole.wmp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** @EnableScheduling backs PayrollAccrualJob's nightly cron trigger (Phase 8). */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class WmpApplication {

	public static void main(String[] args) {
		SpringApplication.run(WmpApplication.class, args);
	}

}
