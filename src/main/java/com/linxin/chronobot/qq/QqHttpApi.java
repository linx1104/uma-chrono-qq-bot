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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
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

    /**
     * 上传群聊图片 (file_type=1), 返回 file_info
     */
    public String uploadGroupImage(String groupOpenid, Path image) throws Exception {
        return uploadFile(API_BASE + "/v2/groups/" + groupOpenid + "/files", image);
    }

    /**
     * 上传私聊图片 (file_type=1), 返回 file_info
     */
    public String uploadC2cImage(String openid, Path image) throws Exception {
        return uploadFile(API_BASE + "/v2/users/" + openid + "/files", image);
    }

    private String uploadFile(String url, Path image) throws Exception {
        byte[] bytes = Files.readAllBytes(image);
        if (bytes.length > 5 * 1024 * 1024) {
            throw new IllegalStateException("图片超过 5MB 上限: " + image);
        }
        String body = mapper.writeValueAsString(Map.of(
                "file_type", 1,
                "file_data", Base64.getEncoder().encodeToString(bytes)
        ));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "QQBot " + accessToken())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            throw new IllegalStateException("上传图片失败 HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode json = mapper.readTree(resp.body());
        String fi = json.path("file_info").asText();
        if (fi.isBlank()) {
            throw new IllegalStateException("上传图片未返回 file_info: " + resp.body());
        }
        return fi;
    }

    /**
     * 群聊发送富媒体消息 (图片, msg_type=7)
     */
    public void sendGroupMedia(String groupOpenid, String msgId, int msgSeq, String fileInfo) {
        try {
            String url = API_BASE + "/v2/groups/" + groupOpenid + "/messages";
            String body = mapper.writeValueAsString(Map.of(
                    "msg_type", 7,
                    "media", Map.of("file_info", fileInfo),
                    "msg_id", msgId,
                    "msg_seq", msgSeq
            ));
            post(url, body);
        } catch (Exception e) {
            log.error("群聊图片发送失败", e);
        }
    }

    /**
     * 私聊发送富媒体消息 (图片, msg_type=7)
     */
    public void sendC2cMedia(String openid, String msgId, String fileInfo) {
        try {
            String url = API_BASE + "/v2/users/" + openid + "/messages";
            String body = mapper.writeValueAsString(Map.of(
                    "msg_type", 7,
                    "media", Map.of("file_info", fileInfo),
                    "msg_id", msgId
            ));
            post(url, body);
        } catch (Exception e) {
            log.error("私聊图片发送失败", e);
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
