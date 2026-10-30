# 提示词：雪铁龙 C3-XR / 飞利浦车机 CANBUS 报文收集器

> 用途：把下面「第 11 节 完整提示词」整段复制给 AI（或开发同事），即可直接产出可运行的工程。
> 需要你先填的只有「第 2 节 待确认参数」，其余已给出默认值，不填就按默认走。

---

## 1. 背景与目标

| 项 | 内容 |
|---|---|
| 车型 | 东风雪铁龙 C3-XR |
| 车机 | 飞利浦（Philips）后装车机，Android 系统 |
| 目标 | 在车机上后台持续采集 CANBUS 报文，**把原始报文完整保存到车机本地存储**，可事后导出分析 |
| 关键点 | 不依赖电脑、不依赖网络，着车即录、熄火不丢已录数据 |
| 产物 | 一个可安装的 APK + 源码 + 使用说明 |

典型链路：

```
整车 CAN 总线
   │
   ├─ 原车 OBD/舒适 CAN
   │
协议盒（CANBUS 解码盒，随车机附带）
   │  UART / USB 串口
   ▼
飞利浦车机（Android）
   │
本 App：串口读取 → 解析成 CAN 帧 → 带时间戳落盘 → 导出
```

---

## 2. 待确认参数（可留空，留空按默认值实现）

| 参数 | 默认值 | 你的实际值 |
|---|---|---|
| 车机 Android 版本 | Android 10（兼容 4.4 ~ 13） | |
| 是否已 root | **否（已确认）** | 无 root |
| 与协议盒的连接方式 | **板载串口 `/dev/ttyS1`（已确认）** | 串口 / USB-OTG 转串口 / 不确定 |
| 串口波特率 | 115200 | 9600 / 38400 / 115200 |
| 串口参数 | 8 数据位、无校验、1 停止位、无流控 | |
| 协议盒品牌/协议 | 未知，按「RAW 透传 + 常见帧头自动识别」处理 | 睿志诚 / 精邦 / 其他 |
| 采集的 CAN 通道 | 舒适 CAN（协议盒透传的那一路） | |
| 日志保存路径 | `/sdcard/CanLog/` | |
| 单次录制时长 | 不限，文件按大小滚动 | |
| 是否需要发送报文 | 否（仅监听） | 是 / 否 |

> 若协议盒不走标准串口、而是走车机 MCU 私有通道，则实现方案改为「方案 B：USB-CAN 适配器」或「方案 C：MCU 透传 SDK」，见 4.3。

---

## 3. 功能需求

### P0（必须实现，缺一不可）
1. **串口打开与配置**：可选端口、波特率、数据位/校验/停止位；打开失败给出明确错误码与提示。
2. **断线自动重连**：串口拔插、协议盒掉电、读取异常时，指数退避重连（1s→2s→5s→10s 封顶），重连事件写入日志。
3. **帧读取**：二进制按字节流读取，支持两种输入模式
   - 原始二进制帧（含帧头/长度/校验，自动识别常见格式）
   - ASCII 文本行（如 `t7DF8 02 01 0C ...`、`7DF#02010C...`）
   无法识别的内容按 `UNKNOWN` 原样记录，不允许崩溃或丢弃。
4. **CAN 帧解析**：标准帧（11 位 ID）/ 扩展帧（29 位 ID）、DLC、数据字节、接收时间戳。
5. **时间戳**：每帧同时记录
   - `uptime_ms`：开机单调时钟（`SystemClock.elapsedRealtimeNanos()`，不受改时间影响，用于算间隔）
   - `wall`：`yyyy-MM-dd HH:mm:ss.SSS`（用于和视频/现象对齐）
