package com.linxin.chronobot.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linxin.chronobot.config.BotProperties;
import com.linxin.chronobot.service.BotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * QQ 机器人 WebSocket 网关客户端
 * <p>
 * 协议: op 10=Hello(心跳间隔) / op 2=Identify / op 1=心跳 / op 11=心跳回执 / op 0=事件下发
 * 断线后自动重连并重新 Identify (QQ 机器人不支持 resume)
 */
@Component
public class QqWebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(QqWebSocketClient.class);

    private static final String GATEWAY = "wss://api.sgroup.qq.com/websocket";

    private final QqHttpApi qqApi;
    private final BotService botService;
    private final BotProperties props;
    private final ObjectMapper mapper;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "qq-gateway");
        t.setDaemon(true);
        return t;
    });

    private volatile WebSocket ws;
    private final AtomicInteger lastSeq = new AtomicInteger(0);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile long retryDelaySec = 5;

    public QqWebSocketClient(QqHttpApi qqApi, BotService botService, BotProperties props, ObjectMapper mapper) {
        this.qqApi = qqApi;
        this.botService = botService;
        this.props = props;
        this.mapper = mapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!props.qq().enabled()) {
            log.info("QQ 接入已禁用 (bot.qq.enabled=false)");
            return;
        }
        log.info("启动 QQ 网关连接...");
        connect();
    }

    private void connect() {
        try {
            String accessToken = qqApi.accessToken();
            HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .buildAsync(URI.create(GATEWAY), new Listener(accessToken))
                    .whenComplete((webSocket, err) -> {
                        if (err != null) {
                            log.error("QQ 网关连接失败: {}", err.getMessage());
                            scheduleReconnect();
                        } else {
                            log.info("QQ 网关 WebSocket 已连接");
                            ws = webSocket;
                        }
                    });
        } catch (Exception e) {
            log.error("QQ 网关连接异常: {}", e.getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!reconnecting.compareAndSet(false, true)) {
            return;
        }
        cancelHeartbeat();
        long delay = retryDelaySec;
        retryDelaySec = Math.min(retryDelaySec * 2, 300);
        log.info("{} 秒后重连 QQ 网关", delay);
        scheduler.schedule(() -> {
            reconnecting.set(false);
            connect();
        }, delay, TimeUnit.SECONDS);
    }

    private void cancelHeartbeat() {
        ScheduledFuture<?> t = heartbeatTask;
        if (t != null) {
            t.cancel(false);
            heartbeatTask = null;
        }
    }

    private void sendHeartbeat() {
        WebSocket w = ws;
        if (w == null) {
            return;
        }
        try {
            w.sendText("{\"op\":1,\"d\":" + lastSeq.get() + "}", true);
        } catch (Exception e) {
            log.warn("心跳发送失败: {}", e.getMessage());
        }
    }

    private class Listener implements WebSocket.Listener {

        private final String accessToken;
        private final StringBuilder buffer = new StringBuilder();
        private boolean identified = false;

        Listener(String accessToken) {
            this.accessToken = accessToken;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
            String identify = String.format(
                    "{\"op\":2,\"d\":{\"token\":\"QQBot %s\",\"intents\":%d,\"shard\":[0,1]}}",
                    accessToken, props.qq().intents());
            webSocket.sendText(identify, true);
            log.info("已发送 Identify");
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                handle(text);
            }
            webSocket.request(1);
            return null;
        }

        private void handle(String text) {
            try {
                JsonNode node = mapper.readTree(text);
                int op = node.path("op").asInt(-1);
                switch (op) {
                    case 10 -> { // Hello
                        long interval = node.path("d").path("heartbeat_interval").asLong(41250);
                        cancelHeartbeat();
                        heartbeatTask = scheduler.scheduleAtFixedRate(
                                QqWebSocketClient.this::sendHeartbeat, interval, interval, TimeUnit.MILLISECONDS);
                        log.info("QQ 网关 Hello, 心跳间隔 {} ms", interval);
                    }
                    case 11 -> { // 心跳回执, 忽略
                    }
                    case 0 -> { // Dispatch
                        if (!identified) {
                            identified = true;
                            retryDelaySec = 5;
                            log.info("QQ 网关 Identify 成功");
                        }
                        lastSeq.set(node.path("s").asInt(lastSeq.get()));
                        dispatch(node.path("t").asText(), node.path("d"), lastSeq.get());
                    }
                    default -> log.debug("未知 op: {}", text);
                }
            } catch (Exception e) {
                log.error("处理网关消息失败: {}", e.getMessage());
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.warn("QQ 网关连接关闭: code={} reason={}", statusCode, reason);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("QQ 网关错误: {}", error.getMessage());
        }
    }

    private void dispatch(String type, JsonNode d, int seq) {
        switch (type) {
            case "GROUP_AT_MESSAGE_CREATE" -> botService.onGroupAtMessage(d, seq);
            case "C2C_MESSAGE_CREATE" -> botService.onC2cMessage(d, seq);
            default -> {
            }
        }
    }
}
