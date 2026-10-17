# chrono-qq-bot

QQ 机器人: 对话式查询赛马娘社团/成员粉丝数据（数据源 ChronoGenesis.net）。

## 群内指令（前缀 cy，需 @机器人）

| 指令 | 说明 |
| --- | --- |
| `cy绑定社团 <社团名>` | 新增绑定（可绑多个，同名不重复添加，上限 10 个；绑定持久化到 bindings.json） |
| `cy解除绑定社团 <社团名>` | 解除绑定（等价：`cy解绑社团` / `cy删除社团`） |
| `cy查询绑定社团列表` | 查看本会话绑定列表（等价：`cy查询绑定社团` / `cy查询社团列表` / `cy查询社团`） |
| `cy查询社团粉丝` | 依次查询全部绑定社团的当日粉丝 / 当月日均 / 当月总粉丝 / 排名 |
| `cy查询社团粉丝 <社团名>` | 查询指定社团（临时覆盖绑定） |
| `cy查询<成员名>粉丝` | 在所有绑定社团中按名字模糊匹配成员（至少 2 个字符，含曾用名），展示社团+成员粉丝数据 |
| `帮助` | 显示指令说明 |

成员数据来自 club_profile 接口的 `club_friend_profile` 字段（含成员名字与粉丝字段），无需额外请求。

## 群聊不可用的排查

机器人未发布前只能进「沙箱群」（在 q.qq.com 配置，成员 ≤20 人、群主为本人），普通群需完成发布审核并手动上线后才能添加。私聊可用、群里没反应，按顺序检查：

1. q.qq.com → 开发 → 开发设置 → 事件订阅：勾选「群聊@机器人消息事件」与「私聊消息事件」
2. 目标群是否已配置为沙箱群，或机器人已完成发布上线
3. 群成员列表里确认机器人确实在群内
4. `journalctl -u chronobot -f` 观察 @机器人 时是否打印「收到群消息」日志

## 工作原理

ChronoGenesis.net 的数据接口受 Cloudflare Turnstile 人机验证保护：

1. 接口请求被拒（422）→ 需要解一次 Turnstile
2. 通过 capsolver 打码平台解出 token，调 `POST /verify` 换取 JWT 会话 Cookie（有效期 72 小时，绑定请求 IP）
3. 之后请求携带 Cookie + `CG-Carrot` 指纹头即可正常取数

本服务验证与查询都在同一台服务器上发起，全程固定 User-Agent / Accept-Language / 指纹头，满足服务端一致性校验。会话过期（约 3 天）或收到 422 时自动重新解验证，单次成本约 $0.001，月成本可忽略。

## 快速开始

### 1. QQ 机器人平台配置

1. 到 [q.qq.com](https://q.qq.com) 创建机器人，获取 AppID / AppSecret
2. 「开发设置」→ 订阅事件：勾选 **群聊@机器人消息** 与 **私聊消息**
3. 「发布设置」→ 开通群聊能力（按平台当前政策可能需要提审；未上架前可在测试群/沙箱频道验证）
4. 把机器人拉进目标群

### 2. 配置

环境变量（可选，不设置则用 application.yml 里的默认值）：

```bash
export QQ_APP_SECRET=你的AppSecret
export CAPSOLVER_KEY=你的capsolver密钥
```

其他配置（社团别名等）在 `src/main/resources/application.yml`。

### 3. 构建与运行

需要 JDK 21 + Maven 3.8+：

```bash
mvn -q clean package -DskipTests
java -Xmx512m -jar target/chrono-qq-bot-1.0.0.jar
```

### 4. 验证

```bash
curl "http://127.0.0.1:8080/health"          # 健康检查, cookieAgeSeconds=-1 表示还没验证过
curl "http://127.0.0.1:8080/query?name=白猫海賊船"   # 手工查询冒烟测试
```

第一条查询会触发一次打码（约 5-40 秒），成功后 /health 的 cookieAgeSeconds 变为非负，之后查询走缓存秒回。

## 部署到阿里云 2C2G

```bash
# 安装 JDK21 (Ubuntu/Debian)
sudo apt-get update && sudo apt-get install -y openjdk-21-jre-headless

# systemd 服务
sudo tee /etc/systemd/system/chronobot.service > /dev/null <<'EOF'
[Unit]
Description=chrono-qq-bot
After=network.target

[Service]
Environment=QQ_APP_SECRET=xxx
Environment=CAPSOLVER_KEY=xxx
ExecStart=/usr/bin/java -Xmx512m -jar /opt/chronobot/chrono-qq-bot-1.0.0.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl enable --now chronobot
```

注意：验证会话绑定服务器出口 IP。如果 ECS 出口 IP 会变（如弹性公网 IP 变更），下次 422 时会自动重新验证，无需人工干预。

## 注意事项

- 该站数据每日更新一次，本服务缓存 30 分钟，对数据源压力极小
- 打码解 Turnstile 属灰色操作，仅限个人低频使用，勿高频刷接口或对外提供服务
- 站长的弹窗说明「粉丝监控工具（Discord bots）需要社团数据可邮件联系 chronogenesist.net@protonmail.com」，建议发邮件申请正规数据通道；拿到后可在 ChronoClient 中直接替换认证方式

## 常见问题

**WebSocket 连接被关闭, 日志出现 `code=4924 reason=session ip forbidden`**

QQ 开放平台启用了 IP 白名单: 登录 [q.qq.com](https://q.qq.com) → 开发设置 → IP 白名单, 添加运行本服务机器的公网 IP(本机测试用) 与 ECS 公网 IP(正式部署用)。添加后无需重启服务, 下一次自动重连即生效。
