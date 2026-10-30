# CAN 报文收集器（雪铁龙 C3-XR / 飞利浦车机）

在飞利浦后装车机上后台录制 CANBUS 报文，带时间戳完整落盘，支持导出到 U 盘 / 分享 / ADB 拉取。
**无需 root**（前提是车机 ROM 把协议盒串口节点放开为可读写，见第 6 节）。

---

## 1. 功能一览

| 能力 | 说明 |
|---|---|
| 通道 | 板载串口 `/dev/ttyS*`（默认，无需 root）/ USB-OTG 转串口（备选） |
| 波特率 | 9600 ~ 921600，8N1，无流控 |
| 解析 | 自动识别 candump / socketcan / `ID DLC 数据` 等文本格式；`0x2E` 二进制帧按长度切分原样保存；认不出的数据原样记录（`RAW`/`TEXT`/`BIN2E`），**不丢数据** |
| 时间戳 | 每行两个时间：单调时钟毫秒（不受改时间影响）+ 墙钟毫秒（写文件头/汇总用） |
| 落盘 | 每 200ms 批量 flush，断电最多丢 1 批 |
| 会话 | 每次开始录制生成 `can_yyyyMMdd_HHmmss.log`，正常停止写 `# ---- END ----` + 统计；异常退出下次启动自动补 `# INCOMPLETE` |
| 切片 | 单文件默认 50MB 自动切 `_part2`、`_part3` |
| 空间保护 | 目录总量默认 500MB，超出删最旧；剩余 <200MB 时拒绝开始并告警 |
| 后台 | 前台服务 + WakeLock，熄屏/切后台继续录；可选开机自启 |
| 导出 | 系统分享（FileProvider）/ 一键复制到 U 盘 / `adb pull` |
| 过滤 | CAN ID 白名单，或用 `-7DF` 写法做黑名单 |
| 汇总 | 每次录制生成 `*.summary.txt`：按 ID 的帧数、平均/最小/最大周期 |

---

## 2. 目录结构

```
车机报文收集/
├── 提示词-车机CAN报文收集.md          ← 需求/提示词文档
├── README.md                          ← 本文件
├── PROTOCOL.md                        ← 协议识别规则与扩展方法
├── build.gradle / settings.gradle / gradle.properties
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/philips/canlogger/
│       │   ├── CanLoggerApp.kt              Application、全局异常捕获
│       │   ├── MainActivity.kt              UI（端口/波特率/启停/导出）
│       │   ├── model/CanFrame.kt            CAN 帧 / 原始数据 / 时间格式化
│       │   ├── transport/
│       │   │   ├── CanTransport.kt          通道接口
│       │   │   ├── SerialPortTransport.kt   板载串口（stty + RandomAccessFile）
│       │   │   ├── UsbSerialTransport.kt    USB 转串口（备选）
│       │   │   └── FrameParser.kt           字节流 → CAN 帧
│       │   ├── recorder/
│       │   │   ├── LogRecorder.kt           队列 + 落盘 + 切片 + 汇总
│       │   │   ├── LogFormatter.kt          TXT / CSV 格式
│       │   │   ├── StorageGuard.kt          目录选择 / 空间守卫 / 清理
│       │   │   └── LogRecovery.kt           异常退出恢复
│       │   ├── service/
│       │   │   ├── LogService.kt            前台服务 + 读取线程 + 重连
│       │   │   ├── LoggerState.kt           UI 共享状态
│       │   │   └── BootReceiver.kt          开机自启
│       │   ├── export/Exporter.kt           分享 / 复制 U 盘
│       │   └── util/                        AppLog / Prefs / IdFilter
│       └── res/                             布局与资源
├── tools/
│   ├── parse_canlog.py                PC 端解析脚本（Python 3.8+）
│   └── parse_canlog.bat               Windows 双击包装
└── samples/
    └── can_20261030_101233.log        样例日志
```

---

## 3. 编译

本机需要 **JDK 17 + Android SDK 34**。