6. **落盘**：写入车机本地存储，**每行即刷**（缓冲 ≤ 200ms 或 4KB 必须 flush），保证断电最多丢 1 行。
7. **会话文件**：每次「开始录制」新建一个文件，文件名含起录时间；停止、崩溃、重启后都能定位到完整文件。
8. **前台服务**：常驻通知栏，熄屏/切后台继续录制；车机重启后可选自动开始录制。
9. **简单 UI**：开始/停止、当前端口与波特率、已录帧数、文件大小、实时滚动显示最近 200 帧、当前日志文件路径。
10. **导出**：一键把当前/全部日志复制到 U 盘目录或通过系统分享（`FileProvider`）导出。
11. **空间保护**：日志目录总大小上限可配（默认 500MB），超出按最旧文件自动删除；剩余空间 < 200MB 时停止录制并在通知栏告警。

### P1（强烈建议）
- CAN ID 白名单/黑名单过滤（正则或十六进制列表），过滤只影响落盘，UI 仍显示。
- 按 ID 统计：帧数、周期（ms）、最近数据，生成 `summary.txt` 便于快速找周期帧。
- 手动发送单帧（用于唤醒总线/试探），默认关闭，需二次确认。
- 日志按时间切片（默认 50MB 或 10 分钟切一个文件）。

### P2（可选）
- DBC 文件导入，附加解析后的信号名与物理值列。
- 内置 HTTP 服务，同一 WiFi 下浏览器下载日志。
- 上传到服务器（可配置 URL）。

---

## 4. 技术约束与实现方案

### 4.1 平台
- 语言：**Kotlin**（无 Kotlin 环境可用 Java，但需同等结构）
- minSdk 21 / targetSdk 按车机实际版本，`compileSdk 34`
- 不引入需要 Google 服务的库（车机多无 GMS）
- 依赖尽量少：串口用 `usb-serial-for-android`（USB 方案）或自带 JNI termios（板载串口方案）

### 4.2 方案 A（已定方案）：板载串口，**无 root**
无 root 下的落地顺序（全部在 App 内自动完成，失败信息要能看懂）：

1. **探测设备节点**：`/dev/ttyS0`、`/dev/ttyS1`、`/dev/ttyS2`、`/dev/ttyS3`、`/dev/ttyHS1`、`/dev/ttyMT1`，用 `exists()/canRead()/canWrite()` 判断，UI 上标注每个节点的可读写状态。
2. **优先用 `stty`（toybox/busybox）配置线路**：
   `stty -F /dev/ttyS1 115200 raw -echo -ixon -crtscts -opost min 0 time 5`
   - `min 0 time 5` 让 `read()` 每 0.5s 返回一次（0 字节），避免阻塞读导致无法停止/无法重连，这是无 root 下的关键技巧。
   - 依次尝试 `stty` → `toybox stty` → `busybox stty` → 退化版（不带 min/time）。
   - 全都不可用时仍尝试直接打开（很多车机在内核/init 里已把该口配成 115200 raw），并在 `app.log` 记录「未能配置线路参数」。
3. **用 `RandomAccessFile(dev, "rw")` 直接读写**：后装车机 ROM 通常把协议盒串口设为 `crw-rw-rw-`，App 可直接访问；权限不足时给出 `ls -l` 结果和建议（改 ROM 权限 / 换 USB 方案）。
4. **读线程**：按 4096 字节阻塞读，`0` 视为超时、`-1/异常` 视为断线触发重连；落盘由独立线程消费队列，读线程零 IO。

> 无 root 且第 3 步失败（权限为 `crw------- root`）时，只能：① 让车机厂商/刷机包放开该节点权限；② 改走 USB-OTG 转串口（方案 B，无需 root）。App 会在界面上明确提示这两条出路，而不是静默失败。

### 4.3 方案 B：USB-OTG 转串口（CH340 / CP2102 / PL2303 / FTDI）
- 使用 `usb-serial-for-android`，通过 `UsbManager` 申请权限，拔插用 `BroadcastReceiver` 监听 `USB_DEVICE_ATTACHED/DETACHED` 自动恢复。

### 4.4 方案 C：协议盒走车机 MCU
- 若串口不可访问，改为调用车机厂商 SDK 的 MCU 透传接口；此方案需厂商文档，先留 `Transport` 接口抽象（`CanTransport`），三种实现可互换。

### 4.5 代码结构（期望交付）

