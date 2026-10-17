package com.linxin.chronobot.chrono;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linxin.chronobot.config.BotProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.GZIPInputStream;

/**
 * ChronoGenesis.net 数据客户端
 * <p>
 * 验证机制 (实测结论, 2026-10-17):
 * 1. 首次请求返回 422 表示需要人机验证 (Cloudflare Turnstile)
 * 2. 用打码平台解出 token, POST /verify 换 JWT Cookie (有效期 72 小时, 绑定请求 IP)
 * 3. 之后请求必须同时携带: Cookie + CG-Carrot 请求头, 且 User-Agent/Accept-Language
 *    等请求头需与验证时保持一致 (服务端会校验一致性)
 * <p>
 * 因此本客户端全局使用固定 UA / Accept-Language / CG-Carrot, 验证与查询从同一台
 * 服务器发起, 天然满足 IP 一致性。
 */
@Component
public class ChronoClient {

    private static final Logger log = LoggerFactory.getLogger(ChronoClient.class);

    public static final String API_BASE = "https://api.chronogenesis.net";

    /** 必须全局固定, 不可中途修改 */
    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";
    public static final String ACCEPT_LANG = "zh-CN,zh;q=0.9,en;q=0.8";

    /** 会话建立后 5 分钟内收到 422 视为并发窗口期旧请求, 不重新验证 */
    private static final long COOKIE_FRESH_MS = 5 * 60_000L;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper mapper;
    private final BotProperties props;
    private final CapsolverClient capsolver;

    /** 固定指纹, 全链路不变 */
    private final String carrot;

    private volatile String cookie;
    private volatile long cookieAt;

