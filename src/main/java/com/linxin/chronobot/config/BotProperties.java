package com.linxin.chronobot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "bot")
public record BotProperties(
        Qq qq,
        Capsolver capsolver,
        Chrono chrono,
        DeepSeek deepseek,
        Chat chat,
        long cacheTtlMinutes,
        boolean warmup,
        String warmupClub,
        List<String> alias,
        String bindingFile,
        String historyFile
) {

    public record Qq(String appId, String appSecret, long intents, boolean enabled) {
    }

    public record Capsolver(String apiKey) {
    }

    public record Chrono(String sitekey, String websiteUrl, String carrotSeed) {
    }

    public record DeepSeek(String apiKey, String apiUrl, String model, long timeoutSeconds) {
    }

    public record Chat(boolean enabled, long baseMessages, int maxMemoryChars,
                       String memoryDir, String personaFile, Trigger trigger) {
        public record Trigger(int minScore, int randomPercent, int groupCooldownSeconds) {
        }
    }
}