```
app/src/main/java/com/philips/canlogger/
├── MainActivity.kt              # UI、权限、启停
├── service/LogService.kt        # 前台服务，持有 Transport + Recorder
├── transport/
│   ├── CanTransport.kt          # 接口：open/close/read/write/state
│   ├── SerialPortTransport.kt   # 方案 A
│   ├── UsbSerialTransport.kt    # 方案 B
│   └── FrameParser.kt           # 字节流 → CanFrame（含帧头识别）
├── model/CanFrame.kt            # id/ext/dlc/data/uptime/wall/dir/raw
├── recorder/
│   ├── LogRecorder.kt           # 队列消费、格式化、flush、滚动
│   ├── LogFormatter.kt          # TXT / CSV 两种输出
│   └── StorageGuard.kt          # 容量上限、剩余空间检查
├── export/Exporter.kt           # FileProvider 分享 / 拷贝到 U 盘
└── util/                        # 十六进制、时间、日志
```

---

## 5. 日志格式规范

### 5.1 主日志（默认 TXT，人可读，可直接 grep）
文件名：`can_20261030_101233.log`（起录时间）

```
# app=CanLogger v1.0
# vehicle=Citroen C3-XR / Philips HU
# port=/dev/ttyS1 baud=115200 8N1
# started=2026-10-30 10:12:33.120 (+08:00)
# format=uptime_ms|dir|can_id|dlc|data
# ---- BEGIN ----
0000012345 RX 7DF 8 02 01 0C 00 00 00 00 00
0000012358 RX 0C9 8 00 00 1A 2F 00 00 00 00
0000012401 RX 18FEF100x 8 FF 00 00 00 00 00 00 00
# ---- END ----
# frames=1234567 dropped=0 bytes=98765432 duration=01:23:45
# stopped=2026-10-30 11:36:18.987 reason=user_stop
```

规则：
- 第一列是**单调时钟毫秒**（定宽 10 位，便于排序/差分）
- `dir`：`RX` / `TX`
- 扩展帧 ID 末尾加 `x`（如 `18FEF100x`）
- 数据统一大写十六进制、两位、空格分隔；DLC 与数据字节数不一致时照实记录
- 以 `#` 开头的是注释行，解析时跳过
- 文件尾必须有 `# ---- END ----` 和统计行；异常退出时下次启动检测到无 END，标记该文件为 `INCOMPLETE`

### 5.2 可选 CSV（便于 Excel / Python）
`can_20261030_101233.csv`
```
uptime_ms,wall_time,dir,can_id,ext,dlc,data,raw
12345,"2026-10-30 10:12:33.120",RX,7DF,0,8,"02 01 0C 00 00 00 00 00","2E 08 7D F0 ..."
```
`raw` 为收到但未解析的原始字节（HEX），便于回溯协议盒格式。

### 5.3 汇总文件
每次录制结束生成 `can_20261030_101233.summary.txt`：按 ID 的帧数、平均周期、最小/最大间隔、最后一个数据。

---

## 6. 存储与导出

- 默认目录：`/sdcard/CanLog/`（Android 10+ 走 `MANAGE_EXTERNAL_STORAGE` 或 `MediaStore`，两者都要实现并自动选择；同时写入 App 私有目录 `/Android/data/com.philips.canlogger/files/CanLog/` 作为兜底）
- 目录结构：
  ```
  /sdcard/CanLog/
  ├── 20261030/
  │   ├── can_20261030_101233.log
  │   ├── can_20261030_101233.summary.txt
  │   └── can_20261030_143001.log      (INCOMPLETE，异常退出)
  └── app.log                          (程序自身运行日志，滚动 5 份 × 2MB)
  ```
- 导出：
  1. 系统分享（`FileProvider`）→ 微信/U盘App
  2. 选择 U 盘挂载点（`/mnt/usbhost1`、`/storage/udisk*`）直接复制
  3. 提示 ADB 命令：`adb pull /sdcard/CanLog`

---

## 7. UI 要求（车机横屏 1024×600 优先）

