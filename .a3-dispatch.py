"""A3 收口（修复版）：一次性把 31 个三段式 S2C 包收进 ClientPayloadDispatcher。

第一阶段【修复】：删掉 clientHandler 字段与 setClientHandler 的开头行——
    单行式：public static void setClientHandler(Consumer<X> h) { clientHandler = h; }
    三行式：public static void setClientHandler(Consumer<X> h) {\n        clientHandler = h;\n    }
上一版脚本只删了开头行，三行式会留下孤儿 `clientHandler = handler;` + `}`。本阶段按
「见到 clientHandler = handler; 就吞掉它和紧随的那一个 }」的状态机统一处理两种形态。

第二阶段【改写】：handleClient 正文换成一行 dispatch。接受三种既有形态：
    转发（带/不带判空、带/不带 else Log.warn）。形状不符即跳过并打印，不猜。
"""
import re
import pathlib

ROOT = pathlib.Path("src/main/java")
IMPORT_DISPATCH = "import com.wsteam.wandscape.foundation.networking.ClientPayloadDispatcher;"
IMPORT_CONSUMER = "import java.util.function.Consumer;"

FIELD_RE = re.compile(r"^[ \t]*private static (?:java\.util\.function\.)?Consumer<\w+> clientHandler\b.*$")
SETTER_OPEN_RE = re.compile(r"^[ \t]*public static void setClientHandler\(.*$")
BODY_LINE_RE = re.compile(r"^[ \t]*clientHandler = handler;[ \t]*$")
CLOSE_RE = re.compile(r"^[ \t]*\}[ \t]*$")

HEADER_RE = re.compile(r"^(?P<ind>[ \t]*)public static void handleClient\((?P<type>\w+) (?P<name>\w+)\) \{\n", re.M)

FORWARD_BODIES = re.compile(
    r"(?:if \(clientHandler != null\) \{\s*clientHandler\.accept\(packet\);\s*\}"
    r"(?:\s*else \{\s*Log\.warn\(.*?\);\s*\})?"
    r"|if \(clientHandler != null\) clientHandler\.accept\(packet\);"
    r"|clientHandler\.accept\(packet\);)$",
    re.S,
)


def strip_field_and_setter(src):
    out, swallow_close = [], False
    for ln in src.split("\n"):
        if BODY_LINE_RE.match(ln):
            swallow_close = True
            continue
        if swallow_close and CLOSE_RE.match(ln):
            swallow_close = False
            continue
        swallow_close = False
        if FIELD_RE.match(ln) or SETTER_OPEN_RE.match(ln):
            continue
        out.append(ln)
    return "\n".join(out)


def method_span(src, m):
    open_at = m.end() - 2  # 正则以 "{\n" 结尾，回退两字符落在 '{'
    depth, i = 0, open_at
    while i < len(src):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                end = i + 1
                if end < len(src) and src[end] == "\n":
                    end += 1
                return m.start(), end, open_at + 1, i
        i += 1
    raise ValueError("花括号未配平")


def add_import(lines, imp):
    if any(ln.strip() == imp for ln in lines):
        return lines
    idxs = [i for i, ln in enumerate(lines) if ln.startswith("import ")]
    if not idxs:
        return lines
    target = imp[len("import "):].rstrip(";")
    for i in idxs:
        if lines[i][len("import "):].rstrip(";").strip() > target:
            lines.insert(i, imp)
            return lines
    lines.insert(idxs[-1] + 1, imp)
    return lines


def main():
    fixed, skipped = [], []

    for path in sorted(ROOT.rglob("*Packet.java")):
        src = path.read_text(encoding="utf-8")
        if "clientHandler" not in src:
            continue
        rel = path.relative_to(ROOT).as_posix()
        needs_rewrite = "clientHandler.accept" in src

        if needs_rewrite:
            header = HEADER_RE.search(src)
            if not header:
                skipped.append((rel, "找不到 handleClient 头"))
                continue
            start, end, b0, b1 = method_span(src, header)
            if not FORWARD_BODIES.fullmatch(src[b0:b1].strip()):
                skipped.append((rel, "正文非转发形态: " + " ".join(src[b0:b1].split())[:90]))
                continue
            new = (
                f"{header.group('ind')}public static void handleClient({header.group('type')} {header.group('name')}) {{\n"
                f"{header.group('ind')}    ClientPayloadDispatcher.dispatch({header.group('name')});\n"
                f"{header.group('ind')}}}\n"
            )
            src = src[:start] + new + src[end:]

        src = strip_field_and_setter(src)

        if "Consumer<" not in src:
            src = "\n".join(ln for ln in src.split("\n") if ln.strip() != IMPORT_CONSUMER)
        src = "\n".join(add_import(src.split("\n"), IMPORT_DISPATCH))
        path.write_text(src, encoding="utf-8")
        fixed.append(rel)

    print(f"收口 {len(fixed)} 个包")
    for rel in fixed:
        print("  " + rel)
    if skipped:
        print(f"\n跳过 {len(skipped)} 个：")
        for rel, why in skipped:
            print(f"  {rel} — {why}")


if __name__ == "__main__":
    main()
