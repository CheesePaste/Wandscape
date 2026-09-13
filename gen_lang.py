#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""gen_lang.py — 把 lang_src/ 下的分域源文件编译成 lang/zh_cn.json + lang/en_us.json。

单一内容源
    lang_src/**/*.json      键 → {"zh_cn": …, "en_us": …}

同一份源两处输出：游戏真正加载的只有 assets/wandscape/lang/<locale>.json（原版
ClientLanguage 一个命名空间一个语言只读一个文件），所以源文件按域拆开、编译时合并。

**中英写在同一条目里**，不是两个平行目录——这样「漏翻」在结构上就不可能发生，
review 时也一眼能看到两种语言的措辞对不对得上。

生成物（提交进仓库）
    src/main/resources/assets/wandscape/lang/zh_cn.json
    src/main/resources/assets/wandscape/lang/en_us.json

源文件怎么分（键 → 目标文件）
    wandscape.character_name.*  → text/names.json     法师名字池
    bubble.*                    → text/bubbles.json   气泡语料
    gui.wandscape.<seg>.*       → gui/<seg>.json      一个界面一个文件
    其余按首段                → content/<seg>.json

用法
    python gen_lang.py            # 编译（覆盖 lang/*.json）
    python gen_lang.py --check    # 只校验，不写文件
    python gen_lang.py --split    # 首次：把现有 lang/*.json 拆成 lang_src/（已有内容则拒绝）
