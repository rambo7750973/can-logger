# 协议说明：帧识别规则与扩展方法

这份文档解释 App 如何把串口字节流变成 CAN 帧，以及拿到日志后怎么反推协议盒格式。

---

## 1. 日志里有三种行

| 类型 | 样子 | 含义 |
|---|---|---|
| CAN 帧 | `0000012345 RX 7DF 8 02 01 0C 00 00 00 00 00` | 已成功解析 |
| 未识别文本 | `0000012361 RX TEXT 41 54 2B 52 44 0D 0A` | 是文本行，但不符合任何已知格式，按 ASCII 原样保存 |
| 未识别二进制 | `0000012365 RX RAW 2E 0C 24 A1 B2 C3 D4` | 不可打印字节，按块原样保存 |
| 二进制切帧 | `0000012370 RX BIN2E 2E 0C 24 A1 ... 3F` | 按 `0x2E` 帧头 + 长度字段切出的完整一帧 |

**核心原则：任何字节都不会被丢弃**。解析不出来的数据一定以 `TEXT` / `RAW` / `BIN2E` 原样落在日志里，
所以第一次装车就能拿到可用于反推协议的完整数据。

---

## 2. 文本格式识别规则（`FrameParser.parseText`）

按顺序匹配，命中即返回：

| # | 模式 | 正则 | 示例 |
|---|---|---|---|
| 0 | candump 前缀剥离 | `^\([^)]*\)\s+\S+\s+(.+)$` | `(123.456) can0 7DF#02010C...` → `7DF#02010C...` |
| 1 | socketcan | `^(?:0x)?([0-9A-Fa-f]{1,8})#([0-9A-Fa-f. ]*)$` | `7DF#02010C00000000`、`7DF#8.02.01.0C` |
| 2 | candump -L | `^[tT]([0-9A-Fa-f]+)$` | `t7DF802010C00000000`、`T18FEF1008FF...` |
| 3 | ID + DLC + 数据 | `^(?:0x)?([0-9A-Fa-f]{1,8})[ ,;:]+((?:[0-9A-Fa-f]{1,2}[ ,;:]*)+)$` | `7DF 8 02 01 0C ...`、`7DF,8,02,01`、`7DF 02 01 0C` |

补充规则：

- ID 长度 **≤3 位** → 标准帧；**>3 位** → 扩展帧（日志里 ID 末尾加 `x`）。
- 第 3 种格式里，若第一个数据 token 只有 1 个字符，视为 DLC 丢弃（`7DF 8 02 01` 里的 `8`）。
- 数据必须是偶数个十六进制字符，否则该行判为 `TEXT`。
- 大小写、`0x` 前缀、`,` `;` `:` 分隔符都兼容。

### 新增一种文本格式

在 `FrameParser.parseText()` 里加一段正则即可，例如协议盒输出 `CAN,7DF,8,02,01,0C`：

```kotlin
private val reCommaCan = Regex("""^CAN,([0-9A-Fa-f]{1,8}),(\d{1,2}),((?:[0-9A-Fa-f]{2},?)+)$""")

// 在 parseText 里、return null 之前插入：
reCommaCan.matchEntire(s)?.let { m ->
    return buildFrame(m.groupValues[1], m.groupValues[3].replace(",", ""), uptimeMs, wallMs, null)
}
```

---

## 3. 二进制帧（0x2E 帧头）

后装市场的协议盒（睿志诚 Raise、欣朴、精邦等）大量使用「帧头 + 长度 + 命令 + 数据 + 校验」结构，
其中 `0x2E` 最常见，因此 App 内置了这个切帧规则：

```
+------+------+------+------------------+--------+
| 0x2E | LEN  | CMD  |      PAYLOAD     | CHK/无 |
+------+------+------+------------------+--------+
         └── LEN = 其后字节数（不含 0x2E 和 LEN 本身）
```

处理逻辑（`FrameParser.feed`）：

1. 遇到 `0x2E` → 读下一字节 `LEN`；
2. 若 `LEN > 510` 视为异常，只丢这 1 个字节，继续；
3. 缓冲区凑够 `2 + LEN` 字节 → 整帧以 `BIN2E` 原样落盘；
4. 不够 → 等下一批数据。

**它现在只保证「不丢字节、按帧对齐」，不解析字段。** 要把它变成真正的 CAN 帧，
需要根据你的协议盒文档补充 `cmd → CAN ID / 数据位置` 的映射（见第 5 节）。

