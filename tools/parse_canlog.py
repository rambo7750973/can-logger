#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CanLogger 日志解析工具（PC 端，Python 3.8+，无第三方依赖）

用法：
    python parse_canlog.py <file.log>                       # 概览：总帧数、时长、ID 统计
    python parse_canlog.py <file.log> --id 7DF              # 只看某个 ID 的数据
    python parse_canlog.py <file.log> --id 7DF --gaps 150   # 打印间隔 >150ms 的位置
    python parse_canlog.py <file.log> --csv out.csv         # 转成 CSV
    python parse_canlog.py <file.log> --diff                # 数据变化时才打印（找状态位很有用）

日志行格式（TXT）：
    0000012345 RX 7DF 8 02 01 0C 00 00 00 00 00
    ^uptime_ms ^dir ^id(扩展帧结尾带 x) ^dlc ^data

未识别的原始数据行：
    0000012350 RX RAW 2E 0C 24 01 02 03 ...
"""

import argparse
import datetime
import os
import sys
from collections import OrderedDict

MAX_TS = 10_000_000_000  # 第一列定宽 10 位


class Frame:
    __slots__ = ("ts", "dir", "can_id", "ext", "dlc", "data", "note")

    def __init__(self, ts, direction, can_id, ext, dlc, data, note=""):
        self.ts = ts
        self.dir = direction
        self.can_id = can_id
        self.ext = ext
        self.dlc = dlc
        self.data = data
        self.note = note

    def data_hex(self):
        return " ".join(f"{b:02X}" for b in self.data)

    def __repr__(self):
        return f"<{self.ts} {self.dir} {self.can_id} {self.dlc} {self.data_hex()}>"


def parse_line(line):
    s = line.strip()
    if not s or s.startswith("#"):
        return None

    parts = s.split()
    if len(parts) < 4:
        return None

    try:
        ts = int(parts[0])
    except ValueError:
        return None

    direction = parts[1]
    token = parts[2]

    # 原始/未识别数据：0000012350 RX RAW 2E 0C 24 ...
    if token in ("RAW", "TEXT", "BIN2E"):
        data = bytes(int(x, 16) for x in parts[3:] if len(x) == 2)
        return Frame(ts, direction, token, False, len(data), data, note=token)

    ext = token.endswith("x")
    if ext:
        token = token[:-1]

    try:
        can_id = int(token, 16)
    except ValueError:
        return None

    try:
        dlc = int(parts[3])
    except (ValueError, IndexError):
        dlc = 0

    data = bytes(int(x, 16) for x in parts[4:] if len(x) == 2)
    return Frame(ts, direction, can_id, ext, dlc, data)


def load(path):
    frames = []
    head = []
    tail = []
    seen_data = False
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            if line.startswith("#"):
                (tail if seen_data else head).append(line.rstrip())
                continue
            fr = parse_line(line)
            if fr:
                seen_data = True
                frames.append(fr)
    return frames, head, tail


def id_text(fr):
    if isinstance(fr.can_id, str):
        return fr.can_id
    return f"{fr.can_id:08X}x" if fr.ext else f"{fr.can_id:03X}"


def human_dur(ms):
    s = ms / 1000.0
    return f"{int(s // 3600):02d}:{int(s % 3600 // 60):02d}:{s % 60:06.3f}"


def cmd_overview(frames, head, tail):
    print("=" * 78)
    print(f"文件行数: {len(frames)}")
    if not frames:
        print("没有可解析的帧。")
        return
    dur = frames[-1].ts - frames[0].ts
    print(f"时长(uptime): {human_dur(dur)}   平均 {len(frames) / max(dur / 1000.0, 1e-6):.1f} 行/秒")
    print("-" * 78)
    print(f"{'ID':<11}{'帧数':>9}{'周期ms(平均)':>14}{'最小':>8}{'最大':>8}  最后数据")
    stats = OrderedDict()
    for fr in frames:
        key = (fr.note, False) if fr.note else (fr.can_id, fr.ext)
        st = stats.get(key)
        if st is None:
            stats[key] = [1, fr.ts, fr.ts, None, None, fr.data]
        else:
            d = fr.ts - st[2]
            st[0] += 1
            st[2] = fr.ts
            if d >= 0:
                st[3] = d if st[3] is None or d < st[3] else st[3]
                st[4] = d if st[4] is None or d > st[4] else st[4]
            st[5] = fr.data

    for (can_id, ext), st in sorted(stats.items(), key=lambda kv: -kv[1][0]):
        cnt, first, last, mn, mx, data = st
        avg = (last - first) / (cnt - 1) if cnt > 1 else 0
        if isinstance(can_id, str):
            name = can_id
        elif ext:
            name = f"{can_id:08X}x"
        else:
            name = f"{can_id:03X}"
        print(f"{name:<11}{cnt:>9}{avg:>14.1f}{(mn or 0):>8}{(mx or 0):>8}  "
              f"{' '.join(f'{b:02X}' for b in data[:8])}")
    print("-" * 78)
    print("文件头:")
    for h in head[:8]:
        print("  " + h)
    if tail:
        print("文件尾:")
        for t in tail[-4:]:
            print("  " + t)


def cmd_id(frames, want, gaps_ms, diff_only):
    ext_want = want.upper().endswith("X")
    key = want.upper().rstrip("X")
    sel = [f for f in frames
           if not f.note and f.can_id == int(key, 16) and f.ext == ext_want]
    print(f"ID {want}: {len(sel)} 帧")
    prev = None
    prev_data = None
    for fr in sel:
        gap = "" if prev is None else f"  (+{fr.ts - prev}ms)"
        if gaps_ms and prev is not None and (fr.ts - prev) > gaps_ms:
            print(f"  ---- 间隔 {fr.ts - prev}ms ----")
        if diff_only and prev_data == fr.data:
            prev = fr.ts
            continue
        print(f"{fr.ts:>10}  {fr.data_hex()}{gap}")
        prev = fr.ts
        prev_data = fr.data


def cmd_csv(frames, out):
    with open(out, "w", encoding="utf-8", newline="") as f:
        f.write("uptime_ms,wall_time,dir,can_id,ext,dlc,data\n")
        for fr in frames:
            f.write(f'{fr.ts},"",{fr.dir},{id_text(fr)},'
                    f'{1 if fr.ext else 0},{fr.dlc},"{fr.data_hex()}"\n')
    print(f"已写出 {out}（{len(frames)} 行）")


def main():
    ap = argparse.ArgumentParser(description="CanLogger 日志解析")
    ap.add_argument("file", help="can_YYYYMMDD_HHmmss.log")
    ap.add_argument("--id", help="只看某个 CAN ID，例如 7DF / 18FEF100x")
    ap.add_argument("--gaps", type=int, default=0, help="打印超过 N ms 的间隔")
    ap.add_argument("--diff", action="store_true", help="数据无变化时不打印")
    ap.add_argument("--csv", help="导出 CSV 到指定文件")
    args = ap.parse_args()

    if not os.path.exists(args.file):
        print(f"文件不存在: {args.file}")
        return 1

    frames, head, tail = load(args.file)
    if args.csv:
        cmd_csv(frames, args.csv)
    elif args.id:
        cmd_id(frames, args.id, args.gaps, args.diff)
    else:
        cmd_overview(frames, head, tail)
    return 0


if __name__ == "__main__":
    sys.exit(main())
