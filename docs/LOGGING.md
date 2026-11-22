# 日志与问题反馈（Logging & Feedback）

本文面向使用者与二次开发者：出现闪退、连不上相机、按钮没反应时，如何拿到一份**能定位问题、又不泄露照片**的日志。

## 四层日志各自负责什么

| 层 | 实现 | 落点 | 用途 |
| --- | --- | --- | --- |
| 门面 | Timber 5 | Logcat + 转发到 DoukLog | 统一 `tag/event/key=value` 结构，代码里只写业务事件 |
| 业务日志 | `DoukLog` | `files/douk-logs/session-YYYYMMDD-HHmmss.log` | 连接、PTP 属性、图库分页、下载、蓝牙 GPS 等关键步骤，每条都带结构化字段 |
| 底层文件日志 | xLog 1.11 | `files/xlog/YYYY-MM-DD` | 进程异常退出前的最后现场，按天分文件 |
| 网络 | `NetworkLoggingInterceptor` | DoukLog，`Net` 标签 | 只记录方法、URL、状态码、耗时、字节数；不记录 Cookie/Authorization |
| 崩溃 | ACRA 5 | `files/acra/{approved,unapproved}` | 堆栈、机型、系统版本、logcat 尾部；下次启动可随诊断包导出 |

设计约束：

- 日志**不包含**照片/视频原始字节、LUT 文件内容、账号 token、热点密码。
- 可能包含本机 IP、相机 IP、相机序列号/型号、持久客户端 GUID、配对过程。发给他人前请确认可以接受。
- 每次启动新建一份会话日志，最多保留 12 份；单文件超过 3 MB 自动切分。
- 写盘走独立守护线程，主线程只入队，不因日志掉帧或 ANR。

## 行格式

```text
11-18 19:49:28.389 D/Prop: DESC_OK prop=0xD1AA cur=800 values=22 writable=true
11-18 19:49:29.876 I/Storage: ENUM_DONE folders=68 photos=60 denied=0 hasMore=true
```

`时间 级别/标签: 事件 key=value ...`

常用标签：`Connect`、`Prop`、`Storage`、`ViewModel`、`Ble`、`Net`、`Preset`、`Diagnostics`。

## 用户怎么把日志发出来

设置 → 关于 → **反馈与建议**，三个入口：

1. **只保存诊断日志**：生成 `lianjing-diagnostics-*.zip`，用系统文件选择器存到手机任意位置。
2. **写邮件并附上日志**：收件人预填 `lianjingup@outlook.com`，正文已带版本、机型、系统、问题描述/复现步骤/期望结果模板，ZIP 作为附件。邮件由用户本机客户端发出，应用不会后台自动发送。
3. **只发文字，不带日志**：只拉起邮件客户端，正文同样带模板。

诊断包目录：

```text
README.txt
device-info.txt
settings/preset.json
logs/           # DoukLog 会话日志
xlog/           # xLog 按天文件
acra/           # 崩溃报告
```

## 开发者怎么加日志

```kotlin
DoukLog.i("Connect", "PTP_HANDSHAKE", "ip" to ip, "ms" to cost)
DoukLog.w("Prop", "WRITE_REJECTED", "prop" to "0x5007", "code" to code)
```

规则：

- 事件名用大写短横线语义（`FOLDER_OPEN`、`ENUM_DONE`），字段用 `key=value`，不要把整段 JSON 塞进 message。
- 不要打印二进制、完整 URL query 中的 token、用户照片路径以外的隐私内容。
- 异常路径必须带相机返回码（PTP response code）和重试次数。
- 新增可恢复错误时，同时给出用户能执行的下一步，例如“把相机重新切到连接至智能设备→配对”。

## 诊断包生成

`DoukDiagnostics.build(context)` 在 IO 线程生成 ZIP；`email(...)` 通过 `FileProvider` + `ACTION_SEND` 把 ZIP 作为附件拉起邮件客户端（`ACTION_SENDTO mailto:` 不支持附件，别用）。导出使用 SAF，不申请存储权限。
