package com.linxin.chronobot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linxin.chronobot.chat.DeepSeekClient;
import com.linxin.chronobot.chat.MemoryStore;
import com.linxin.chronobot.chrono.ChronoClient;
import com.linxin.chronobot.chrono.ClubData;
import com.linxin.chronobot.config.BotProperties;
import com.linxin.chronobot.qq.QqHttpApi;
import com.linxin.chronobot.util.TableRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 命令解析与回复组装
 * <p>
 * 指令 (前缀 cy, 群聊需 @机器人):
 * <ul>
 *   <li>cy绑定社团 &lt;社团名&gt;        新增绑定 (同名不重复添加)</li>
 *   <li>cy解除绑定社团 &lt;名&gt;        解绑 (等价: cy解绑社团 / cy删除社团)</li>
 *   <li>cy查询绑定社团列表              查看本群绑定列表 (等价: cy查询绑定社团 / cy查询社团列表 / cy查询社团)</li>
 *   <li>cy查询社团粉丝 [社团名]         查询社团粉丝报表 (生成表格图片, 带参数时只查指定社团)</li>
 * </ul>
 * 绑定按群/会话隔离, 持久化到 bindings.json。
 */
@Service
public class BotService {

    private static final Logger log = LoggerFactory.getLogger(BotService.class);

    private static final String USAGE =
            "cy 指令说明:\n" +
            "cy绑定社团 <社团名>    新增绑定 (可绑多个)\n" +
            "cy解绑社团 <社团名>    解除绑定\n" +
            "cy查询绑定社团列表    查看本群绑定\n" +
            "cy查询社团粉丝       查询绑定社团粉丝报表 (表格图)\n" +
            "cy查询社团粉丝 <名>  查询指定社团报表 (表格图)\n" +
            "帮助                显示本说明\n\n" +
            "聊天: 群里喊「杏目」、问句、早晚问候等她都可能接话; @和私聊必回";

    /** 每个群最多绑定社团数 */
    private static final int MAX_BINDS = 10;

    private static final NumberFormat NF = NumberFormat.getNumberInstance(Locale.CHINA);

    private final QqHttpApi qqApi;
    private final ChronoClient chrono;
    private final BotProperties props;
    private final ObjectMapper mapper;
    private final DeepSeekClient deepSeek;
    private final MemoryStore memoryStore;
    private final ClubHistoryStore historyStore;

    /** 杏目人设 (persona.md, 优先工作目录同名文件) */
    private String persona = "你是杏目, 一个元气开朗的QQ群AI助手。";

    /** 全量消息触发的聊天冷却: 每人两次回复最小间隔 */
    private static final long CHAT_COOLDOWN_MS = 20_000L;
    private final Map<String, Long> lastChatAt = new ConcurrentHashMap<>();

    /** 最近对话轮次 (内存态, 保证上下文连贯; 每轮 user+assistant 两条) */
    private static final int MAX_RECENT_TURNS = 10;
    private final Map<String, Deque<DeepSeekClient.Msg>> recentTurns = new ConcurrentHashMap<>();

    /** 每会话最近消息缓冲 (接话时带 TA 说话前的最近 10 条上下文) */
    private static final int GROUP_CONTEXT_SIZE = 10;
    private static final int GROUP_BUFFER_CAP = 30;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private final Map<String, Deque<String>> groupBuffer = new ConcurrentHashMap<>();

    /** 每会话发言者编号 (openid -> 群友N), 上下文里用于区分谁说了什么 */
    private final Map<String, Map<String, String>> speakerLabels = new ConcurrentHashMap<>();

    /** 每群接话冷却 (防止连续刷屏) */
    private final Map<String, Long> lastGroupChatAt = new ConcurrentHashMap<>();

    /** 已处理消息去重 (全量模式下同一条 @ 消息可能同时触发 @ 事件与全量事件) */
    private final Set<String> processedMsgIds = ConcurrentHashMap.newKeySet();