- 单页布局，深色主题，按钮 ≥ 80dp（行车可点）
- 顶部状态条：`● 录制中 | /dev/ttyS1 @115200 | 12,345 帧 | 2.3 MB | 00:12:34`
- 中部：最近 200 帧滚动列表（等宽字体，自动滚到底，暂停滚动按钮）
- 底部：`开始/停止`、`设置`（端口/波特率/过滤/容量上限）、`导出`
- 开始录制后允许熄屏，界面不常亮

---

## 8. 稳定性与异常处理

| 场景 | 要求 |
|---|---|
| 串口打不开 | 提示具体 errno（Permission denied / No such file / busy），自动依次尝试候选端口 |
| 读线程异常 | 捕获所有 `IOException`，重连；连续失败 10 次进入 30s 慢重试 |
| 存储写失败 | 保留内存队列 5 分钟并提示；恢复后补写，超出则丢弃并记录 `DROP` |
| 进程被杀 | 前台服务 + `START_STICKY`；重启后检测未闭合日志并补 `# INCOMPLETE` |
| 车机休眠/断电 | 依赖逐行 flush，最大丢 1 行 |
| 系统时间被改 | 不影响 `uptime_ms` 排序 |
| 高负载 | 落盘线程优先级 `THREAD_PRIORITY_BACKGROUND`，读线程 `URGENT_AUDIO`；禁止在主线程做任何 IO |
| 日志暴涨 | 1000 帧/秒以上时启用批量写（每 200ms 拼一次 write），并统计实际丢帧数 |

**必须遵守**：任何异常都不得让 App 静默退出；所有异常写入 `app.log` 并在 UI 上以红字提示最近一条错误。

---

## 9. 验收标准（可逐条测试）

1. 安装后首次打开，授予存储权限，选择 `/dev/ttyS1 @115200`，点开始 → 通知栏出现常驻通知，状态条计数递增。
2. 录制 10 分钟后按 Home 键、熄屏 10 分钟 → 计数继续递增，文件持续增大。
3. 拔掉协议盒电源 30 秒再插回 → 日志中出现 `# reconnect` 记录，之后帧继续写入，无崩溃。
4. 强制杀进程 → 已写入内容完整（最多缺最后 1 行），下次启动该文件被标记 `INCOMPLETE`。
5. 日志可用 Python 一行脚本解析：`[l.split() for l in open(f) if not l.startswith('#')]` 得到 `[uptime, dir, id, dlc, *data]`。
6. 导出到 U 盘后，在 PC 上能正常打开且帧数与 App 显示一致（误差为 0 除丢弃计数外）。
7. 目录超过 500MB 后最旧文件被删除，当前文件不受影响。
8. 无 root、有 root 两种环境都能跑（至少一种可用，另一种给出清晰提示）。

---

## 10. 交付物

1. 完整 Android 工程（Gradle，可直接 `./gradlew assembleRelease`）
2. 已签名 APK（debug 签名也可）
3. `README.md`：安装、授权、选端口、导出、常见问题
4. `PROTOCOL.md`：帧头识别规则、已知协议盒格式、如何新增一种格式
5. 一段 2 分钟实际录制样例日志（脱敏也可）

---

## 11. 完整提示词（直接复制这一段给 AI）

