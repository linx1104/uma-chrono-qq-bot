package com.linxin.chronobot.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linxin.chronobot.config.BotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 群友记忆库 (每日凌晨批量整理版)
 * <p>
 * 目录结构: {memoryDir}/{会话}/log-YYYY-MM-DD.jsonl   全天消息流水 (本地记录, 零 token)
 *           {memoryDir}/{会话}/{群友}/memory.md       记忆档案
 *           {memoryDir}/{会话}/{群友}/pending.jsonl   建档案前的原始消息队列
 * <p>
 * 策略 (用户定义):
 * 1. 白天所有文字消息只写本地流水, 不调 DeepSeek (零 token)
 * 2. 群友还没有档案时, 前 baseMessages 条消息原样写入 memory.md 作为基础记忆 (零 token)
 * 3. 每天凌晨 3 点 (Asia/Shanghai), 把昨天 4 点到今天 3 点的流水 + 发言群友现有档案
 *    一次性喂给 DeepSeek, 输出「记忆增量」本地追加; 每个会话每天只调一次, 成本固定
 * 4. 单群友档案上限 maxMemoryChars 字
 */
@Component
public class MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 每日整理窗口: 终点凌晨 3 点, 起点前一天凌晨 4 点 (窗口 23 小时) */
    private static final int BATCH_END_HOUR = 3;
    private static final int WINDOW_HOURS = 23;

    /** 解析模型输出 "--- 发言者1" 分块 */
    private static final Pattern BLOCK = Pattern.compile("^\\s*---\\s*发言者\\s*(\\d+)");

    private final ObjectMapper mapper;
    private final DeepSeekClient deepSeek;
    private final BotProperties props;
    private final Path memoryRoot;
    private final Path lastBatchFile;

    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    /** 批处理/基础记忆串行执行, 避免并发写文件 */
    private final ExecutorService maintenanceExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "memory-batch");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);

    public MemoryStore(ObjectMapper mapper, DeepSeekClient deepSeek, BotProperties props) {
        this.mapper = mapper;
        this.deepSeek = deepSeek;
        this.props = props;
        String dir = (props.chat() == null || props.chat().memoryDir() == null)
                ? "memory" : props.chat().memoryDir();
        this.memoryRoot = Path.of(dir);
        this.lastBatchFile = memoryRoot.resolve("last-batch.txt");
    }

    public record Memory(String content, List<String> recent) {
    }

    private int baseMessages() {
        return props.chat() == null || props.chat().baseMessages() <= 0 ? 10 : (int) props.chat().baseMessages();
    }

    private int maxMemoryChars() {
        return props.chat() == null || props.chat().maxMemoryChars() <= 0 ? 10000 : props.chat().maxMemoryChars();
    }

    private Path ctxDir(String ctxKey) {
        return memoryRoot.resolve(sanitize(ctxKey));
    }

    private Path userDir(String ctxKey, String userKey) {
        return ctxDir(ctxKey).resolve(sanitize(userKey));
    }

    private String sanitize(String s) {
        return s == null ? "unknown" : s.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private Object lock(String key) {
        return locks.computeIfAbsent(key, k -> new Object());
    }

    /**
     * 追加一条群友消息: 写入当天流水 (零 token); 尚无档案的群友同时攒基础记忆
     */
    public void record(String ctxKey, String userKey, String content) {
        if (ctxKey == null || userKey == null || userKey.isBlank()
                || content == null || !isWorthRecording(content)) {
            return;
        }
        String one = content.replace('\n', ' ').trim();
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        // 1) 消息流水 (超长消息截断, 防撑爆每日提示词)
        String logContent = one.length() > 500 ? one.substring(0, 500) : one;
        synchronized (lock(ctxKey + "|log")) {
            try {
                Path dir = ctxDir(ctxKey);
                Files.createDirectories(dir);
                String line = mapper.writeValueAsString(Map.of(
                        "t", now.format(TS),
                        "ts", now.toInstant().toEpochMilli(),
                        "u", userKey,
                        "c", logContent));
                Files.writeString(dir.resolve("log-" + now.format(DAY) + ".jsonl"), line + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception e) {
                log.warn("消息流水记录失败 {}: {}", userKey, e.getMessage());
            }
        }
        // 2) 基础记忆 (仅档案还不存在时)
        Path dir = userDir(ctxKey, userKey);
        if (Files.exists(dir.resolve("memory.md"))) {
            return;
        }
        synchronized (lock(ctxKey + "|" + userKey)) {
            try {
                if (Files.exists(dir.resolve("memory.md"))) {
                    return;
                }
                Files.createDirectories(dir);
                Path pending = dir.resolve("pending.jsonl");
                Files.writeString(pending, mapper.writeValueAsString(Map.of(
                        "t", now.format(TS), "c", one)) + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                List<String> lines = readPending(dir);
                if (lines.size() >= baseMessages()) {
                    writeBaseMemory(dir, lines.subList(0, baseMessages()));
                    rewritePending(dir, new ArrayList<>(lines.subList(baseMessages(), lines.size())));
                }
            } catch (Exception e) {
                log.warn("基础记忆记录失败 {}: {}", userKey, e.getMessage());
            }
        }
    }

    /** 读取记忆: content=档案正文, recent=该群友最近几条流水消息 (供调试查看) */
    public Memory load(String ctxKey, String userKey) {
        if (ctxKey == null || userKey == null) {
            return new Memory("", List.of());
        }
        try {
            Path dir = userDir(ctxKey, userKey);
            String content = readMemoryBody(dir);
            List<String> recent = readRecentLog(ctxKey, userKey, 10);
            return new Memory(content, recent);
        } catch (Exception e) {
            return new Memory("", List.of());
        }
    }

    // ---------- 每日批量整理 ----------

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Shanghai")
    public void scheduledBatch() {
        maintenanceExecutor.execute(() -> runDailyBatch(false));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        maintenanceExecutor.execute(() -> runDailyBatch(false));
    }

    /**
     * 跑每日记忆整理。force=true 时把「最近 23 小时」的流水立刻整理一遍 (调试用)。
     * 正常情况下自动补齐所有漏跑的窗口 (服务重启自愈)。
     */
    public Map<String, Object> runDailyBatch(boolean force) {
        if (!deepSeek.available()) {
            return Map.of("ok", false, "reason", "DEEPSEEK_API_KEY 未配置");
        }
        if (!running.compareAndSet(false, true)) {
            return Map.of("ok", false, "reason", "已有整理任务在运行");
        }
        try {
            LocalDate today = LocalDate.now(ZONE);
            LocalDate last = readLastBatch();
            LocalDate from = force ? today : (last == null ? today : last.plusDays(1));
            List<String> windows = new ArrayList<>();
            int total = 0;
            for (LocalDate end = from; !end.isAfter(today); end = end.plusDays(1)) {
                ZonedDateTime endDt = force
                        ? ZonedDateTime.now(ZONE)
                        : end.atTime(BATCH_END_HOUR, 0).atZone(ZONE);
                int n = processWindow(endDt.minusHours(WINDOW_HOURS), endDt);
                windows.add(end + " → " + n + " 位群友档案更新");
                total += n;
                if (!force) {
                    writeLastBatch(end);
                }
            }
            if (force) {
                writeLastBatch(today);
            }
            log.info("记忆整理完成: {}", windows);
            return Map.of("ok", true, "windows", windows, "updated", total);
        } finally {
            running.set(false);
        }
    }

    public Map<String, Object> batchStatus() {
        LocalDate last = readLastBatch();
        return Map.of(
                "deepseekAvailable", deepSeek.available(),
                "lastBatch", last == null ? "" : last.toString(),
                "memoryDir", memoryRoot.toString());
    }

    /**
     * 整理一个 23 小时窗口内的所有会话。返回更新档案的群友数。
     */
    private int processWindow(ZonedDateTime start, ZonedDateTime end) {
        if (!Files.isDirectory(memoryRoot)) {
            return 0;
        }
        long startMs = start.toInstant().toEpochMilli();
        long endMs = end.toInstant().toEpochMilli();
        int updated = 0;
        try (Stream<Path> dirs = Files.list(memoryRoot)) {
            for (Path ctxDir : dirs.filter(Files::isDirectory).sorted().toList()) {
                try {
                    updated += processCtxWindow(ctxDir, startMs, endMs, start.toLocalDate(), end.toLocalDate());
                } catch (Exception e) {
                    log.warn("会话 {} 整理失败: {}", ctxDir.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("枚举记忆目录失败: {}", e.getMessage());
        }
        return updated;
    }

    private int processCtxWindow(Path ctxDir, long startMs, long endMs, LocalDate fromDay, LocalDate toDay)
            throws IOException {
        // 收集窗口内消息, 按发言者分组 (保持出现顺序)
        Map<String, List<String>> byUser = new LinkedHashMap<>();
        for (LocalDate d = fromDay; !d.isAfter(toDay); d = d.plusDays(1)) {
            Path f = ctxDir.resolve("log-" + d.format(DAY) + ".jsonl");
            if (!Files.exists(f)) {
                continue;
            }
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                try {
                    JsonNode n = mapper.readTree(line);
                    long ts = n.path("ts").asLong(0);
                    if (ts < startMs || ts >= endMs) {
                        continue;
                    }
                    String u = n.path("u").asText("");
                    if (u.isBlank()) {
                        continue;
                    }
                    String t = n.path("t").asText("");
                    String when = t.length() >= 16 ? t.substring(11, 16) : t;
                    byUser.computeIfAbsent(u, k -> new ArrayList<>())
                            .add("[" + when + "] " + n.path("c").asText(""));
                } catch (Exception ignored) {
                    // 跳过无法解析的行
                }
            }
        }
        if (byUser.isEmpty()) {
            return 0;
        }
        // 发言者编号 1..N (与提示词/输出一致)
        List<String> users = new ArrayList<>(byUser.keySet());
        Map<String, Integer> idxOf = new LinkedHashMap<>();
        for (int i = 0; i < users.size(); i++) {
            idxOf.put(users.get(i), i + 1);
        }
        // 只有已有档案的发言者参与增量整理 (新人的基础记忆零成本, 不走模型)
        List<Integer> targets = new ArrayList<>();
        Map<Integer, String> archives = new LinkedHashMap<>();
        for (int i = 0; i < users.size(); i++) {
            Path dir = ctxDir.resolve(sanitize(users.get(i)));
            if (Files.exists(dir.resolve("memory.md"))) {
                targets.add(i + 1);
                archives.put(i + 1, readMemoryBody(dir));
            }
        }
        if (targets.isEmpty()) {
            return 0;
        }
        // 组装提示词
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是 QQ 群 AI「杏目」的记忆整理助手。\n")
                .append("下面是某个会话最近一天的聊天记录 (含私聊), 以及部分发言者现有的记忆档案。\n")
                .append("请为每位在【现有档案】中出现过的发言者, 从聊天记录里提取新增或变化的信息, 只输出「记忆增量」。\n\n")
                .append("增量包含: 称呼/自称、性格与喜好、重要经历与心情、与他人的关系、与杏目的互动或约定、以后聊天值得自然提起的细节。\n")
                .append("不要包含: 寒暄、表情、闲聊、与现有档案重复的内容。\n")
                .append("某位发言者没有值得新增的内容就跳过。\n\n")
                .append("输出格式 (严格遵守):\n")
                .append("--- 发言者1\n")
                .append("- 增量要点1\n")
                .append("- 增量要点2\n\n");
        prompt.append("【聊天记录】\n");
        for (Map.Entry<String, List<String>> e : byUser.entrySet()) {
            String label = "发言者" + idxOf.get(e.getKey());
            for (String m : e.getValue()) {
                prompt.append(label).append(": ").append(m).append("\n");
            }
        }
        prompt.append("\n【现有档案】\n");
        for (Map.Entry<Integer, String> e : archives.entrySet()) {
            prompt.append("--- 发言者").append(e.getKey()).append(" 现有档案\n")
                    .append(e.getValue()).append("\n");
        }
        prompt.append("\n请输出记忆增量:");
        String result;
        try {
            result = deepSeek.chat(List.of(
                    DeepSeekClient.Msg.system("你是杏目的记忆整理助手, 只输出记忆增量, 严格遵守格式要求。"),
                    DeepSeekClient.Msg.user(prompt.toString())), 0.5, 8000);
        } catch (Exception e) {
            log.warn("会话 {} 记忆整理调用失败: {}", ctxDir.getFileName(), e.getMessage());
            return 0;
        }
        if (result == null || result.isBlank()) {
            return 0;
        }
        // 解析分块并本地追加
        Integer currentIdx = null;
        Map<Integer, StringBuilder> blocks = new LinkedHashMap<>();
        for (String line : result.split("\n")) {
            Matcher m = BLOCK.matcher(line);
            if (m.matches()) {
                int idx = Integer.parseInt(m.group(1));
                if (targets.contains(idx)) {
                    currentIdx = idx;
                    blocks.computeIfAbsent(idx, k -> new StringBuilder());
                } else {
                    currentIdx = null;
                }
            } else if (currentIdx != null && !line.isBlank()) {
                blocks.get(currentIdx).append(line).append("\n");
            }
        }
        int updated = 0;
        for (Map.Entry<Integer, StringBuilder> e : blocks.entrySet()) {
            String delta = e.getValue().toString().trim();
            if (delta.isEmpty()) {
                continue;
            }
            try {
                appendMemoryDelta(ctxDir, users.get(e.getKey() - 1), fromDay, delta);
                updated++;
            } catch (Exception ex) {
                log.warn("会话 {} 档案追加失败: {}", ctxDir.getFileName(), ex.getMessage());
            }
        }
        return updated;
    }

    /** 把某天的记忆增量追加到群友档案, 超出上限时从最旧内容开始裁剪 */
    private void appendMemoryDelta(Path ctxDir, String userKey, LocalDate day, String delta) throws IOException {
        Path dir = ctxDir.resolve(sanitize(userKey));
        String body = readMemoryBody(dir);
        String section = "## " + day.format(DAY) + " 更新\n" + delta;
        String newBody = body.isBlank() ? section : body + "\n" + section;
        int max = maxMemoryChars();
        if (newBody.length() > max) {
            newBody = trimOldest(newBody, max);
        }
        String header = "杏目 · 群友记忆档案\n更新时间: " + ZonedDateTime.now(ZONE).format(TS) + "\n---\n";
        Files.writeString(dir.resolve("memory.md"), header + newBody + "\n", StandardCharsets.UTF_8);
        log.info("群友档案已更新: {} (+{} 字)", sanitize(userKey), delta.length());
    }

    /** 从最旧的行开始删, 保留最新的 max 字 */
    private String trimOldest(String body, int max) {
        List<String> lines = new ArrayList<>(List.of(body.split("\n", -1)));
        int total = body.length();
        while (total > max && lines.size() > 1) {
            total -= lines.remove(0).length() + 1;
        }
        return String.join("\n", lines);
    }

    // ---------- 内部 ----------

    /** 只记录「像文字」的消息: 至少 2 字符且含字母/数字/汉字, 排除 [图片] 之类占位 */
    private boolean isWorthRecording(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        if (t.length() < 2) {
            return false;
        }
        if (t.matches("^\\[.+]$")) {
            return false;
        }
        return t.matches(".*[\\p{L}\\p{N}].*");
    }

    private String readMemoryBody(Path dir) throws IOException {
        Path f = dir.resolve("memory.md");
        if (!Files.exists(f)) {
            return "";
        }
        String text = Files.readString(f, StandardCharsets.UTF_8);
        int idx = text.indexOf("---\n");
        return idx >= 0 ? text.substring(idx + 4).trim() : text.trim();
    }

    private List<String> readPending(Path dir) throws IOException {
        Path f = dir.resolve("pending.jsonl");
        if (!Files.exists(f)) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                out.add(line);
            }
        }
        return out;
    }

    private void rewritePending(Path dir, List<String> lines) throws IOException {
        Path f = dir.resolve("pending.jsonl");
        if (lines.isEmpty()) {
            Files.deleteIfExists(f);
            return;
        }
        Files.writeString(f, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    private void writeBaseMemory(Path dir, List<String> lines) throws IOException {
        StringBuilder sb = new StringBuilder("基础记忆 (TA 最初的几条消息):\n");
        for (String line : lines) {
            try {
                JsonNode n = mapper.readTree(line);
                sb.append("- ").append(n.path("t").asText()).append(": ")
                        .append(n.path("c").asText().replace('\n', ' ')).append("\n");
            } catch (Exception ignored) {
                // 跳过无法解析的行
            }
        }
        String header = "杏目 · 群友记忆档案\n更新时间: " + ZonedDateTime.now(ZONE).format(TS) + "\n---\n";
        Files.writeString(dir.resolve("memory.md"), header + sb.toString().trim() + "\n",
                StandardCharsets.UTF_8);
        log.info("已写入基础记忆 {} ({} 条)", dir.getFileName(), lines.size());
    }

    /** 该群友最近 n 条流水消息 (今天+昨天日志) */
    private List<String> readRecentLog(String ctxKey, String userKey, int n) throws IOException {
        Path ctxDir = ctxDir(ctxKey);
        LocalDate today = LocalDate.now(ZONE);
        List<String> out = new ArrayList<>();
        for (LocalDate d = today.minusDays(1); !d.isAfter(today); d = d.plusDays(1)) {
            Path f = ctxDir.resolve("log-" + d.format(DAY) + ".jsonl");
            if (!Files.exists(f)) {
                continue;
            }
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                try {
                    JsonNode node = mapper.readTree(line);
                    if (userKey.equals(node.path("u").asText(""))) {
                        out.add(node.path("t").asText("") + " " + node.path("c").asText(""));
                    }
                } catch (Exception ignored) {
                    // 跳过无法解析的行
                }
            }
        }
        int from = Math.max(0, out.size() - n);
        return new ArrayList<>(out.subList(from, out.size()));
    }

    public Map<String, Object> debugInfo(String ctxKey, String userKey) {
        try {
            Path dir = userDir(ctxKey, userKey);
            String content = readMemoryBody(dir);
            List<String> pending = readPending(dir);
            List<String> recent = readRecentLog(ctxKey, userKey, 5);
            return Map.of("content", content,
                    "archiveChars", content.length(),
                    "pendingCount", pending.size(),
                    "recent", recent);
        } catch (Exception e) {
            return Map.of("content", "", "archiveChars", 0, "pendingCount", 0, "recent", List.of());
        }
    }

    private LocalDate readLastBatch() {
        try {
            if (Files.exists(lastBatchFile)) {
                String s = Files.readString(lastBatchFile, StandardCharsets.UTF_8).trim();
                if (!s.isBlank()) {
                    return LocalDate.parse(s);
                }
            }
        } catch (Exception ignored) {
            // 无标记视为从未整理过
        }
        return null;
    }

    private void writeLastBatch(LocalDate d) {
        try {
            Files.createDirectories(memoryRoot);
            Files.writeString(lastBatchFile, d.toString() + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("写入整理标记失败: {}", e.getMessage());
        }
    }
}
