package com.linxin.chronobot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linxin.chronobot.chrono.ChronoClient;
import com.linxin.chronobot.chrono.ClubData;
import com.linxin.chronobot.config.BotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 社团粉丝月度记忆存储。
 * <p>
 * 每月月底每位成员的粉丝数持久化到本地 JSON (club-fans-history.json),
 * 后续查询沿记忆展示最近六个月每位成员的粉丝变化。
 * <p>
 * 数据来源: 站点 club_data_by_month 接口 (支持最近 6 个月), 首次查询某月时补录, 之后只读本地。
 */
@Component
public class ClubHistoryStore {

    private static final Logger log = LoggerFactory.getLogger(ClubHistoryStore.class);

    /** 回溯最近 5 个完整月, 加当月 = 6 个月 */
    public static final int HISTORY_MONTHS = 5;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 目标定义: daily=true 按日均计算完成率, 否则按月度总量 */
    public record Target(long value, boolean daily) {
        public boolean exists() {
            return value > 0;
        }
    }

    /** 预置目标: 大秦月目标 3000 万, 白猫日均目标 150 万 */
    private static final Map<String, Target> DEFAULT_TARGETS = Map.of(
            "大秦仙朝！！！", new Target(30_000_000L, false),
            "白猫海賊船", new Target(1_500_000L, true)
    );

    private final ObjectMapper mapper;
    private final Path file;
    private final ObjectNode root;

    public ClubHistoryStore(ObjectMapper mapper, BotProperties props) {
        this.mapper = mapper;
        this.file = Path.of(props.historyFile() == null || props.historyFile().isBlank()
                ? "club-fans-history.json" : props.historyFile());
        this.root = load();
    }

    private ObjectNode load() {
        try {
            if (Files.exists(file)) {
                JsonNode n = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
                if (n != null && n.isObject()) {
                    return (ObjectNode) n;
                }
            }
        } catch (Exception e) {
            log.warn("读取粉丝历史文件失败, 使用空记录: {}", e.getMessage());
        }
        ObjectNode r = mapper.createObjectNode();
        r.putObject("targets");
        r.putObject("clubs");
        return r;
    }

    /** 社团目标 (JSON 配置优先, 缺省用预置值) */
    public synchronized Target target(String clubName) {
        JsonNode t = root.path("targets").path(clubName);
        long monthly = t.path("monthlyTarget").asLong(0);
        long daily = t.path("dailyTarget").asLong(0);
        if (monthly > 0) {
            return new Target(monthly, false);
        }
        if (daily > 0) {
            return new Target(daily, true);
        }
        Target def = DEFAULT_TARGETS.get(clubName);
        return def != null ? def : new Target(0, false);
    }

    /** 设置/覆盖社团目标 (调试用) */
    public synchronized void putTarget(String clubName, long monthlyTarget, long dailyTarget) {
        ObjectNode targets = (ObjectNode) root.path("targets");
        ObjectNode t = targets.putObject(clubName);
        t.put("monthlyTarget", monthlyTarget);
        t.put("dailyTarget", dailyTarget);
        save();
    }

    /**
     * 保证最近 5 个完整月的成员数据已补录, 返回按时间升序的月度数据列表。
     * 当月数据不在此列 (由 club_profile 实时提供)。
     */
    public synchronized List<ChronoClient.MonthData> ensureHistory(ChronoClient chrono, ClubData d) throws Exception {
        String name = d.name();
        ObjectNode clubs = (ObjectNode) root.path("clubs");
        ObjectNode club = (ObjectNode) clubs.get(name);
        if (club == null) {
            club = clubs.putObject(name);
        }
        club.put("circleId", d.circleId());
        JsonNode mn = club.get("months");
        ObjectNode months = (mn != null && mn.isObject()) ? (ObjectNode) mn : club.putObject("months");

        YearMonth now = YearMonth.now(ZONE);
        List<ChronoClient.MonthData> out = new ArrayList<>();
        boolean changed = false;
        for (int i = HISTORY_MONTHS; i >= 1; i--) {
            YearMonth ym = now.minusMonths(i);
            String key = ym.toString();
            ObjectNode m = (ObjectNode) months.get(key);
            if (m == null) {
                ChronoClient.MonthData md = chrono.queryMonth(d.circleId(), key + "-01");
                m = mapper.createObjectNode();
                m.put("fetchedAt", Instant.now().toString());
                ObjectNode mems = m.putObject("members");
                for (ChronoClient.MonthMember mm : md.members()) {
                    ObjectNode one = mems.putObject(String.valueOf(mm.viewerId()));
                    one.put("name", mm.name());
                    one.put("endFan", mm.endFan());
                    one.put("gain", mm.gain());
                }
                months.set(key, m);
                changed = true;
                log.info("已补录社团 {} {} 月粉丝记忆: {} 人", name, key, md.members().size());
            }
            out.add(toMonthData(m, key));
        }
        if (changed) {
            save();
        }
        return out;
    }

    /** 查看某社团已记忆的月份 (调试用) */
    public synchronized List<String> storedMonths(String clubName) {
        JsonNode months = root.path("clubs").path(clubName).path("months");
        List<String> out = new ArrayList<>();
        if (months.isObject()) {
            months.fieldNames().forEachRemaining(out::add);
        }
        out.sort(String::compareTo);
        return out;
    }

    /** 历史原始 JSON (调试用) */
    public synchronized JsonNode raw() {
        return root.deepCopy();
    }

    private ChronoClient.MonthData toMonthData(ObjectNode m, String key) {
        List<ChronoClient.MonthMember> members = new ArrayList<>();
        JsonNode mems = m.path("members");
        if (mems.isObject()) {
            mems.fields().forEachRemaining(e -> {
                long vid;
                try {
                    vid = Long.parseLong(e.getKey());
                } catch (NumberFormatException ex) {
                    return;
                }
                JsonNode v = e.getValue();
                members.add(new ChronoClient.MonthMember(vid,
                        v.path("name").asText(),
                        v.path("endFan").asLong(),
                        v.path("gain").asLong()));
            });
        }
        return new ChronoClient.MonthData(key + "-01", members);
    }

    private void save() {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("保存粉丝历史失败: {}", e.getMessage());
        }
    }
}
