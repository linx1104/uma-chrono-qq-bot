package com.linxin.chronobot.util;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 社团粉丝报表 PNG 生成器 (无头 AWT 绘制)。
 * <p>
 * 布局: 每个社团一张表 (标题区 + 单张合并表):
 * 本月数据列 (月初粉丝/当前粉丝/本月增量/日均/上月增量/完成率) 后直接接
 * 近六个月每月增量列; 多社团纵向拼接为一张长图, 只发一条消息。
 */
public final class TableRenderer {

    private TableRenderer() {
    }

    /** 单行数据: rate=完成率 (null 表示无目标), historyGains=最近 5 个完整月的当月增量 (缺失为 null) */
    public record Row(String name, long monthStartFan, long currentFan, long monthGain,
                      long dailyAvg, long lastMonthGain, Double rate, List<Long> historyGains) {
    }

    public record Data(String clubName, String targetLabel, long clubFan, long clubDailyAvg,
                       long clubMonthly, int rank, String updatedAt,
                       List<String> historyLabels, List<Row> rows) {
    }

    private static final int MAX_ROWS = 40;

    private static final Color BG = Color.WHITE;
    private static final Color HEADER_BG = new Color(62, 124, 124);
    private static final Color ALT_ROW = new Color(240, 246, 246);
    private static final Color TEXT = new Color(34, 34, 34);
    private static final Color SUBTITLE = new Color(102, 102, 102);
    private static final Color TITLE = new Color(43, 79, 79);
    private static final Color GRID = new Color(222, 232, 232);
    private static final Color RATE_GREEN = new Color(26, 127, 55);
    private static final Color RATE_ORANGE = new Color(179, 89, 0);
    private static final Color RATE_RED = new Color(198, 40, 40);

    private static final int PAD_X = 12;
    private static final int ROW_H = 38;
    private static final int MARGIN = 20;
    private static final int TITLE_H = 118;
    private static final int BLOCK_GAP = 44;

    /** 本月数据列 (完成率列在索引 6, 参与着色) */
    private static final String[] BASE_HEADERS = {"昵称", "月初粉丝", "当前粉丝", "本月增量", "日均", "上月增量", "完成率"};
    private static final int RATE_COL = 6;

    public static Path render(Data data) throws IOException {
        return renderAll(List.of(data));
    }

    /** 多社团纵向拼接成一张长图 */
    public static Path renderAll(List<Data> datas) throws IOException {
        Font base = pickCjkFont(17);
        Font bold = base.deriveFont(Font.BOLD);

        BufferedImage scratch = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        Graphics2D gs = scratch.createGraphics();
        FontMetrics fm = gs.getFontMetrics(base);
        FontMetrics fmb = gs.getFontMetrics(bold);
        gs.dispose();

        List<Block> blocks = new ArrayList<>();
        for (Data d : datas) {
            blocks.add(Block.prepare(d, fm));
        }

        int width = MARGIN * 2;
        int height = MARGIN * 2;
        for (int i = 0; i < blocks.size(); i++) {
            width = Math.max(width, blocks.get(i).width + MARGIN * 2);
            height += blocks.get(i).height;
            if (i > 0) {
                height += BLOCK_GAP;
            }
        }

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, width, height);

        int y = MARGIN;
        for (Block b : blocks) {
            y = b.draw(g, fm, fmb, MARGIN, y);
            y += BLOCK_GAP;
        }