"""

import io
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "lang_src"
OUT = ROOT / "src/main/resources/assets/wandscape/lang"
LANGS = ("zh_cn", "en_us")

NAMES_PREFIX = "wandscape.character_name."
BUBBLE_PREFIX = "bubble."
GUI_PREFIX = "gui.wandscape."

# 占位符：%s / %d / %1$s / %% 都算，用于中英对照自检
FORMAT_RE = re.compile(r"%(?:(\d+)\$)?[sd]|%%")


def format_slots(text):
    """把占位符归一成「第几个参数」的列表。%s %s 与 %1$s %2$s 等价，
    译文换语序（%2$s 在前）也是合法的，所以比的是**参数序号集合**而不是字面量。"""
    slots, nxt = [], 1
    for m in FORMAT_RE.finditer(text):
        if m.group(0) == "%%":
            continue
        if m.group(1):
            slots.append(int(m.group(1)))
        else:
            slots.append(nxt)
            nxt += 1
    return sorted(slots)


def target_file(key):
    """键 → lang_src 下的相对路径。规则集中在这一个函数里，改拆分只改这里。"""
    if key.startswith(NAMES_PREFIX):
        return "text/names.json"
    if key.startswith(BUBBLE_PREFIX):
        return "text/bubbles.json"
    if key.startswith(GUI_PREFIX):
        seg = key[len(GUI_PREFIX):].split(".")[0]
        return "gui/%s.json" % seg
    return "content/%s.json" % key.split(".")[0]


def read_json(path):
    with io.open(path, encoding="utf-8") as f:
        return json.load(f)


def write_json(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(obj, ensure_ascii=False, indent=2) + "\n")


def load_sources():
    """lang_src/**/*.json → {key: {"zh_cn":…, "en_us":…}}，顺带查重复键。"""
    if not SRC.is_dir():
        sys.exit("找不到 %s —— 先跑 python gen_lang.py --split" % SRC)
    merged, origin, dup = {}, {}, []
    for path in sorted(SRC.rglob("*.json")):
        rel = path.relative_to(SRC).as_posix()
        for key, val in read_json(path).items():
            if key in merged:
                dup.append("%s（%s 与 %s）" % (key, origin[key], rel))
                continue
            merged[key] = val
            origin[key] = rel
    if dup:
        sys.exit("重复键：\n  " + "\n  ".join(dup))
    if not merged:
        sys.exit("lang_src/ 里一个键都没有")
    return merged, origin


def validate(merged):
    """返回 (errors, warnings)。错误挡写盘，警告只打印。"""
    errors, warnings, identical = [], [], 0
    for key, val in sorted(merged.items()):
        if not isinstance(val, dict):
            errors.append("%s：值必须是 {\"zh_cn\": …, \"en_us\": …}，实际是 %s" % (key, type(val).__name__))
            continue
        # null = 待补；空串 = 合法的「没有文案」（如中文名之间的分隔符就是 ""），两者不能混为一谈
        missing = [lg for lg in LANGS if lg not in val or val[lg] is None]
        if missing:
            errors.append("%s：%s 还是 null，待补" % (key, "/".join(missing)))
            continue
        bad = [lg for lg in LANGS if not isinstance(val[lg], str)]
        if bad:
            errors.append("%s：%s 不是字符串" % (key, "/".join(bad)))
            continue
        if val["zh_cn"] == val["en_us"]:
            # 专有名词、HP/MP、zzz… 这类本来就该相同，不算错，只统计
            identical += 1
        zf, ef = format_slots(val["zh_cn"]), format_slots(val["en_us"])
        if zf != ef:
            warnings.append("%s：占位符参数不对应 —— zh %s / en %s" % (key, zf or "无", ef or "无"))
    if identical:
        warnings.append("中英完全相同的条目 %d 条（专有名词之类正常，翻漏了要自己认）" % identical)
    return errors, warnings


def cmd_check():
    merged, origin = load_sources()
    errors, warnings = validate(merged)
    # 产物漂移：lang/*.json 是否还等于 lang_src 编译出来的结果（有人手改过产物就会不一致）
    for lg in LANGS:
        path = OUT / ("%s.json" % lg)
        want = {k: v[lg] for k, v in sorted(merged.items())}
        got = read_json(path) if path.is_file() else {}
        if got != want:
            diff = sorted(set(want) ^ set(got)) or sorted(k for k in set(want) & set(got) if want[k] != got[k])
            warnings.append("lang/%s.json 与 lang_src 不一致（例：%s）—— 跑一次 python gen_lang.py"
                            % (lg, "、".join(diff[:3])))
    report(merged, origin, errors, warnings, written=False)
    return 1 if errors else 0


def cmd_split():
    if SRC.is_dir() and any(SRC.rglob("*.json")):
        sys.exit("%s 里已有文件，拒绝覆盖。要重拆先自己删掉它。" % SRC)
    zh = read_json(OUT / "zh_cn.json")
    en = read_json(OUT / "en_us.json")
    buckets, orphan = {}, []
    for key in sorted(set(zh) | set(en)):
        if key not in zh or key not in en:
            orphan.append(key)
        buckets.setdefault(target_file(key), {})[key] = {
            "zh_cn": zh.get(key), "en_us": en.get(key)}
    for rel, data in buckets.items():
        write_json(SRC / rel, data)
    print("拆分完成：%d 个键 → %d 个源文件" % (sum(len(v) for v in buckets.values()), len(buckets)))
    for key in orphan:
        print("  注意：%s 只在一个语言里有（已按 null 拆出，补完再编译）" % key)
    return 0


def report(merged, origin, errors, warnings, written):
    files = {}
    for key, rel in origin.items():
        files[rel] = files.get(rel, 0) + 1
    print("源文件 %d 个，键 %d 条" % (len(files), len(merged)))
    for rel in sorted(files):
        print("   %-34s %4d" % (rel, files[rel]))
    if warnings:
        print()
        for w in warnings:
            print("   [警告] %s" % w)
    if errors:
        print()
        for e in errors:
            print("   [错误] %s" % e)
    print()
    if errors:
        print("校验未通过，%d 个错误，未写盘。" % len(errors))
    elif written:
        print("已写出 %s" % "、".join("lang/%s.json" % lg for lg in LANGS))
    else:
        print("校验通过。")


def cmd_compile():
    merged, origin = load_sources()
    errors, warnings = validate(merged)
    if errors:
        report(merged, origin, errors, warnings, written=False)
        return 1
    for lg in LANGS:
        write_json(OUT / ("%s.json" % lg), {k: v[lg] for k, v in sorted(merged.items())})
    report(merged, origin, errors, warnings, written=True)
    return 0


def main():
    args = sys.argv[1:]
    if "--split" in args:
        return cmd_split()
    if "--check" in args:
        return cmd_check()
    return cmd_compile()


if __name__ == "__main__":
    sys.exit(main())