> 其它帧头（`0xAA 0x55`、`0x5A 0xA5`、`0x7E`）目前会以 `RAW` 原样记录。
> 确认帧头后，在 `feed()` 的 `when` 里仿照 `0x2E` 分支加一段即可。

---

## 4. 拿到日志后怎么反推协议（实操步骤）

1. **先看有没有文本特征**
   ```bash
   py -3 tools/parse_canlog.py can_xxx.log
   ```
   如果 RAW 占比高，用十六进制工具看前 512 字节：
   ```bash
   head -c 2048 can_xxx.log | xxd
   ```
2. **找固定帧头**：统计出现频率最高的前 2 个字节。
3. **验证长度字段**：取 20 帧，检查 `第 2 字节 + 2 == 帧间距`。成立就说明是 `LEN` 字段。
4. **找周期帧**：`--gaps` 看同一 ID 的间隔是否稳定（10/20/50/100ms 是典型车载周期）。
5. **找变化字节**：操作一个功能（开门、打转向、踩刹车），`--diff` 对比数据变化，锁定字节位置。
6. **建立映射**：把 `ID + 字节偏移 + 位` 记到下表，再回到代码里解析。

推荐记录表：

| 功能 | CAN ID | 字节 | 位 | 取值含义 | 验证方式 |
|---|---|---|---|---|---|
| 车速 | 0C9 | 2-3 | - | 0.01 km/h | 举升/路试对比 GPS |
| 车门 | 3D1 | 0 | bit0-3 | 1=开 | 逐个开门 |
| 转向灯 | 3D1 | 1 | bit0/1 | 左/右 | 打灯 |

> 提醒：C3-XR 这类车的舒适 CAN 是 125kbps，动力 CAN 是 500kbps。协议盒通常只透传它需要的那一路，
> 想拿全车数据，更可靠的做法是把 CAN 分析仪直接并在 OBD 的 6/14 脚上。

---

## 5. 把二进制帧变成 CAN 帧（代码扩展点）

假设你的协议盒文档写的是：

```
0x2E LEN 0x24 ID_H ID_L DLC D0..D7 CHK
```

在 `FrameParser.feed()` 里，`0x2E` 分支中 `emitRaw(copyOf(i, total), "BIN2E", ...)` 的位置，
改成先尝试解析、失败再落 RAW：

```kotlin
val frameBytes = copyOf(i, total)
val parsed = decodeRaise24(frameBytes, uptimeMs, wallMs)
if (parsed != null) sink.onFrame(parsed) else emitRaw(frameBytes, "BIN2E", uptimeMs, wallMs, sink)
```

```kotlin
private fun decodeRaise24(b: ByteArray, uptimeMs: Long, wallMs: Long): CanFrame? {
    if (b.size < 6 || (b[0].toInt() and 0xFF) != 0x2E) return null
    if ((b[2].toInt() and 0xFF) != 0x24) return null          // 只处理 CAN 数据命令
    val id = ((b[3].toInt() and 0xFF) shl 8) or (b[4].toInt() and 0xFF)   // 扩展帧需 4 字节
    val dlc = (b[5].toInt() and 0xFF).coerceIn(0, 8)
    if (b.size < 6 + dlc) return null
    val data = b.copyOfRange(6, 6 + dlc)
    return CanFrame(uptimeMs, wallMs, Dir.RX, id, false, dlc, data)
}
```

校验位（CHK）也要验证，通常是前面所有字节求和取低 8 位，具体以文档为准。

---

## 6. 校验和速查（常见）

| 算法 | 代码 |
|---|---|
| 累加和 | `sum(0..n-1) and 0xFF` |
| 累加和取反 | `(sum.inv() + 1) and 0xFF` |
| 异或 | `reduce { a, b -> a xor b }` |
| CRC8 (poly 0x31, init 0xFF) | 见协议盒文档 |

在 `FrameParser` 里验证校验和，能显著降低「错帧被当成好帧」的概率。

---

## 7. 排查清单

- [ ] 日志里 RAW 的比例是多少？>90% 说明端口/波特率可能不对（不是协议问题）
- [ ] 有没有稳定出现的 `2E` / `AA55` 帧头？
- [ ] 相邻帧间距是否等于长度字段 + 2？
- [ ] 同一 ID 的周期是否稳定（10/20/50/100ms）？
- [ ] 操作单个功能时，是否只有 1~2 个字节变化？
- [ ] 校验和能否用累加/异或复现？
