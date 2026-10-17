package com.linxin.chronobot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linxin.chronobot.chrono.ChronoClient;
import com.linxin.chronobot.chrono.ClubData;
import com.linxin.chronobot.config.BotProperties;
import com.linxin.chronobot.qq.QqHttpApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 命令解析与回复组装
 * <p>
 * 指令 (前缀 cy, 群聊需 @机器人):
 * <ul>
 *   <li>cy绑定社团 &lt;社团名&gt;        新增绑定 (同名不重复添加)</li>
 *   <li>cy解除绑定社团 &lt;名&gt;        解绑 (等价: cy解绑社团 / cy删除社团)</li>
 *   <li>cy查询绑定社团列表              查看本群绑定列表 (等价: cy查询绑定社团 / cy查询社团列表 / cy查询社团)</li>
 *   <li>cy查询社团粉丝 [社团名]         查询绑定社团粉丝 (带参数时只查指定社团)</li>
 *   <li>cy查询&lt;成员名&gt;粉丝         在所有绑定社团中模糊匹配成员 (至少 2 个字符)</li>
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
            "cy查询社团粉丝       查询绑定社团粉丝\n" +
            "cy查询社团粉丝 <名>  查询指定社团\n" +
            "cy查询<成员名>粉丝   查询成员粉丝\n" +
            "帮助                显示本说明";

    /** 每个群最多绑定社团数 */
    private static final int MAX_BINDS = 10;
    /** 成员匹配最多展示人数 (每个社团) */
    private static final int MAX_MATCH_SHOW = 5;

    private static final NumberFormat NF = NumberFormat.getNumberInstance(Locale.CHINA);

    private final QqHttpApi qqApi;
    private final ChronoClient chrono;
    private final BotProperties props;
    private final ObjectMapper mapper;

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

    public BotService(QqHttpApi qqApi, ChronoClient chrono, BotProperties props, ObjectMapper mapper) {
        this.qqApi = qqApi;
        this.chrono = chrono;
        this.props = props;
        this.mapper = mapper;
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
        log.info("收到群消息 group={} msgId={} content={}", groupOpenid, msgId, abbreviate(content));
        executor.execute(() -> {
            String reply = buildReply("g:" + groupOpenid, content);
            if (reply != null) {
                qqApi.replyGroup(groupOpenid, msgId, seq, reply);
            }
        });
    }

    public void onC2cMessage(JsonNode d, int seq) {
        String content = d.path("content").asText("");
        String msgId = d.path("id").asText("");
        String openid = d.path("author").path("user_openid").asText("");
        log.info("收到私聊消息 user={} msgId={} content={}", openid, msgId, abbreviate(content));
        executor.execute(() -> {
            String reply = buildReply("u:" + openid, content);
            if (reply != null) {
                qqApi.replyC2c(openid, msgId, reply);
            }
        });
    }

    /**
     * 组装回复; 无法识别命令时返回 null (不回复)
     *
     * @param ctxKey 上下文键 (g:群openid / u:用户openid)
     */
    public String buildReply(String ctxKey, String raw) {
        String content = normalize(raw);
        if (content.isEmpty()) {
            return null;
        }
        if (content.matches("^(帮助|help|菜单|说明|指令)$")) {
            return USAGE;
        }
        if (!content.toLowerCase(Locale.ROOT).startsWith("cy")) {
            return null; // 非 cy 指令不响应
        }
        String cmd = content.substring(2).trim();

        // cy绑定社团 <名>
        java.util.regex.Matcher bind = java.util.regex.Pattern
                .compile("^绑定社团\\s+(.+)$").matcher(cmd);
        if (bind.matches()) {
            return handleBind(ctxKey, bind.group(1).trim());
        }

        // cy解除绑定社团 <名> / cy解绑社团 <名> / cy删除社团 <名>
        java.util.regex.Matcher unbind = java.util.regex.Pattern
                .compile("^(?:解除绑定社团|解绑社团|删除社团)\\s+(.+)$").matcher(cmd);
        if (unbind.matches()) {
            return handleUnbind(ctxKey, unbind.group(1).trim());
        }

        // cy查询绑定社团列表 / cy查询绑定社团 / cy查询社团列表 / cy查询社团
        if (cmd.matches("^(查询绑定社团列表|查询绑定社团|查询社团列表|查询社团)$")) {
            return handleBindList(ctxKey);
        }

        // cy查询社团粉丝 [名]
        java.util.regex.Matcher club = java.util.regex.Pattern
                .compile("^查询社团粉丝(?:\\s+(.+))?$").matcher(cmd);
        if (club.matches()) {
            return handleClubQuery(ctxKey, club.group(1) == null ? null : club.group(1).trim());
        }

        // cy查询<成员名>粉丝
        java.util.regex.Matcher member = java.util.regex.Pattern
                .compile("^查询(.+?)粉丝$").matcher(cmd);
        if (member.matches()) {
            return handleMemberQuery(ctxKey, member.group(1).trim());
        }

        return "未识别的 cy 指令, 发送「帮助」查看用法";
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

    private String handleClubQuery(String ctxKey, String explicitName) {
        List<String> names;
        if (explicitName != null && !explicitName.isBlank()) {
            names = List.of(resolveAlias(explicitName));
        } else {
            names = bindings.get(ctxKey);
            if (names == null || names.isEmpty()) {
                return "本会话尚未绑定社团\n请先发送: cy绑定社团 <社团名>";
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            try {
                ClubData d = chrono.queryClub(name);
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(formatClub(d));
            } catch (ChronoClient.ClubNotFoundException e) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append("未找到社团: ").append(name);
            } catch (Exception e) {
                log.error("查询失败: {}", name, e);
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(name).append(" 查询失败: ").append(e.getMessage());
            }
        }
        return sb.toString();
    }

    private String handleMemberQuery(String ctxKey, String kw) {
        if (kw.length() < 2) {
            return "成员名至少需要 2 个字符, 请重新输入";
        }
        List<String> names = bindings.get(ctxKey);
        if (names == null || names.isEmpty()) {
            return "本会话尚未绑定社团\n请先发送: cy绑定社团 <社团名>";
        }
        StringBuilder sb = new StringBuilder();
        List<String> searched = new ArrayList<>();
        for (String name : names) {
            try {
                ClubData d = chrono.queryClub(name);
                searched.add(d.name());
                List<ClubData.MemberData> matched = matchMembers(d, kw);
                if (matched.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(formatClub(d)).append("\n");
                sb.append("成员「").append(kw).append("」匹配 ").append(matched.size()).append(" 人:\n");
                int shown = Math.min(matched.size(), MAX_MATCH_SHOW);
                for (int i = 0; i < shown; i++) {
                    if (i > 0) {
                        sb.append("\n");
                    }
                    sb.append(formatMember(matched.get(i)));
                }
                if (matched.size() > shown) {
                    sb.append("\n(仅显示前 ").append(shown).append(" 人)");
                }
            } catch (Exception e) {
                log.error("成员查询失败: {}", name, e);
            }
        }
        if (sb.length() == 0) {
            sb.append("未在绑定社团 (").append(String.join("、", searched))
                    .append(") 中找到名字包含「").append(kw).append("」的成员");
        }
        return sb.toString();
    }

    /**
     * 模糊匹配成员: 名字或曾用名包含关键字 (不区分大小写)
     */
    private List<ClubData.MemberData> matchMembers(ClubData d, String kw) {
        String lower = kw.toLowerCase(Locale.ROOT);
        List<ClubData.MemberData> out = new ArrayList<>();
        for (ClubData.MemberData m : d.members()) {
            boolean hit = m.name() != null && m.name().toLowerCase(Locale.ROOT).contains(lower);
            if (!hit && m.names() != null) {
                for (String n : m.names()) {
                    if (n != null && n.toLowerCase(Locale.ROOT).contains(lower)) {
                        hit = true;
                        break;
                    }
                }
            }
            if (hit) {
                out.add(m);
            }
        }
        return out;
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

    private String formatClub(ClubData d) {
        String diff = d.dailyDiff() >= 0 ? "+" + NF.format(d.dailyDiff()) : NF.format(d.dailyDiff());
        String updated = d.updatedAt();
        if (updated != null && updated.length() >= 16) {
            updated = updated.substring(0, 16).replace('T', ' ');
        }
        return d.name() + "\n" +
                "当日粉丝: " + NF.format(d.fanCount()) + " (" + diff + ")\n" +
                "当月日均粉丝: " + NF.format(d.dailyAverage()) + "\n" +
                "当月总粉丝: " + NF.format(d.monthlyAverage()) + "\n" +
                "排名: #" + d.rank() + " (成员 " + d.memberNum() + ")\n" +
                "数据更新: " + updated;
    }

    private String formatMember(ClubData.MemberData m) {
        String diff = m.dailyDiff() >= 0 ? "+" + NF.format(m.dailyDiff()) : NF.format(m.dailyDiff());
        String updated = m.updatedAt();
        if (updated != null && updated.length() >= 16) {
            updated = updated.substring(0, 16).replace('T', ' ');
        }
        String role = switch (m.membership()) {
            case 3 -> " [社长]";
            case 2 -> " [副社]";
            default -> "";
        };
        return m.name() + role + "\n" +
                "当日粉丝: " + NF.format(m.fanCount()) + " (" + diff + ")\n" +
                "当月日均粉丝: " + NF.format(m.dailyAverage()) + "\n" +
                "当月总粉丝: " + NF.format(m.monthlyAverage()) + "\n" +
                "数据更新: " + updated;
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
