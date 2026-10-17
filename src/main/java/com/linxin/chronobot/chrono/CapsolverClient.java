package com.linxin.chronobot.chrono;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linxin.chronobot.config.BotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * capsolver 打码平台客户端: 解 Cloudflare Turnstile
 */
@Component
public class CapsolverClient {

    private static final Logger log = LoggerFactory.getLogger(CapsolverClient.class);

    private static final String CREATE_TASK_URL = "https://api.capsolver.com/createTask";
    private static final String GET_RESULT_URL = "https://api.capsolver.com/getTaskResult";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper mapper;
    private final BotProperties props;

    public CapsolverClient(ObjectMapper mapper, BotProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    /**
     * 解 Turnstile 并返回 token (约 5-40 秒)
     */
    public String solve() throws Exception {
        String apiKey = props.capsolver().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("capsolver api-key 未配置");
        }

        // 1. 创建任务
        Map<String, Object> task = Map.of(
                "type", "AntiTurnstileTaskProxyLess",
                "websiteURL", props.chrono().websiteUrl(),
                "websiteKey", props.chrono().sitekey()
        );
        JsonNode created = postJson(CREATE_TASK_URL, mapper.writeValueAsString(Map.of(
                "clientKey", apiKey,
                "task", task
        )));
        if (created.path("errorId").asInt(1) != 0) {
            throw new IllegalStateException("capsolver 创建任务失败: "
                    + created.path("errorCode").asText() + " "
                    + created.path("errorDescription").asText());
        }
        String taskId = created.path("taskId").asText();
        log.info("capsolver 任务已创建: {}", taskId);

        // 2. 轮询结果
        for (int i = 0; i < 36; i++) { // 最长 3 分钟
            Thread.sleep(5000);
            JsonNode res = postJson(GET_RESULT_URL, mapper.writeValueAsString(Map.of(
                    "clientKey", apiKey,
                    "taskId", taskId
            )));
            String status = res.path("status").asText();
            if ("ready".equals(status)) {
                String token = res.path("solution").path("token").asText();
                if (token == null || token.isBlank()) {
                    throw new IllegalStateException("capsolver 返回空 token");
                }
                log.info("capsolver 解验证成功 ({} 秒)", (i + 1) * 5);
                return token;
            }
            if (res.path("errorId").asInt(1) != 0) {
                throw new IllegalStateException("capsolver 解验证失败: "
                        + res.path("errorCode").asText() + " "
                        + res.path("errorDescription").asText());
            }
        }
        throw new IllegalStateException("capsolver 解验证超时 (3 分钟)");
    }

    private JsonNode postJson(String url, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(resp.body());
    }
}
