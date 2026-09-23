"""一次性脚本：把 Wandscape.java 的 410 行注册墙转成 PayloadRegistry 的 s2c/c2s 表。

输入 = Wandscape.java 里 onRegisterPayloads 中从第一个 .playTo* 到最后一个 `);` 的墙。
输出 = PayloadRegistry.java 全文（打印到 stdout，不落地）。

解析规则：每个条目是 `.playToClient(` / `.playToServer(` 起、括号配平止，内部三个顶层逗号分隔
的实参 = 类型 / codec / 处理器。处理器只接受两种已归一化的形态：
    ::handleClient / ::handleServer                        （方法引用）
    (packet, ctx) -> X.handleClient(packet)                （旧式 lambda，折叠成方法引用）
    (packet, ctx) -> ctx.enqueueWork(() -> X.handleClient(packet))
    (packet, ctx) -> X.handleServer(packet, (ServerPlayer) ctx.player())
    等等 —— 一律按「取 X + 方向」折算，其余的形态报错不猜。
"""
import re
import sys
import pathlib

SRC = pathlib.Path("src/main/java/com/wsteam/wandscape/Wandscape.java")

ENTRY_RE = re.compile(r"^\s*\.playTo(Client|Server)\($")


def read_entries(lines, start, end):
    """从 start 扫到 end，产出 (方向, 类型式, codec 式, 处理器式, 前置注释行)。"""
    i = start
    pending_comments = []
    while i < end:
        raw = lines[i]
        stripped = raw.strip()
        if stripped.startswith("//"):
            pending_comments.append(stripped)
            i += 1
            continue
        m = ENTRY_RE.match(raw)
        if not m:
            if stripped:
                pending_comments = []
            i += 1
            continue

        direction = m.group(1)
        # 从这一行起做括号配平，把条目拼成一段文本
        buf = raw[raw.index(".playTo"):]
        depth = buf.count("(") - buf.count(")")
        while depth > 0:
            i += 1
            buf += "\n" + lines[i]
            depth = buf.count("(") - buf.count(")")
        inner = buf[buf.index("(") + 1: buf.rindex(")")]
        args = split_top_level(inner)
        yield direction, args, pending_comments
        pending_comments = []
        i += 1


def split_top_level(s):
    """按顶层逗号切分。"""
    out, depth, cur, in_str = [], 0, [], False
    prev = ""
    for ch in s:
        if in_str:
            cur.append(ch)
            if ch == '"' and prev != "\\":
                in_str = False
        else:
            if ch == '"':
                in_str = True
                cur.append(ch)
            elif ch in "([{":
                depth += 1
                cur.append(ch)
            elif ch in ")]}":
                depth -= 1
                cur.append(ch)
            elif ch == "," and depth == 0:
                out.append("".join(cur).strip())
                cur = []
            else:
                cur.append(ch)
        prev = ch
    if cur:
        out.append("".join(cur).strip())
    return out


def squeeze(expr):
    """去掉表达式内所有空白——墙里 FQN 会跨行，点号两侧因此夹着空格与缩进。"""
    return re.sub(r"\s+", "", expr)


def cls_of(type_expr):
    """从 X.TYPE 或 a.b.C.TYPE 取出 C。"""
    return type_expr[: -len(".TYPE")].rsplit(".", 1)[-1]


HANDLER_RE = re.compile(
    r"([\w.]+)::(handleClient|handleServer)"
    r"|\(packet,ctx\)->(?:ctx\.enqueueWork\(\(\)->([\w.]+)\.(handleClient|handleServer)\(packet\)\)"
    r"|([\w.]+)\.(handleClient|handleServer)\(packet\)"
    r"|([\w.]+)\.(handleClient|handleServer)\(packet,"
    r"(?:(?:\(net\.minecraft\.server\.level\.ServerPlayer\)|\(ServerPlayer\))?ctx\.player\(\)|ctx)\))"
)


def build(lines, start, end):
    body, imports, problems = [], set(), []

    for direction, args, comments in read_entries(lines, start, end):
        if len(args) != 3:
            problems.append(f"{direction} 实参数 {len(args)}: {squeeze(''.join(args))[:100]}")
            continue
        type_expr, codec_expr, handler = (squeeze(a) for a in args)

        if not type_expr.endswith(".TYPE"):
            problems.append(f"类型式非 X.TYPE: {type_expr[:80]}")
            continue
        cls = cls_of(type_expr)

        if codec_expr != type_expr[: -len(".TYPE")] + ".STREAM_CODEC":
            problems.append(f"codec 式与类型不匹配: {type_expr} / {codec_expr[:80]}")
            continue

        m = HANDLER_RE.fullmatch(handler)
        if not m:
            problems.append(f"处理器形态不认: {handler[:120]}")
            continue
        cls_h = next(g for g in m.groups() if g and g not in ("handleClient", "handleServer"))
        verb = next(g for g in m.groups() if g in ("handleClient", "handleServer"))
        if cls_h.rsplit(".", 1)[-1] != cls:
            problems.append(f"处理器类 {cls_h} 与类型类 {cls} 不符")
            continue

        want = "handleClient" if direction == "Client" else "handleServer"
        if verb != want:
            problems.append(f"{direction} 方向却调 {verb}: {cls}")
            continue

        fn = "s2c" if direction == "Client" else "c2s"
        if comments:
            body.append("")
            body.extend("        " + c for c in comments)
        body.append(f"        {fn}(r, {cls}.TYPE, {cls}.STREAM_CODEC, {cls}::{verb});")
        imports.add(cls)

    return body, imports, problems


OUT = pathlib.Path("src/main/java/com/wsteam/wandscape/foundation/networking/PayloadRegistry.java")
ROOT = pathlib.Path("src/main/java")

