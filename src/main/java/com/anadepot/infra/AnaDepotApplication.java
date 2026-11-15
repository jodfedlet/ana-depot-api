package com.anadepot.infra;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point. It lives in {@code infra} on purpose: component scanning covers only infrastructure, so {@code core}
 * (plain Java, no Spring) is wired explicitly in {@link com.anadepot.infra.config.CoreConfig}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class AnaDepotApplication {

	public static void main(String[] args) {
		SpringApplication.run(AnaDepotApplication.class, args);
	}
}