### 方式 A：Android Studio（推荐）
1. `File → Open` 选择本目录
2. 等待 Gradle Sync（首次会下载 AGP 8.3.2 / Gradle 8.4）
3. `Build → Build Bundle(s)/APK(s) → Build APK(s)`
4. 产物：`app/build/outputs/apk/debug/app-debug.apk`

### 方式 B：命令行
```bash
# Windows
gradlew.bat assembleDebug
# macOS / Linux
./gradlew assembleDebug
```
> 仓库里只有 `gradle/wrapper/gradle-wrapper.properties`，没有 wrapper jar。
> 用 Android Studio 打开会自动补；或在装了 Gradle 8.4 的机器上执行 `gradle wrapper`。

### 无网络环境
`app/build.gradle` 里唯一的外部依赖是 USB 转串口库：
```groovy
implementation 'com.github.mik3y:usb-serial-for-android:3.7.0'
```
板载串口方案不需要它。若同步失败，删掉这一行，同时删掉
`transport/UsbSerialTransport.kt` 和 `MainActivity.startUsbThenService()` 即可编译。

---

## 4. 安装到车机

```bash
adb connect 192.168.x.x:5555        # 车机开启 ADB 调试后
adb install -r app-debug.apk
```
或把 APK 拷到 U 盘，在车机的文件管理器里点击安装。

---

## 5. 首次使用

1. 打开 App，授予通知权限；Android 10+ 建议点 **「全盘权限」** 授权（否则日志写到 App 私有目录）。
2. **通道** 选「板载串口」；**串口** 列表里带 ✔ 的才是 App 有权限读写的节点，一般协议盒是 `/dev/ttyS1` 或 `/dev/ttyS3`。
3. **波特率** 按协议盒实际值选，不知道就依次试 `115200 → 9600 → 38400`。
4. 点 **「开始录制」**，状态条应出现 `● 录制中`，帧数开始增长。
5. 需要的话勾选 **「开机自动录制」**（车机重启后自动开录）。
6. 录完点 **「停止」**，文件立即写入 `# ---- END ----` 和统计行。

---

## 6. 无 root 下串口打不开？按顺序排查

### 6.1 看节点权限
```bash
adb shell ls -l /dev/ttyS* /dev/ttyHS* /dev/ttyMT*
```
- `crw-rw-rw-` → App 能直接打开，正常。
- `crw------- root root` → App 无权限，需要下面的 6.2 / 6.3。

### 6.2 让节点放开权限（临时验证）
```bash
adb root                # 车机 adb 支持 root 时
adb shell chmod 666 /dev/ttyS1
```
生效后 App 立刻可用。若重启失效，需要改 ROM 的 `init.rc` / `ueventd.rc`。

> **注意 SELinux**：有些车机即使 `ls -l` 显示 `crw-rw-rw-`，打开仍报 `EACCES (Permission denied)`，
> 那是 SELinux 拦的（`untrusted_app` 域默认禁止访问串口设备）。确认方法：
> ```bash
> adb shell getenforce                 # Enforcing 就是开着
> adb shell dmesg | grep avc           # 能看到 ttyS 相关的 denied 记录
> ```
> 临时验证：`adb shell setenforce 0`（重启恢复）。要长期可用只能改 ROM 的 sepolicy
> （把 App 加入允许访问串口的域），或直接改走 6.4 的 USB 方案。

### 6.3 确认是哪个口、什么波特率
```bash
adb shell "stty -F /dev/ttyS1 115200 raw -echo min 0 time 5; cat /dev/ttyS1 | head -c 64 | xxd"
```
有规律的数据（如 `2e 0c ...`）说明找对口了；全是 0 或没数据就换 `ttyS2/ttyS3`。

### 6.4 仍然不行 → 用 USB 方案（完全不需要 root）
1. 买一个 CH340 / CP2102 的 USB 转串口小板（3.3V/5V 跳线按协议盒电平选）。
2. 协议盒 TX → 小板 RX，RX → TX，GND 共地。
3. 车机 USB 口插上小板，App 里通道切到「USB 转串口」，点开始（会弹系统授权框）。

---

## 7. 日志在哪、怎么取