    private final ReentrantLock verifyLock = new ReentrantLock();

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "chrono-warmup");
                t.setDaemon(true);
                return t;
            });

    private record CacheEntry(ClubData club, long expireAt) {
    }

    public ChronoClient(ObjectMapper mapper, BotProperties props, CapsolverClient capsolver) {
        this.mapper = mapper;
        this.props = props;
        this.capsolver = capsolver;
        this.carrot = sha256Hex(props.chrono().carrotSeed());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        if (!props.warmup() || props.warmupClub() == null || props.warmupClub().isBlank()) {
            return;
        }
        scheduler.execute(() -> {
            try {
                ClubData d = queryClub(props.warmupClub());
                log.info("预热成功: {} 当日粉丝 {}", d.name(), d.fanCount());
            } catch (Exception e) {
                log.warn("预热失败 (不影响后续自动重试): {}", e.getMessage());
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * 查询社团粉丝数据, 带缓存; 422 时自动重新验证并重试一次
     */
    public ClubData queryClub(String name) throws Exception {
        CacheEntry hit = cache.get(name);
        if (hit != null && System.currentTimeMillis() < hit.expireAt()) {
            return hit.club();
        }
        try {
            ClubData d = fetch(name);
            cache.put(name, new CacheEntry(d, System.currentTimeMillis() + props.cacheTtlMinutes() * 60_000L));
            return d;
        } catch (NeedsVerifyException e) {
            ensureVerified();
            ClubData d = fetch(name);
            cache.put(name, new CacheEntry(d, System.currentTimeMillis() + props.cacheTtlMinutes() * 60_000L));
            return d;
        }
    }

    public long cookieAgeSeconds() {
        if (cookie == null) {
            return -1;
        }
        return (System.currentTimeMillis() - cookieAt) / 1000;
    }

    public int cacheSize() {
        return cache.size();
    }

    private ClubData fetch(String name) throws Exception {
        String url = API_BASE + "/jp/club_profile?circle_id="
                + URLEncoder.encode(name, StandardCharsets.UTF_8) + "&jp=";
        HttpRequest req = requestBuilder(url)
                .GET()
                .build();
        SimpleResp resp = send(req);

        if (resp.statusCode() == 422) {
            throw new NeedsVerifyException();
        }
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        JsonNode root = mapper.readTree(resp.body());
        JsonNode club = root.path("club");
        if (club.isArray() && club.size() > 0) {
            JsonNode c = club.get(0);
            return new ClubData(
                    name,
                    c.path("name").asText(name),
                    c.path("fan_count").asLong(),
                    c.path("daily_average").asLong(),
                    c.path("monthly_average").asLong(),
                    c.path("daily_diff").asLong(),
                    c.path("rank").asInt(),
                    c.path("updated_at").asText(),
                    c.path("member_num").asInt(),
                    parseMembers(root.path("club_friend_profile"))
            );
        }
        String msg = root.path("message").asText();
        throw new ClubNotFoundException(msg == null || msg.isBlank() ? "未找到社团: " + name : msg);
    }

    /**
     * 解析成员粉丝列表 (club_friend_profile)
     */
    private List<ClubData.MemberData> parseMembers(JsonNode arr) {
        List<ClubData.MemberData> out = new java.util.ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode m : arr) {
            List<String> names = new java.util.ArrayList<>();
            JsonNode ns = m.path("names");
            if (ns.isArray()) {
                for (JsonNode n : ns) {
                    if (n != null && !n.asText().isBlank()) {
                        names.add(n.asText());
                    }
                }
            }
            out.add(new ClubData.MemberData(
                    m.path("friend_viewer_id").asLong(),
                    m.path("name").asText(),
                    names,
                    m.path("fan_count").asLong(),
                    m.path("daily_average").asLong(),
                    m.path("monthly_average").asLong(),
                    m.path("daily_diff").asLong(),
                    m.path("monthly_diff").asLong(),
                    m.path("last_month").asLong(),
                    m.path("join_time").asText(),
                    m.path("last_login_time").asText(),
                    m.path("membership").asInt(),
                    m.path("updated_at").asText()
            ));
        }
        return out;
    }

    /**
     * 确保会话有效; 首次或临近过期时走完整验证流程。
     * 若会话刚建立 (5 分钟内) 却收到 422, 多为并发窗口期旧请求, 跳过验证直接重试。
     */
    private void ensureVerified() throws Exception {
        verifyLock.lock();
        try {
            if (cookie != null && System.currentTimeMillis() - cookieAt < COOKIE_FRESH_MS) {
                log.info("会话仍新鲜 ({} 秒), 收到 422 视为并发窗口期, 跳过重新验证",
                        (System.currentTimeMillis() - cookieAt) / 1000);
                return;
            }
            log.info("会话缺失或过期, 开始验证");
            verify();
        } finally {
            verifyLock.unlock();
        }
    }

    private void verify() throws Exception {
        String token = capsolver.solve();

        HttpRequest req = requestBuilder(API_BASE + "/verify")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                        java.util.Map.of(
                                "CF-Turnstile-Token", token,
                                "CG-Carrot", carrot
                        ))))
                .build();
        SimpleResp vresp = send(req);
        if (vresp.statusCode() != 200) {
            throw new IllegalStateException("verify 失败: HTTP " + vresp.statusCode() + " " + vresp.body());
        }
        String setCookie = vresp.headers().firstValue("Set-Cookie").orElse("");
        String value = java.util.Arrays.stream(setCookie.split(";"))
                .map(String::trim)
                .filter(s -> s.startsWith("kurono="))
                .map(s -> s.substring("kurono=".length()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("verify 响应未携带 kurono Cookie"));
        this.cookie = value;
        this.cookieAt = System.currentTimeMillis();
        log.info("验证会话已刷新, 有效期 72 小时");
    }

    private HttpRequest.Builder requestBuilder(String url) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", UA)
                .header("Accept-Language", ACCEPT_LANG)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "gzip, deflate")
                .header("CG-Carrot", carrot);
        if (cookie != null) {
            b.header("Cookie", "kurono=" + cookie);
        }
        return b;
    }

    private SimpleResp send(HttpRequest req) throws Exception {
        HttpResponse<byte[]> raw = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        byte[] body = raw.body();
        String encoding = raw.headers().firstValue("Content-Encoding").orElse("");
        if (encoding.contains("gzip")) {
            try (GZIPInputStream gin = new GZIPInputStream(new ByteArrayInputStream(body));
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                gin.transferTo(out);
                body = out.toByteArray();
            }
        }
        String text = new String(body, Charset.forName("UTF-8"));
        return new SimpleResp(raw.statusCode(), raw.headers(), text);
    }

    private record SimpleResp(int statusCode, java.net.http.HttpHeaders headers, String body) {
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static class NeedsVerifyException extends Exception {
        public NeedsVerifyException() {
            super("站点触发了人机验证限流, 请稍后重试");
        }
    }

    public static class ClubNotFoundException extends Exception {
        public ClubNotFoundException(String message) {
            super(message);
        }
    }
}