```text
你是资深 Android 车机（Head Unit）开发工程师。请为我开发一个「CANBUS 报文收集器」Android App，运行在飞利浦后装车机上（车型：东风雪铁龙 C3-XR，Android 10，横屏 1024×600，可能无 root、无 GMS）。

【目标】
在车机上后台持续采集协议盒（CANBUS 解码盒）通过串口送来的 CAN 报文，把原始报文带时间戳完整保存到车机本地存储，支持事后导出分析。要求着车可自动开录，熄屏/后台继续录，断电最多丢 1 行。

【输入通道】
1. 默认：板载串口 /dev/ttyS0、/dev/ttyS1、/dev/ttyS2、/dev/ttyS3、/dev/ttyHS1，波特率可选 9600/38400/115200，8N1，无流控；自动探测可用端口。
2. 备选：USB-OTG 转串口（CH340/CP2102/PL2303/FTDI），用 usb-serial-for-android，监听 USB 拔插自动恢复。
3. 请把通道抽象成 CanTransport 接口（open/close/read/write/state），以上两种各一个实现，UI 里可切换。

【功能要求 P0】
- 串口打开失败要给出明确 errno 提示；读线程异常自动指数退避重连（1s/2s/5s/10s 封顶），重连写日志。
- 字节流解析：兼容「二进制帧（含帧头+长度+校验）」与「ASCII 文本帧（如 t7DF8 02 01 0C... 或 7DF#02010C...）」，自动识别；识别不了的原样记为 UNKNOWN，不丢弃、不崩溃。
- 解析出：标准/扩展 ID、DLC、数据、方向 RX/TX。
- 每帧记录两个时间：uptime_ms（SystemClock.elapsedRealtimeNanos，单调）和 wall（yyyy-MM-dd HH:mm:ss.SSS）。
- 读线程只做入队（BlockingQueue 容量 10000），独立落盘线程消费并格式化，绝不在读线程做 IO；队列满时计数丢弃并写 DROP。
- 落盘：每 200ms 或 4KB 必须 flush 一次。
- 每次开始录制新建会话文件 can_yyyyMMdd_HHmmss.log，写入文件头（app 版本、车型、端口、波特率、起始时间、格式说明），正常停止时写 # ---- END ---- 和统计行（帧数、丢弃数、字节数、时长）；异常退出下次启动检测无 END 则标记 INCOMPLETE。
- 日志行格式（定宽，便于脚本解析）：
	0000012345 RX 7DF 8 02 01 0C 00 00 00 00 00
	0000012358 RX 18FEF100x 8 FF 00 00 00 00 00 00 00
  第一列单调毫秒（10 位补零），扩展帧 ID 结尾加 x；数据大写两位十六进制空格分隔；# 开头为注释。
- 可选同时输出 CSV：uptime_ms,wall_time,dir,can_id,ext,dlc,data,raw
- 存储：/sdcard/CanLog/yyyyMMdd/，Android 10+ 自动在 MANAGE_EXTERNAL_STORAGE 与 MediaStore 之间选择，App 私有目录作兜底；日志目录总容量上限可配（默认 500MB），超出删最旧；剩余空间 <200MB 停止录制并通知告警。
- 前台服务常驻通知，START_STICKY，支持开机自启后自动录制（默认关闭，可配）。
- UI 单页深色，按钮 ≥80dp：状态条（录制中/端口/波特率/帧数/文件大小/时长）、最近 200 帧滚动列表（等宽字体，可暂停滚动）、开始/停止、设置（端口、波特率、ID 黑白名单、容量上限、自动开录）、导出。
- 导出：FileProvider 系统分享；可复制到 U 盘挂载点（/mnt/usbhost1、/storage/udisk*）。

【P1】
- ID 白名单/黑名单过滤（只影响落盘，UI 仍显示）；
- 录制结束生成 summary.txt：按 ID 的帧数、平均周期、最小/最大间隔、最后数据；
- 手动发送单帧（默认关闭，二次确认）；
- 日志按 50MB 或 10 分钟切片。

【P2，可选做】
- DBC 导入并附加信号解析列；
- 内置 HTTP 服务，同 WiFi 浏览器下载日志。

【技术要求】
- Kotlin，minSdk 21，compileSdk 34，不依赖 GMS；依赖尽量少。
- 板载串口实现（无 root）：探测 /dev/ttyS*、ttyHS*、ttyMT* 等节点并检查可读写；用 toybox/busybox 的 `stty -F <dev> 115200 raw -echo -ixon -crtscts -opost min 0 time 5` 配置线路（min 0 time 5 让 read 每 0.5s 超时返回，避免阻塞读无法停止/重连），stty 全部不可用时仍尝试直接打开；再用 RandomAccessFile(dev,"rw") 读写。失败要给出每个节点的具体原因（不存在/权限不足/打开失败），并提示「放开 tty 节点权限」或「改用 USB 转串口」。
- 工程结构：
  MainActivity / service/LogService / transport/{CanTransport,SerialPortTransport,UsbSerialTransport,FrameParser} / model/CanFrame / recorder/{LogRecorder,LogFormatter,StorageGuard} / export/Exporter / util
- 代码需有中文注释，关键类写 KDoc；所有异常写 app.log（滚动 5×2MB）并在 UI 红字提示最近错误，禁止静默崩溃。

【验收】请确保满足：熄屏录制、拔插协议盒自动恢复、强杀进程最多丢 1 行且下次标记 INCOMPLETE、日志能被 Python 一行解析、导出到 U 盘帧数与 UI 一致、目录超限自动清理最旧。

【交付】
1) 完整可编译 Gradle 工程；2) 可安装 APK；3) README.md（安装/授权/选端口/导出/FAQ）；4) PROTOCOL.md（帧头识别规则与新增格式方法）；5) 一份样例日志。

请先输出：① 方案确认（含端口探测策略、帧头识别表、文件格式定稿）② 完整代码 ③ 自测步骤。若某处信息不足，用合理默认值并在代码里做成可配置项，不要停下来反复追问。
```