HEADER = '''package com.wsteam.wandscape.foundation.networking;

@@IMPORTS@@

/**
 * 全部网络包的注册表——86 个 payload 的类型契约与收发处理器在此一次登记。
 *
 * <p>集中在此的理由：注册需要的平台符号（{@code RegisterPayloadHandlersEvent} /
 * {@code PayloadRegistrar} / {@code IPayloadContext}）因此全仓库只出现在本文件，
 * 而不是散进 86 个包类。包类只管「字段 + 怎么写怎么读」，不碰注册。
 *
 * <p>两条登记通道各一个 helper，把方向差异收在同一处：
 * <ul>
 *   <li>{@link #s2c} 服务端 → 客户端，处理器是包的静态 {@code handleClient(包类型)}。</li>
 *   <li>{@link #c2s} 客户端 → 服务端，处理器是包的静态 {@code handleServer(包类型, ServerPlayer)}。
 *       玩家实例由本 helper 从上下文取出，包类只收结果，不接触平台上下文类型。</li>
 * </ul>
 *
 * <p>public 是为了 {@code compat/curios} 复用同一条通道——Curios 只在加载时注册自己的包，
 * 但方向适配规则应与主干完全一致。
 *
 * <p>新增包时只在本表加一行，并依包归属域把处理器写进对应包类。
 */
public final class PayloadRegistry {

    private PayloadRegistry() {}

    /** 注册全部 payload。由 {@code Wandscape} 在 {@code RegisterPayloadHandlersEvent} 中调用。 */
    public static void register(RegisterPayloadHandlersEvent event) {
        var r = event.registrar(MODID).versioned("1.0");

@@BODY@@

        // Curios 兼容：法师饰品栏打开请求（仅 Curios 加载时在实现类内注册；无 Curios 时此处不引用任何 Curios 类）
        CuriosCompat.registerPayloads(r);
    }

    /** 登记一条服务端 → 客户端的包。 */
    public static <T extends CustomPacketPayload> void s2c(
            PayloadRegistrar r,
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            Consumer<T> handler) {
        r.playToClient(type, codec, (payload, ctx) -> handler.accept(payload));
    }

    /**
     * 登记一条客户端 → 服务端的包。
     *
     * <p>上下文里的玩家只在服务端的 serverbound 路径上存在，取到的必须是 {@link ServerPlayer}；
     * 真拿到别的形态说明包走错了方向，记警告后丢弃，不打断整条连接。
     */
    public static <T extends CustomPacketPayload> void c2s(
            PayloadRegistrar r,
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            BiConsumer<T, ServerPlayer> handler) {
        r.playToServer(type, codec, (payload, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) {
                handler.accept(payload, player);
            } else {
                Log.warnOnce(LogCategory.NETWORK, "not-server-player:" + type.id(),
                        "Serverbound payload {} arrived without a ServerPlayer — dropped", type.id());
            }
        });
    }
}
'''


def fqn_index():
    """扫全库 *Packet.java 建 简单类名 -> 全限定名。重名直接报错。"""
    idx = {}
    for p in ROOT.rglob("*Packet.java"):
        fqn = p.relative_to(ROOT).as_posix()[: -len(".java")].replace("/", ".")
        name = fqn.rsplit(".", 1)[1]
        if name in idx and idx[name] != fqn:
            raise SystemExit(f"类名冲突: {name} -> {idx[name]} / {fqn}")
        idx[name] = fqn
    return idx


EXTRA_IMPORTS = [
    # 非包类，fqn_index 扫不到，显式列出
    "com.wsteam.wandscape.compat.curios.CuriosCompat",
    "com.wsteam.wandscape.foundation.log.Log",
    "com.wsteam.wandscape.foundation.log.LogCategory",
]


def emit(body, imports):
    idx = fqn_index()
    missing = sorted(c for c in imports if c not in idx)
    if missing:
        raise SystemExit(f"找不到全限定名: {missing}")
    foreign = sorted([idx[c] for c in imports] + EXTRA_IMPORTS)
    blocks = [
        "\n".join(f"import {f};" for f in foreign),
        "\n".join([
            "import net.minecraft.network.RegistryFriendlyByteBuf;",
            "import net.minecraft.network.codec.StreamCodec;",
            "import net.minecraft.network.protocol.common.custom.CustomPacketPayload;",
            "import net.minecraft.server.level.ServerPlayer;",
            "import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;",
            "import net.neoforged.neoforge.network.registration.PayloadRegistrar;",
        ]),
        "\n".join([
            "import java.util.function.BiConsumer;",
            "import java.util.function.Consumer;",
        ]),
        "import static com.wsteam.wandscape.Wandscape.MODID;",
    ]
    text = HEADER.replace("@@IMPORTS@@", "\n\n".join(blocks)).replace("@@BODY@@", "\n".join(body))
    OUT.write_text(text, encoding="utf-8")
    print(f"写入 {OUT}（{len(text.splitlines())} 行）", file=sys.stderr)


def main():
    lines = SRC.read_text(encoding="utf-8").split("\n")
    wall = [i for i, l in enumerate(lines) if ENTRY_RE.match(l)]
    start, end = wall[0], wall[-1]
    tail = next(i for i in range(end, len(lines)) if "CuriosCompat.registerPayloads" in lines[i])

    body, imports, problems = build(lines, start, tail)
    if problems:
        print(f"未解析 {len(problems)} 条：", file=sys.stderr)
        for p in problems:
            print("  " + p, file=sys.stderr)
        sys.exit(1)
    n = sum(1 for b in body if b.strip().startswith(("s2c(", "c2s(")))
    print(f"条目 {n} 条，涉及类 {len(imports)} 个", file=sys.stderr)
    emit(body, imports)


if __name__ == "__main__":
    main()
