package com.linxin.chronobot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "bot")
public record BotProperties(
        Qq qq,
        Capsolver capsolver,
        Chrono chrono,
        long cacheTtlMinutes,
        boolean warmup,
        String warmupClub,
        List<String> alias,
        String bindingFile
) {

    public record Qq(String appId, String appSecret, long intents, boolean enabled) {
    }

    public record Capsolver(String apiKey) {
    }

    public record Chrono(String sitekey, String websiteUrl, String carrotSeed) {
    }
}
