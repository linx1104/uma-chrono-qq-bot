package com.linxin.chronobot.qq;

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
 * QQ 机器人开放平台 HTTP API 封装
 */
@Component
public class QqHttpApi {

    private static final Logger log = LoggerFactory.getLogger(QqHttpApi.class);

    private static final String TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";
    private static final String API_BASE = "https://api.sgroup.qq.com";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper;
    private final BotProperties props;

    private volatile AccessToken accessToken;

    private record AccessToken(String token, long expiresAt) {
    }

    public QqHttpApi(ObjectMapper mapper, BotProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    /**
     * 获取 (并缓存) app access_token, 剩 5 分钟内过期时自动刷新
     */
    public synchronized String accessToken() throws Exception {
        if (accessToken != null && System.currentTimeMillis() < accessToken.expiresAt() - 300_000L) {
            return accessToken.token();
        }
        String body = mapper.writeValueAsString(Map.of(
                "appId", props.qq().appId(),
                "clientSecret", props.qq().appSecret()
        ));
        HttpRequest req = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode json = mapper.readTree(resp.body());
        String token = json.path("access_token").asText();
        long expiresIn = json.path("expires_in").asLong(7200);
        if (token.isBlank()) {
            throw new IllegalStateException("获取 QQ access_token 失败: " + resp.body());
        }
        accessToken = new AccessToken(token, System.currentTimeMillis() + expiresIn * 1000L);
        log.info("QQ access_token 已获取, 有效期 {} 秒", expiresIn);
        return token;
    }

    /**
     * 群聊被动回复
     */
    public void replyGroup(String groupOpenid, String msgId, int msgSeq, String content) {
        try {
            String url = API_BASE + "/v2/groups/" + groupOpenid + "/messages";
            String body = mapper.writeValueAsString(Map.of(
                    "content", content,
                    "msg_type", 0,
                    "msg_id", msgId,
                    "msg_seq", msgSeq
            ));
            post(url, body);
        } catch (Exception e) {
            log.error("群聊回复失败", e);
        }
    }

    /**
     * 私聊被动回复
     */
    public void replyC2c(String openid, String msgId, String content) {
        try {
            String url = API_BASE + "/v2/users/" + openid + "/messages";
            String body = mapper.writeValueAsString(Map.of(
                    "content", content,
                    "msg_type", 0,
                    "msg_id", msgId
            ));
            post(url, body);
        } catch (Exception e) {
            log.error("私聊回复失败", e);
        }
    }

    private void post(String url, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "QQBot " + accessToken())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            log.warn("QQ API {} 返回 {}: {}", url, resp.statusCode(), resp.body());
        }
    }
}
