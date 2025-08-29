package com.app.swingtrader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * App
 */
@SpringBootApplication
@EnableScheduling
public class SwingTraderApplication {

	/**
	 * main
	 * @param args
	 */
	public static void main(String[] args) {
		SpringApplication.run(SwingTraderApplication.class, args);
	}

}
