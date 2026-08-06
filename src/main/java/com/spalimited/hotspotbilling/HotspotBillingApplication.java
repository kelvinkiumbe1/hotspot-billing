package com.spalimited.hotspotbilling;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class HotspotBillingApplication {

	public static void main(String[] args) {
		SpringApplication.run(HotspotBillingApplication.class, args);
	}

}
