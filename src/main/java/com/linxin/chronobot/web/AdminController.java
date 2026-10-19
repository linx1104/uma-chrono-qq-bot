package com.linxin.chronobot.web;

import com.linxin.chronobot.chat.MemoryStore;
import com.linxin.chronobot.chrono.ChronoClient;
import com.linxin.chronobot.chrono.ClubData;
import com.linxin.chronobot.service.BotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 健康检查与手工查询 (部署冒烟测试用)
 */
@RestController
public class AdminController {

    private final ChronoClient chrono;
    private final BotService botService;
    private final MemoryStore memoryStore;

    public AdminController(ChronoClient chrono, BotService botService, MemoryStore memoryStore) {
        this.chrono = chrono;
        this.botService = botService;
        this.memoryStore = memoryStore;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "ok", true,
                "cookieAgeSeconds", chrono.cookieAgeSeconds(),
                "cacheSize", chrono.cacheSize()
        );
    }

    @GetMapping("/query")
    public ClubData query(@RequestParam("name") String name) throws Exception {
        return chrono.queryClub(name);
    }

    /** 调试: 返回社团原始 JSON (含未映射字段); q 为附加查询串如 sdate=2026-09-01, ep 为端点名默认 club_profile */
    @GetMapping("/raw")
    public String raw(@RequestParam("name") String name,
                      @RequestParam(value = "q", defaultValue = "") String q,
                      @RequestParam(value = "ep", defaultValue = "club_profile") String ep) throws Exception {
        return chrono.queryClubRaw(name, q, ep);
    }

    /**
     * 调试: 模拟一条指令的解析与回复 (ctx 为 g:<群openid> 或 u:<用户openid>)
     */
    @GetMapping("/reply")
    public Map<String, Object> reply(@RequestParam("text") String text,
                                     @RequestParam(value = "ctx", defaultValue = "g:test") String ctx) {
        BotService.CmdReply r = botService.buildReply(ctx, text);
        if (r == null) {
            return Map.of("reply", "");
        }
        return Map.of(
                "reply", r.text() == null ? "" : r.text(),
                "images", r.images().stream().map(Path::toString).toList()
        );
    }

    /**
     * 调试: 生成社团粉丝报表图片 (同时补录月度记忆)
     */
    @GetMapping("/clubimg")
    public Map<String, Object> clubimg(@RequestParam("name") String name) throws Exception {
        Path png = botService.renderClubImage(name);
        return Map.of(
                "name", name,
                "png", png.toAbsolutePath().toString(),
                "sizeBytes", Files.size(png)
        );
    }

    /**
     * 调试: 强制走聊天链路 (同时把该消息记入记忆)
     */
    @GetMapping("/chat")
    public Map<String, Object> chat(@RequestParam("text") String text,
                                    @RequestParam(value = "ctx", defaultValue = "u:debug") String ctx,
                                    @RequestParam(value = "user", defaultValue = "debug-user") String user) {
        String r = botService.chatForDebug(ctx, user, text);
        return Map.of("reply", r == null ? "" : r);
    }

    /**
     * 调试: 查看某群友的记忆档案与待压缩消息
     */
    @GetMapping("/memory")
    public Map<String, Object> memory(@RequestParam("ctx") String ctx,
                                      @RequestParam("user") String user) {
        return botService.memoryForDebug(ctx, user);
    }

    /**
     * 调试: 每日记忆整理状态; run=1 时立刻整理最近 23 小时
     */
    @GetMapping("/batch")
    public Map<String, Object> batch(@RequestParam(value = "run", defaultValue = "0") String run) {
        if ("1".equals(run)) {
            return memoryStore.runDailyBatch(true);
        }
        return memoryStore.batchStatus();
    }

    /**
     * 调试: 查看某条消息的接话打分
     */
    @GetMapping("/should")
    public Map<String, Object> should(@RequestParam("text") String text,
                                      @RequestParam(value = "ctx", defaultValue = "g:test") String ctx) {
        return botService.shouldForDebug(ctx, text);
    }
}