默认目录 `/sdcard/CanLog/`（无全盘权限时在 App 私有目录）：

```
/sdcard/CanLog/
└── 20261030/
    ├── can_20261030_101233.log          主日志（TXT）
    ├── can_20261030_101233.csv          可选 CSV
    ├── can_20261030_101233.summary.txt  按 ID 汇总
    └── can_20261030_101233.open         录制中才存在；存在说明上次异常退出
```

三种取法：
```bash
adb pull /sdcard/CanLog D:\canlog
```
或 App 里 **导出/分享** / **复制到U盘**（U 盘会多一个 `CanLog/` 目录）。

### 日志格式
```
0000012345 RX 7DF 8 02 01 0C 00 00 00 00 00
0000012358 RX 18FEF100x 8 FF 00 00 00 00 00 00 00
0000012361 RX RAW 2E 0C 24 A1 B2 C3 D4 E5
#  ↑单调毫秒  ↑方向  ↑ID(扩展帧带x)  ↑DLC  ↑数据
```
`#` 开头是注释，解析时跳过。

---

## 8. PC 端分析

```bash
py -3 tools\parse_canlog.py samples\can_20261030_101233.log
py -3 tools\parse_canlog.py xxx.log --id 0C9 --diff          # 只看某 ID，数据变化才显示
py -3 tools\parse_canlog.py xxx.log --id 0C9 --gaps 150      # 找出丢帧/断线位置
py -3 tools\parse_canlog.py xxx.log --csv out.csv            # 转 CSV 给 Excel
```

自己写脚本也只需一行：
```python
rows = [l.split() for l in open(f) if not l.startswith('#')]
# rows[i] = [uptime_ms, dir, can_id, dlc, b0, b1, ...]
```

---

## 9. 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| 状态条一直「连接失败：权限不足」 | 见第 6 节，节点是 `crw------- root` |
| 有帧数但数据全是 `RAW` | 协议盒输出的是二进制协议（如 `0x2E` 帧），已完整保存，按 `PROTOCOL.md` 反推即可 |
| 帧数很少或全是乱码 | 波特率不对，依次换 115200/9600/38400 |
| 熄屏后停止 | 车机把 App 电池优化限制掉了：设置里给本 App「允许后台运行/不受限制」，并确认通知栏常驻通知存在 |
| 日志目录里没有 `/sdcard/CanLog` | 没给全盘权限，日志在 `/sdcard/Android/data/com.philips.canlogger/files/CanLog` |
| 开机不自动录 | 需先勾选「开机自动录制」；部分车机会拦截自启，需在系统设置里允许 |
| `dropped` 大于 0 | 落盘跟不上或队列满（>20000 条积压），检查存储速度/是否被杀后台，帧率上限约 1 万帧/秒 |

---

## 10. 默认参数（改代码或 UI）

| 参数 | 默认 | 位置 |
|---|---|---|
| 候选串口 | 自动扫描 `/dev` + 常用节点 | `SerialPorts` |
| 波特率 | 115200 | UI / `Prefs` |
| 单文件切片 | 50MB | `Prefs.maxFileMb` |
| 目录总上限 | 500MB | `Prefs.maxTotalMb` |
| 剩余空间下限 | 200MB | `StorageGuard.MIN_FREE_BYTES` |
| 落盘批量间隔 | 200ms | `LogRecorder.loop()` |
| 重连退避 | 1s → 2s → 5s → 10s 封顶 | `LogService.readLoop()` |
| 预览行数 | 200 行，≤10 行/秒 | `LoggerState` / `LogService.preview()` |

---

## 11. 已知限制

- 只做**监听**，默认不向总线发送报文（`CanTransport.write()` 已预留）。
- 不支持 CAN FD（>8 字节会被记录，但不解析 FD 帧结构）。
- 二进制协议（`0x2E` 等）目前只做**切帧 + 原样保存**，不做字段解析；解析需要拿到协议盒文档后扩展 `FrameParser`。
- 无 root 时能否直接读串口，取决于车机 ROM 的节点权限，软件层面无法绕过。