    /** 接话打分信号 */
    private static final Pattern NICK = Pattern.compile("杏目|almond|アーモンド", Pattern.CASE_INSENSITIVE);
    private static final Pattern GREETING = Pattern.compile(
            "^(大家|各位)?[,\\s，]*?(早上好|早安|中午好|午安|下午好|晚上好|晚安|早啊|早|你好呀|哈喽|嗨)+[!！~～\\s、,，。.]*$");
    private static final Pattern EMOTION = Pattern.compile(
            "(哈哈|嘿嘿|嘻嘻|呜呜|好气|气死|崩溃|太难了|好难过|开心死|太好了|太棒|赢啦|输了|离谱|绝了|破防|泪目)");

    /** 别名 -> 正式社团名 */
    private final Map<String, String> aliasMap = new ConcurrentHashMap<>();

    /** 绑定表: "g:<群openid>" 或 "u:<用户openid>" -> 社团名列表 (有序, 去重) */
    private final Map<String, List<String>> bindings = new ConcurrentHashMap<>();

    private final Path bindingFile;

    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "bot-reply");
        t.setDaemon(true);
        return t;
    });

    public BotService(QqHttpApi qqApi, ChronoClient chrono, BotProperties props, ObjectMapper mapper,
                      DeepSeekClient deepSeek, MemoryStore memoryStore, ClubHistoryStore historyStore) {
        this.qqApi = qqApi;
        this.chrono = chrono;
        this.props = props;
        this.mapper = mapper;
        this.deepSeek = deepSeek;
        this.memoryStore = memoryStore;
        this.historyStore = historyStore;
        this.persona = loadPersona();
        if (props.alias() != null) {
            for (String entry : props.alias()) {
                int idx = entry.indexOf('=');
                if (idx > 0) {
                    aliasMap.put(entry.substring(0, idx).trim(), entry.substring(idx + 1).trim());
                }
            }
        }
        this.bindingFile = Path.of(props.bindingFile() == null ? "bindings.json" : props.bindingFile());
        loadBindings();
    }

    public void onGroupAtMessage(JsonNode d, int seq) {
        String content = d.path("content").asText("");
        String msgId = d.path("id").asText("");
        String groupOpenid = d.path("group_openid").asText("");
        String memberOpenid = d.path("author").path("member_openid").asText("");
        if (!markProcessed(msgId)) {
            return;
        }
        log.info("收到群消息 group={} msgId={} content={}", groupOpenid, msgId, abbreviate(content));
        recordMessage("g:" + groupOpenid, memberOpenid, content);
        Ctx ctx = recordGroupMessage("g:" + groupOpenid, memberOpenid, content);
        executor.execute(() -> {
            CmdReply reply = buildReply("g:" + groupOpenid, content);
            if (reply == null) {
                String chat = chatReply("g:" + groupOpenid, memberOpenid, content, true, ctx.lines(), ctx.speakerLabel());
                if (chat != null) {
                    qqApi.replyGroup(groupOpenid, msgId, seq, chat);
                }
                return;
            }
            sendCmdReply(reply, groupOpenid, msgId, seq, true);
        });
    }

    /**
     * 全量群消息事件 (GROUP_MESSAGE_CREATE), 平台开启「接收所有消息」后推送。
     * 每条消息都会记入群友记忆与群聊上下文缓冲; 接话按多信号打分决定,
     * @/喊「杏目」名字视为强制回复。
     */
    public void onGroupAllMessage(JsonNode d, int seq) {
        String content = d.path("content").asText("");
        String msgId = d.path("id").asText("");
        String groupOpenid = d.path("group_openid").asText("");
        String memberOpenid = d.path("author").path("member_openid").asText("");
        if (!markProcessed(msgId)) {
            return;
        }
        log.info("收到全量群消息(未@) group={} msgId={} content={}", groupOpenid, msgId, abbreviate(content));
        recordMessage("g:" + groupOpenid, memberOpenid, content);
        Ctx ctx = recordGroupMessage("g:" + groupOpenid, memberOpenid, content);
        boolean forced = isMention(content);
        executor.execute(() -> {
            String reply = chatReply("g:" + groupOpenid, memberOpenid, content, forced, ctx.lines(), ctx.speakerLabel());
            if (reply != null) {
                qqApi.replyGroup(groupOpenid, msgId, seq, reply);
            }
        });
    }

    public void onC2cMessage(JsonNode d, int seq) {
        String content = d.path("content").asText("");
        String msgId = d.path("id").asText("");
        String openid = d.path("author").path("user_openid").asText("");
        if (!markProcessed(msgId)) {
            return;
        }
        log.info("收到私聊消息 user={} msgId={} content={}", openid, msgId, abbreviate(content));
        recordMessage("u:" + openid, openid, content);
        Ctx ctx = recordGroupMessage("u:" + openid, openid, content);
        executor.execute(() -> {
            CmdReply reply = buildReply("u:" + openid, content);
            if (reply == null) {
                String chat = chatReply("u:" + openid, openid, content, true, ctx.lines(), ctx.speakerLabel());
                if (chat != null) {
                    qqApi.replyC2c(openid, msgId, chat);
                }
                return;
            }
            sendCmdReply(reply, openid, msgId, seq, false);
        });
    }

    /** 发送指令回复: 先文本, 再逐张上传并发送图片 (msg_type=7)。
     *  对同一条消息的多次被动回复必须用不同的 msg_seq (QQ 去重校验), 故逐条递增。 */
    private void sendCmdReply(CmdReply reply, String peerOpenid, String msgId, int seq, boolean group) {
        int offset = 0;
        if (reply.text() != null && !reply.text().isBlank()) {
            if (group) {
                qqApi.replyGroup(peerOpenid, msgId, seq + offset, reply.text());
            } else {
                qqApi.replyC2c(peerOpenid, msgId, reply.text());
            }
            offset++;
        }
        for (Path p : reply.images()) {
            try {
                String fi = group ? qqApi.uploadGroupImage(peerOpenid, p) : qqApi.uploadC2cImage(peerOpenid, p);
                if (group) {
                    qqApi.sendGroupMedia(peerOpenid, msgId, seq + offset, fi);
                } else {
                    qqApi.sendC2cMedia(peerOpenid, msgId, fi);
                }
                offset++;
            } catch (Exception e) {
                log.error("发送报表图片失败: {}", e.getMessage());
            }
        }
    }

    /** 记录群友消息到记忆库 (压缩任务在后台线程串行执行) */
    private void recordMessage(String ctxKey, String userKey, String content) {
        if (userKey == null || userKey.isBlank() || content == null || content.isBlank()) {
            return;
        }
        try {
            memoryStore.record(ctxKey, userKey, content);
        } catch (Exception e) {
            log.warn("记忆记录失败: {}", e.getMessage());
        }
    }

    /**
     * 聊天回复。
     *
     * @param forced       true=@/私聊/喊名字必回; false=按接话信号打分决定 (且受冷却限制)
     * @param groupContext TA 说话前的最近会话上下文 (可为空)
     */
    private String chatReply(String ctxKey, String userKey, String content, boolean forced,
                             List<String> groupContext, String speakerLabel) {
        if (props.chat() == null || !props.chat().enabled()) {
            return null;
        }
        String c = normalize(content);
        if (c.isEmpty() || c.toLowerCase(Locale.ROOT).startsWith("cy")) {
            return null;
        }
        if (!deepSeek.available()) {
            return null;
        }
        if (!forced) {
            long now = System.currentTimeMillis();
            Long last = lastChatAt.get(userKey);
            if (last != null && now - last < CHAT_COOLDOWN_MS) {
                return null;
            }
            Long lastG = lastGroupChatAt.get(ctxKey);
            if (lastG != null && now - lastG < groupCooldownMs()) {
                return null;
            }
            if (!shouldChimeIn(c)) {
                return null;
            }
            lastChatAt.put(userKey, now);
            lastGroupChatAt.put(ctxKey, now);
        }
        try {
            MemoryStore.Memory mem = memoryStore.load(ctxKey, userKey);
            List<DeepSeekClient.Msg> msgs = new ArrayList<>();
            StringBuilder sys = new StringBuilder(persona);
            if (mem.content() == null || mem.content().isBlank()) {
                sys.append("\n\n你还不认识这位群友, 暂时没有 TA 的记忆档案。正常聊天就好, 别每次硬打招呼。");
            } else {
                sys.append("\n\n以下是杏目对这位群友的记忆档案, 聊天时自然地引用其中的信息, 不要机械复述:\n")
                        .append(mem.content());
            }
            boolean hasCtx = groupContext != null && !groupContext.isEmpty();
            if (hasCtx) {
                sys.append("\n\n用户消息里会附带【群聊上下文】, 其中「群友N」是不同的群友, 最后那句【TA刚刚说】才是正在和你说话的人, 旁边会标明 TA 在上下文里的编号。编号和 TA 相同的那些话也都是 TA 自己之前说的, 别把 TA 的旧消息当成别人。上下文只用来理解 TA 在接谁的话、在聊什么, 你的回复只针对 TA 最后那句话。特别注意: TA 的这句话可能是在问别的群友, 不是问你——比如 群友1 说去看了比赛、TA 追问『哪一场』, 那是 TA 在问 群友1, 你插话时别把 TA 当成那个去看比赛的人, 也别替别人回答问题。搞不清 TA 在对谁说时, 就顺着话题轻轻接一句, 不要做具体假设。");
            }
            msgs.add(DeepSeekClient.Msg.system(sys.toString()));
            Deque<DeepSeekClient.Msg> turns = recentTurns.computeIfAbsent(ctxKey + "|" + userKey, k -> new ArrayDeque<>());
            synchronized (turns) {
                msgs.addAll(turns);
            }
            if (hasCtx) {
                String labelHint = (speakerLabel == null || speakerLabel.isBlank())
                        ? "" : " (上下文里的 " + speakerLabel + ")";
                msgs.add(DeepSeekClient.Msg.user("【TA说这句之前, 会话里最近的对话】\n"
                        + String.join("\n", groupContext) + "\n\n【TA刚刚说" + labelHint + "】\n" + c));
            } else {
                msgs.add(DeepSeekClient.Msg.user(c));
            }
            String reply = deepSeek.chat(msgs, 0.9, 400);
            if (reply == null || reply.isBlank()) {
                return null;
            }
            reply = truncate(stripStageDirections(reply), 500);
            if (reply.isBlank()) {
                return null;
            }
            synchronized (turns) {
                turns.addLast(DeepSeekClient.Msg.user(c));
                turns.addLast(DeepSeekClient.Msg.assistant(reply));
                while (turns.size() > MAX_RECENT_TURNS * 2) {
                    turns.removeFirst();
                }
            }
            return reply;
        } catch (Exception e) {
            log.warn("聊天调用失败: {}", e.getMessage());
            return null;
        }
    }

    /** 消息是否以「吗」或「？/?」结尾 (允许末尾有空白/表情符号) */
    private boolean isQuestion(String s) {
        String t = s.replaceAll("[\\s。.!！~～…、,，]+$", "");
        if (t.isEmpty()) {
            return false;
        }
        char last = t.charAt(t.length() - 1);
        return last == '？' || last == '?' || last == '吗';
    }

    /** 全量消息接话判定: 多信号打分 + 小概率随机插话 */
    private boolean shouldChimeIn(String c) {
        int score = scoreMessage(c);
        if (ThreadLocalRandom.current().nextInt(100) < triggerRandomPercent()) {
            score += 3;
        }
        return score >= triggerMinScore();
    }

    /** 接话信号打分 (不含随机), 供判定与调试共用 */
    private int scoreMessage(String c) {
        int score = 0;
        if (isQuestion(c)) {
            score += 3;
        }
        if (NICK.matcher(c).find()) {
            score += 5;
        }
        if (GREETING.matcher(c).matches()) {
            score += 3;
        }
        if (EMOTION.matcher(c).find()) {
            score += 1;
        }
        if (c.length() > 40) {
            score += 1;
        }
        return score;
    }

    private int triggerMinScore() {
        return props.chat() == null || props.chat().trigger() == null
                || props.chat().trigger().minScore() <= 0 ? 3 : props.chat().trigger().minScore();
    }

    private int triggerRandomPercent() {
        return props.chat() == null || props.chat().trigger() == null
                ? 3 : props.chat().trigger().randomPercent();
    }

    private long groupCooldownMs() {
        return props.chat() == null || props.chat().trigger() == null
                || props.chat().trigger().groupCooldownSeconds() <= 0
                ? 90_000L : props.chat().trigger().groupCooldownSeconds() * 1000L;
    }

    /** 消息去重 (全量模式下同一条 @ 消息可能同时触发 @ 事件与全量事件), 返回是否首次处理 */
    private boolean markProcessed(String msgId) {
        if (msgId == null || msgId.isBlank()) {
            return true; // 无 ID 不拦截 (调试场景)
        }
        boolean added = processedMsgIds.add(msgId);
        if (processedMsgIds.size() > 1000) {
            processedMsgIds.clear();
        }
        return added;
    }

    /** 原文是否 @ 了机器人或喊了杏目名字 */
    private boolean isMention(String raw) {
        if (raw == null) {
            return false;
        }
        return raw.matches("(?s).*<@!?\\d+>.*") || raw.contains("@杏目");
    }

    /**
     * 把消息写入会话缓冲区, 返回 TA 说话前的最近 GROUP_CONTEXT_SIZE 条上下文 (带发言者编号) 与 TA 本人的编号
     */
    private Ctx recordGroupMessage(String ctxKey, String speaker, String content) {
        String c = normalize(content);
        if (c.isEmpty()) {
            return new Ctx(List.of(), null);
        }
        String label = null;
        if (speaker != null && !speaker.isBlank()) {
            Map<String, String> labels = speakerLabels.computeIfAbsent(ctxKey, k -> new ConcurrentHashMap<>());
            label = labels.computeIfAbsent(speaker, s -> "群友" + (labels.size() + 1));
        }
        String entry = "[" + LocalTime.now(ZONE).format(HM) + "] "
                + (label == null ? "" : label + ": ") + truncate(c, 80);
        Deque<String> q = groupBuffer.computeIfAbsent(ctxKey, k -> new ArrayDeque<>());
        synchronized (q) {
            List<String> all = new ArrayList<>(q);
            int from = Math.max(0, all.size() - GROUP_CONTEXT_SIZE);
            List<String> prev = new ArrayList<>(all.subList(from, all.size()));
            q.addLast(entry);
            while (q.size() > GROUP_BUFFER_CAP) {
                q.removeFirst();
            }
            return new Ctx(prev, label);
        }
    }

    /** 群聊上下文快照: lines=TA 说话前的最近对话, speakerLabel=TA 在上下文里的编号 */
    private record Ctx(List<String> lines, String speakerLabel) {
    }

    /** 调试用: 查看某条消息的接话打分 */
    public Map<String, Object> shouldForDebug(String ctxKey, String text) {
        String c = normalize(text);
        int score = scoreMessage(c);
        return Map.of("text", c, "score", score, "minScore", triggerMinScore(),
                "randomPercent", triggerRandomPercent(),
                "wouldReply", score >= triggerMinScore());
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 去除模型偶尔漏出的整行括号内容 (舞台指示/内心独白), 只删开头连续的括号行 */
    private String stripStageDirections(String s) {
        String r = s == null ? "" : s.strip();
        boolean changed;
        do {
            changed = false;
            if (r.startsWith("（") || r.startsWith("(")) {
                int nl = r.indexOf('\n');
                String firstLine = nl < 0 ? r : r.substring(0, nl);
                if (firstLine.endsWith("）") || firstLine.endsWith(")")) {
                    r = nl < 0 ? "" : r.substring(nl + 1).strip();
                    changed = true;
                }
            }
        } while (changed);
        return r.strip();
    }

    /** 调试用: 强制聊天 (绕过触发条件与冷却), 供 /chat 端点调用 */
    public String chatForDebug(String ctxKey, String userKey, String text) {
        String c = normalize(text);
        CmdReply cy = buildReply(ctxKey, c);
        if (cy != null) {
            StringBuilder sb = new StringBuilder(cy.text() == null ? "" : cy.text());
            for (Path p : cy.images()) {
                sb.append("\n[图片] ").append(p.toAbsolutePath());
            }
            return sb.toString().strip();
        }
        recordMessage(ctxKey, userKey, c);
        Ctx ctx = recordGroupMessage(ctxKey, userKey, c);
        return chatReply(ctxKey, userKey, text, true, ctx.lines(), ctx.speakerLabel());
    }

    /** 调试用: 查看某群友的记忆档案 */
    public Map<String, Object> memoryForDebug(String ctxKey, String userKey) {
        return memoryStore.debugInfo(ctxKey, userKey);
    }

    private String loadPersona() {
        String name = (props.chat() == null || props.chat().personaFile() == null || props.chat().personaFile().isBlank())
                ? "persona.md" : props.chat().personaFile();
        try {
            Path ext = Path.of(name);
            if (Files.exists(ext)) {
                return Files.readString(ext, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // 继续尝试 jar 内置资源
        }
        try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("读取人设文件失败: {}", e.getMessage());
        }
        return "你是杏目, 一个元气开朗的QQ群AI助手。";
    }

    /**
     * 组装回复; 无法识别命令时返回 null (不回复)
     *
     * @param ctxKey 上下文键 (g:群openid / u:用户openid)
     */
    public CmdReply buildReply(String ctxKey, String raw) {
        String content = normalize(raw);
        if (content.isEmpty()) {
            return null;
        }
        if (content.matches("^(帮助|help|菜单|说明|指令)$")) {
            return CmdReply.text(USAGE);
        }
        if (!content.toLowerCase(Locale.ROOT).startsWith("cy")) {
            return null; // 非 cy 指令不响应
        }
        String cmd = content.substring(2).trim();

        // cy绑定社团 <名>
        java.util.regex.Matcher bind = java.util.regex.Pattern
                .compile("^绑定社团\\s+(.+)$").matcher(cmd);
        if (bind.matches()) {
            return CmdReply.text(handleBind(ctxKey, bind.group(1).trim()));
        }

        // cy解除绑定社团 <名> / cy解绑社团 <名> / cy删除社团 <名>
        java.util.regex.Matcher unbind = java.util.regex.Pattern
                .compile("^(?:解除绑定社团|解绑社团|删除社团)\\s+(.+)$").matcher(cmd);
        if (unbind.matches()) {
            return CmdReply.text(handleUnbind(ctxKey, unbind.group(1).trim()));
        }

        // cy查询绑定社团列表 / cy查询绑定社团 / cy查询社团列表 / cy查询社团
        if (cmd.matches("^(查询绑定社团列表|查询绑定社团|查询社团列表|查询社团)$")) {
            return CmdReply.text(handleBindList(ctxKey));
        }

        // cy查询社团粉丝 [名]
        java.util.regex.Matcher club = java.util.regex.Pattern
                .compile("^查询社团粉丝(?:\\s+(.+))?$").matcher(cmd);
        if (club.matches()) {
            return handleClubQuery(ctxKey, club.group(1) == null ? null : club.group(1).trim());
        }

        return CmdReply.text("未识别的 cy 指令, 发送「帮助」查看用法");
    }

    private String handleBind(String ctxKey, String rawName) {
        String name = resolveAlias(rawName);
        try {
            ClubData d = chrono.queryClub(name);
            List<String> list = bindings.computeIfAbsent(ctxKey, k -> new CopyOnWriteArrayList<>());
            synchronized (list) {
                if (list.size() >= MAX_BINDS) {
                    return "绑定数量已达上限 (" + MAX_BINDS + " 个), 请先解绑不用的社团";
                }
                if (list.contains(d.name())) {
                    return "社团「" + d.name() + "」已在本会话绑定过, 无需重复绑定\n"
                            + "当前绑定: " + formatBindList(list);
                }
                list.add(d.name());
            }
            saveBindings();
            return "已新增绑定「" + d.name() + "」(成员 " + d.memberNum() + ")\n"
                    + "当前绑定: " + formatBindList(list);
        } catch (ChronoClient.ClubNotFoundException e) {
            return "未找到社团「" + rawName + "」, 请检查名称后重试";
        } catch (Exception e) {
            log.error("绑定社团失败", e);
            return "绑定失败: " + e.getMessage();
        }
    }

    private String handleUnbind(String ctxKey, String rawName) {
        String name = resolveAlias(rawName);
        List<String> list = bindings.get(ctxKey);
        if (list == null || list.isEmpty()) {
            return "本会话还没有绑定任何社团";
        }
        synchronized (list) {
            if (!list.contains(name)) {
                return "社团「" + rawName + "」不在本会话绑定列表中\n"
                        + "当前绑定: " + formatBindList(list);
            }
            list.remove(name);
        }
        saveBindings();
        if (list.isEmpty()) {
            return "已解绑「" + name + "」, 本会话当前无绑定社团";
        }
        return "已解绑「" + name + "」\n当前绑定: " + formatBindList(list);
    }

    private String handleBindList(String ctxKey) {
        List<String> list = bindings.get(ctxKey);
        if (list == null || list.isEmpty()) {
            return "本会话尚未绑定社团\n请先发送: cy绑定社团 <社团名>";
        }
        return "本会话已绑定社团:\n" + formatBindList(list);
    }

    private CmdReply handleClubQuery(String ctxKey, String explicitName) {
        List<String> names;
        if (explicitName != null && !explicitName.isBlank()) {
            names = List.of(resolveAlias(explicitName));
        } else {
            names = bindings.get(ctxKey);
            if (names == null || names.isEmpty()) {
                return CmdReply.text("本会话尚未绑定社团\n请先发送: cy绑定社团 <社团名>");
            }
        }
        List<TableRenderer.Data> datas = new ArrayList<>();
        StringBuilder errs = new StringBuilder();
        for (String name : names) {
            try {
                ClubData d = chrono.queryClub(name);
                List<ChronoClient.MonthData> hist = historyStore.ensureHistory(chrono, d);
                datas.add(buildTableData(d, hist));
            } catch (ChronoClient.ClubNotFoundException e) {
                if (errs.length() > 0) {
                    errs.append("\n");
                }
                errs.append("未找到社团: ").append(name);
            } catch (Exception e) {
                log.error("社团报表生成失败: {}", name, e);
                if (errs.length() > 0) {
                    errs.append("\n");
                }
                errs.append(name).append(" 查询失败: ").append(e.getMessage());
            }
        }
        if (datas.isEmpty()) {
            return CmdReply.text(errs.toString());
        }
        try {
            Path png = TableRenderer.renderAll(datas);
            return new CmdReply(null, List.of(png));
        } catch (Exception e) {
            log.error("渲染报表失败", e);
            return CmdReply.text("报表生成失败: " + e.getMessage());
        }
    }

    /** 调试/种子用: 查询社团并生成报表图片 (同时补录月度记忆) */
    public Path renderClubImage(String name) throws Exception {
        ClubData d = chrono.queryClub(resolveAlias(name));
        List<ChronoClient.MonthData> hist = historyStore.ensureHistory(chrono, d);
        return TableRenderer.render(buildTableData(d, hist));
    }

    /**
     * 组装表格数据: 当月实时数据 (club_profile) + 最近 5 个完整月月末粉丝 (记忆)。
     */
    private TableRenderer.Data buildTableData(ClubData d, List<ChronoClient.MonthData> histMonths) {
        ClubHistoryStore.Target t = historyStore.target(d.name());
        String targetLabel = t.exists()
                ? (t.daily() ? "日目标 " + TableRenderer.compact(t.value())
                             : "月目标 " + TableRenderer.compact(t.value()))
                : "";

        List<String> labels = new ArrayList<>();
        List<Map<Long, Long>> histGain = new ArrayList<>();
        for (ChronoClient.MonthData md : histMonths) {
            int m = Integer.parseInt(md.sdate().substring(5, 7));
            labels.add(m + "月增量");
            Map<Long, Long> g = new HashMap<>();
            for (ChronoClient.MonthMember mm : md.members()) {
                g.put(mm.viewerId(), mm.gain());
            }
            histGain.add(g);
        }

        List<TableRenderer.Row> rows = new ArrayList<>();
        for (ClubData.MemberData m : d.members()) {
            Double rate = null;
            if (t.exists()) {
                rate = t.daily() ? (double) m.dailyAverage() / t.value()
                        : (double) m.monthlyDiff() / t.value();
            }
            List<Long> gains = new ArrayList<>();
            for (Map<Long, Long> g : histGain) {
                gains.add(g.get(m.viewerId()));
            }
            String role = m.membership() == 3 ? " (社)" : m.membership() == 2 ? " (副)" : "";
            rows.add(new TableRenderer.Row(
                    m.name() + role,
                    m.fanCount() - m.monthlyDiff(),
                    m.fanCount(),
                    m.monthlyDiff(),
                    m.dailyAverage(),
                    m.lastMonth(),
                    rate,
                    gains));
        }
        rows.sort((a, b) -> {
            if (a.rate() != null && b.rate() != null) {
                return Double.compare(b.rate(), a.rate());
            }
            if (a.rate() != null) {
                return -1;
            }
            if (b.rate() != null) {
                return 1;
            }
            return Long.compare(b.currentFan(), a.currentFan());
        });

        String updated = d.updatedAt();
        if (updated != null && updated.length() >= 16) {
            updated = updated.substring(0, 16).replace('T', ' ');
        }
        return new TableRenderer.Data(d.name(), targetLabel, d.fanCount(), d.dailyAverage(),
                d.monthlyAverage(), d.rank(), updated, labels, rows);
    }

    /**
     * 去掉 @提及, 归一化空白
     */
    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String c = raw.replaceAll("<@!?\\d+>", " ").trim();
        c = c.replaceAll("^(@\\S+\\s*)+", "").trim();
        return c;
    }

    private String resolveAlias(String name) {
        String mapped = aliasMap.get(name);
        return mapped != null ? mapped : name;
    }

    private String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replace('\n', ' ');
        return one.length() <= 60 ? one : one.substring(0, 60) + "...";
    }

    private String formatBindList(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append(i + 1).append(". ").append(list.get(i));
        }
        return sb.toString();
    }

    /** 指令回复: 文本 + 可选的报表图片 */
    public record CmdReply(String text, List<Path> images) {
        public static CmdReply text(String t) {
            return new CmdReply(t, List.of());
        }
    }

    // ---------- 绑定持久化 ----------

    private void loadBindings() {
        try {
            if (!Files.exists(bindingFile)) {
                return;
            }
            JsonNode root = mapper.readTree(Files.readString(bindingFile, StandardCharsets.UTF_8));
            root.fields().forEachRemaining(e -> {
                List<String> list = new CopyOnWriteArrayList<>();
                JsonNode v = e.getValue();
                if (v.isTextual()) { // 兼容旧版单值格式
                    list.add(v.asText());
                } else if (v.isArray()) {
                    for (JsonNode n : v) {
                        if (n.isTextual() && !n.asText().isBlank()) {
                            list.add(n.asText());
                        }
                    }
                }
                if (!list.isEmpty()) {
                    bindings.put(e.getKey(), list);
                }
            });
            log.info("已加载绑定记录: {} 个会话, 文件 {}", bindings.size(), bindingFile);
        } catch (Exception e) {
            log.warn("加载绑定记录失败, 使用空绑定表: {}", e.getMessage());
        }
    }

    private void saveBindings() {
        try {
            Path parent = bindingFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(bindingFile,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(bindings),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("保存绑定记录失败: {}", e.getMessage());
        }
    }
}