---

## 12. 极简版提示词（只想快速生成代码时用）

```text
用 Kotlin 写一个 Android 车机 App（雪铁龙 C3-XR 飞利浦车机，Android 10，横屏，无 root）：从 /dev/ttyS1（可配 9600/38400/115200，8N1；用 stty 配置 `raw min 0 time 5` 后 RandomAccessFile 直读，或 USB 转串口）读取协议盒的 CAN 数据，解析成 标准/扩展ID+DLC+数据，带 uptime_ms 和墙钟时间戳，以前台服务持续录制，逐 200ms flush 写入 /sdcard/CanLog/yyyyMMdd/can_yyyyMMdd_HHmmss.log，行格式：`0000012345 RX 7DF 8 02 01 0C 00 00 00 00 00`，扩展 ID 结尾加 x；每次录制写文件头和 # ---- END ---- 统计行，异常退出标记 INCOMPLETE；断线指数退避重连；日志目录上限 500MB 自动清理最旧；UI 提供启停、端口波特率选择、帧数/大小显示、最近 200 帧滚动、导出（FileProvider + 复制到U盘）。不要用 GMS 依赖。附带 README 和样例日志。
```

---

## 13. 已落地工程与需求映射

本目录下的 Android 工程就是按本文档实现的（**板载串口 / 无 root** 路线）。

| 需求 | 实现位置 |
|---|---|
| 串口探测 + stty 配置 + 读取 | `transport/SerialPortTransport.kt`、`SerialPorts` |
| USB 备选通道 | `transport/UsbSerialTransport.kt` |
| 字节流 → CAN 帧 / 原样保存 | `transport/FrameParser.kt` |
| 时间戳（单调 + 墙钟） | `model/CanFrame.kt`、`LogService.readLoop()` |
| 队列 + 200ms 落盘 + 切片 | `recorder/LogRecorder.kt` |
| 日志格式（TXT/CSV/汇总） | `recorder/LogFormatter.kt` |
| 目录选择 / 500MB 上限 / 空间检查 | `recorder/StorageGuard.kt` |
| 异常退出补 INCOMPLETE | `recorder/LogRecovery.kt` + `*.open` 标记 |
| 前台服务 / 断线重连 / 通知 | `service/LogService.kt` |
| 开机自启 | `service/BootReceiver.kt` |
| UI（状态条 / 预览 / 启停 / 导出） | `MainActivity.kt` + `res/layout/activity_main.xml` |
| 分享 / 复制到 U 盘 | `export/Exporter.kt` |
| ID 白黑名单 | `util/IdFilter.kt` |
| PC 端解析 | `tools/parse_canlog.py` |
| 使用与排查文档 | `README.md`、`PROTOCOL.md` |

构建、安装、无 root 权限排查（含 SELinux）见 `README.md`。

