package com.linxin.chronobot.web;

import com.linxin.chronobot.chrono.ChronoClient;
import com.linxin.chronobot.chrono.ClubData;
import com.linxin.chronobot.service.BotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查与手工查询 (部署冒烟测试用)
 */
@RestController
public class AdminController {

    private final ChronoClient chrono;
    private final BotService botService;

    public AdminController(ChronoClient chrono, BotService botService) {
        this.chrono = chrono;
        this.botService = botService;
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

    /**
     * 调试: 模拟一条指令的解析与回复 (ctx 为 g:<群openid> 或 u:<用户openid>)
     */
    @GetMapping("/reply")
    public Map<String, Object> reply(@RequestParam("text") String text,
                                     @RequestParam(value = "ctx", defaultValue = "g:test") String ctx) {
        String r = botService.buildReply(ctx, text);
        return Map.of("reply", r == null ? "" : r);
    }
}
