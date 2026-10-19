package com.linxin.chronobot.chat;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek 聊天补全客户端 (OpenAI 兼容协议)
 * <p>
 * 密钥通过环境变量 DEEPSEEK_API_KEY 注入, 未配置时 available()=false, 聊天功能整体停用。
 */
@Component
public class DeepSeekClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    public record Msg(String role, String content) {
        public static Msg system(String c) {
            return new Msg("system", c);
        }

        public static Msg user(String c) {
            return new Msg("user", c);
        }

        public static Msg assistant(String c) {
            return new Msg("assistant", c);
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper mapper;
    private final BotProperties props;

    private volatile boolean keyWarned = false;

    public DeepSeekClient(ObjectMapper mapper, BotProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    public boolean available() {
        if (props.deepseek() == null || props.deepseek().apiKey() == null
                || props.deepseek().apiKey().isBlank()) {
            if (!keyWarned) {
                keyWarned = true;
                log.warn("DEEPSEEK_API_KEY 未配置, 聊天功能停用 (仅 cy 指令可用)");
            }
            return false;
        }
        return true;
    }

    /**
     * 聊天补全, 返回助手回复文本; 失败抛出异常
     */
    public String chat(List<Msg> messages, double temperature, int maxTokens) throws Exception {
        BotProperties.DeepSeek ds = props.deepseek();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", ds.model() == null ? "deepseek-chat" : ds.model());
        payload.put("messages", messages.stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList());
        payload.put("temperature", temperature);
        payload.put("max_tokens", maxTokens);

        HttpRequest req = HttpRequest.newBuilder(URI.create(ds.apiUrl()))
                .timeout(Duration.ofSeconds(ds.timeoutSeconds() > 0 ? ds.timeoutSeconds() : 60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + ds.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode json = mapper.readTree(resp.body());
        if (resp.statusCode() >= 300) {
            String msg = json.path("error").path("message").asText("");
            throw new IllegalStateException("DeepSeek 返回 " + resp.statusCode()
                    + (msg.isBlank() ? "" : ": " + abbreviate(msg)));
        }
        JsonNode usage = json.path("usage");
        log.info("DeepSeek 用量: prompt={} completion={}",
                usage.path("prompt_tokens").asLong(0), usage.path("completion_tokens").asLong(0));
        return json.path("choices").path(0).path("message").path("content").asText("");
    }

    private String abbreviate(String s) {
        String one = s.replace('\n', ' ');
        return one.length() <= 120 ? one : one.substring(0, 120) + "...";
    }
}