        g.dispose();

        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "chronobot");
        Files.createDirectories(dir);
        cleanup(dir);
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String safe = datas.size() == 1
                ? datas.get(0).clubName().replaceAll("[^\\p{L}\\p{N}_-]", "_")
                : "clubs";
        Path out = dir.resolve("club-" + safe + "-" + ts + ".png");
        ImageIO.write(img, "png", out.toFile());
        return out;
    }

    /** 单个社团的渲染块 */
    private static final class Block {
        final Data data;
        final List<Row> shown;
        final boolean truncated;
        final String[] headers;
        final List<String[]> cells;
        final int[] widths;
        final int width;
        final int height;

        private Block(Data data, List<Row> shown, boolean truncated, String[] headers,
                      List<String[]> cells, int[] widths, int width, int height) {
            this.data = data;
            this.shown = shown;
            this.truncated = truncated;
            this.headers = headers;
            this.cells = cells;
            this.widths = widths;
            this.width = width;
            this.height = height;
        }

        static Block prepare(Data data, FontMetrics fm) {
            List<Row> rows = data.rows() == null ? List.of() : data.rows();
            boolean truncated = rows.size() > MAX_ROWS;
            List<Row> shown = truncated ? new ArrayList<>(rows.subList(0, MAX_ROWS)) : rows;

            // 表头: 本月数据列 + 近六个月增量列
            List<String> hl = new ArrayList<>(Arrays.asList(BASE_HEADERS));
            if (data.historyLabels() != null) {
                hl.addAll(data.historyLabels());
            }
            String[] headers = hl.toArray(new String[0]);

            List<String[]> cells = new ArrayList<>();
            for (Row r : shown) {
                String[] c = new String[headers.length];
                c[0] = r.name();
                c[1] = num(r.monthStartFan());
                c[2] = num(r.currentFan());
                c[3] = gain(r.monthGain());
                c[4] = num(r.dailyAvg());
                c[5] = gain(r.lastMonthGain());
                c[6] = rateStr(r.rate());
                int nHist = headers.length - BASE_HEADERS.length;
                for (int i = 0; i < nHist; i++) {
                    Long v = (r.historyGains() != null && i < r.historyGains().size())
                            ? r.historyGains().get(i) : null;
                    c[BASE_HEADERS.length + i] = v == null ? "-" : gain(v);
                }
                cells.add(c);
            }

            // 列最小宽度: 本月数据列 92, 历史增量列 84
            int[] mins = new int[headers.length];
            mins[0] = 160;
            for (int i = 1; i < BASE_HEADERS.length; i++) {
                mins[i] = 92;
            }
            for (int i = BASE_HEADERS.length; i < headers.length; i++) {
                mins[i] = 84;
            }
            int[] widths = colWidths(fm, headers, cells, mins);

            int width = sum(widths);
            int noteH = truncated ? 34 : 0;
            int height = TITLE_H + ROW_H + shown.size() * ROW_H + noteH;
            return new Block(data, shown, truncated, headers, cells, widths, width, height);
        }

        int draw(Graphics2D g, FontMetrics fm, FontMetrics fmb, int x, int y) {
            Font base = fm.getFont();
            Font title = base.deriveFont(Font.BOLD, 26f);
            Font sub = base.deriveFont(15f);
            Font note = base.deriveFont(15f);

            // 标题区
            g.setColor(TITLE);
            g.setFont(title);
            g.drawString("「" + data.clubName() + "」粉丝报表", x, y + 30);
            g.setFont(sub);
            g.setColor(TEXT);
            g.drawString("社团当日粉丝 " + num(data.clubFan())
                    + " · 当月日均 " + num(data.clubDailyAvg())
                    + " · 当月总 " + num(data.clubMonthly()), x, y + 58);
            String meta = "更新 " + (data.updatedAt() == null || data.updatedAt().isBlank() ? "-" : data.updatedAt());
            if (data.rank() > 0) {
                meta += " · 排名 #" + data.rank();
            }
            if (data.targetLabel() != null && !data.targetLabel().isBlank()) {
                meta += " · " + data.targetLabel();
            }
            g.setColor(SUBTITLE);
            g.drawString(meta, x, y + 80);
            y += TITLE_H;

            // 合并表
            y = drawHeader(g, fmb, x, y, widths, headers);
            for (int i = 0; i < cells.size(); i++) {
                y = drawRow(g, fm, x, y, widths, cells.get(i), i % 2 == 1, RATE_COL);
            }

            if (truncated) {
                g.setFont(note);
                g.setColor(SUBTITLE);
                g.drawString("仅显示前 " + MAX_ROWS + " 人, 另有 " + (data.rows().size() - MAX_ROWS) + " 人未显示", x, y + 26);
            }
            return y;
        }
    }

    private static int drawHeader(Graphics2D g, FontMetrics fm, int x, int y, int[] widths, String[] headers) {
        int w = sum(widths);
        g.setColor(HEADER_BG);
        g.fillRoundRect(x, y, w, ROW_H, 6, 6);
        g.setFont(fm.getFont());
        g.setColor(Color.WHITE);
        int cx = x;
        for (int i = 0; i < headers.length; i++) {
            boolean right = i > 0;
            String h = headers[i];
            int tx = right ? cx + widths[i] - PAD_X - fm.stringWidth(h) : cx + PAD_X;
            g.drawString(h, tx, y + ROW_H / 2 + fm.getAscent() / 2 - 2);
            cx += widths[i];
        }
        return y + ROW_H;
    }

    private static int drawRow(Graphics2D g, FontMetrics fm, int x, int y, int[] widths,
                               String[] cells, boolean alt, int rateCol) {
        int w = sum(widths);
        if (alt) {
            g.setColor(ALT_ROW);
            g.fillRoundRect(x, y, w, ROW_H, 4, 4);
        }
        g.setColor(GRID);
        g.drawLine(x, y + ROW_H, x + w, y + ROW_H);
        g.setFont(fm.getFont());
        int cx = x;
        for (int i = 0; i < cells.length; i++) {
            String c = cells[i];
            Color color = TEXT;
            if (i == rateCol && c != null && !c.equals("-")) {
                try {
                    double v = Double.parseDouble(c.replace("%", ""));
                    color = v >= 100 ? RATE_GREEN : v >= 50 ? RATE_ORANGE : RATE_RED;
                } catch (NumberFormatException ignored) {
                }
            }
            boolean right = i > 0;
            int maxW = widths[i] - PAD_X * 2;
            String draw = c;
            while (draw.length() > 1 && fm.stringWidth(draw) > maxW) {
                draw = draw.substring(0, draw.length() - 2) + "…";
            }
            int tx = right ? cx + widths[i] - PAD_X - fm.stringWidth(draw) : cx + PAD_X;
            g.setColor(color);
            g.drawString(draw, tx, y + ROW_H / 2 + fm.getAscent() / 2 - 2);
            cx += widths[i];
        }
        return y + ROW_H;
    }

    private static int[] colWidths(FontMetrics fm, String[] headers, List<String[]> rows, int[] mins) {
        int n = headers.length;
        int[] w = new int[n];
        for (int i = 0; i < n; i++) {
            int m = fm.stringWidth(headers[i]) + PAD_X * 2 + 4;
            for (String[] row : rows) {
                String c = row[i] == null ? "" : row[i];
                m = Math.max(m, fm.stringWidth(c) + PAD_X * 2 + 4);
            }
            w[i] = Math.max(m, mins != null && i < mins.length ? mins[i] : 64);
        }
        return w;
    }

    private static int sum(int[] a) {
        int s = 0;
        for (int v : a) {
            s += v;
        }
        return s;
    }

    /** 紧凑数字: ≥1亿 → x.xx亿, ≥1万 → x.x万, 否则千分位 (公开给 BotService 格式化目标值) */
    public static String compact(long v) {
        return num(v);
    }

    /** 紧凑数字: ≥1亿 → x.xx亿, ≥1万 → x.x万, 否则千分位 */
    private static String num(long v) {
        boolean neg = v < 0;
        long abs = Math.abs(v);
        String body;
        if (abs >= 100_000_000L) {
            body = trim(abs / 100_000_000.0) + "亿";
        } else if (abs >= 10_000L) {
            body = trim(abs / 10_000.0) + "万";
        } else {
            body = String.format(Locale.CHINA, "%,d", abs);
        }
        return neg ? "-" + body : body;
    }

    /** 带正负号的增量 */
    private static String gain(long v) {
        return v >= 0 ? "+" + num(v) : num(v);
    }

    private static String rateStr(Double r) {
        if (r == null) {
            return "-";
        }
        return String.format(Locale.CHINA, "%.1f%%", r * 100);
    }

    /** 最多两位小数并去掉尾零 */
    private static String trim(double d) {
        String s = String.format(Locale.CHINA, "%.2f", d);
        if (s.endsWith("00")) {
            return s.substring(0, s.length() - 3);
        }
        if (s.endsWith("0")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static Font pickCjkFont(int size) {
        Set<String> avail = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String c : List.of("Noto Sans CJK SC", "Noto Sans SC", "Source Han Sans SC",
                "WenQuanYi Micro Hei", "WenQuanYi Zen Hei", "Microsoft YaHei", "SimHei", "PingFang SC")) {
            if (avail.contains(c)) {
                return new Font(c, Font.PLAIN, size);
            }
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, size);
    }

    /** 清理 6 小时前的旧报表文件 */
    private static void cleanup(Path dir) {
        long cutoff = System.currentTimeMillis() - 6 * 3600_000L;
        try (var s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".png")).forEach(p -> {
                try {
                    if (Files.getLastModifiedTime(p).toMillis() < cutoff) {
                        Files.deleteIfExists(p);
                    }
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
