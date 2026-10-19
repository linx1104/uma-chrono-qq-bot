package com.linxin.chronobot;

import com.linxin.chronobot.config.BotProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ChronoBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChronoBotApplication.class, args);
    }
}
