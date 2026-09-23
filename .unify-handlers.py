"""一次性脚本：把 handleServer(X pkt, IPayloadContext ctx) 的 ctx 守卫折叠成 ServerPlayer 形参。

13 个包的开头逐字相同：
    public static void handleServer(X pkt, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
改成：
    public static void handleServer(X pkt, ServerPlayer sp) {
等价性：playToServer 处理器只会在服务端跑，ctx.player() 必为 ServerPlayer；
原守卫是防御性的，现已由 PayloadRegistry.c2s 统一承担。守卫改用参数后不再需要。
"""
import re
import pathlib

ROOT = pathlib.Path("src/main/java")

PAT = re.compile(
    r"public static void handleServer\((?P<type>\w+) (?P<name>\w+), IPayloadContext ctx\) \{\n"
    r"[ \t]*if \(!\(ctx\.player\(\) instanceof ServerPlayer sp\)\) return;\n"
)

IMPORT = "import net.neoforged.neoforge.network.handling.IPayloadContext;"


def main():
    n_files = 0
    for path in ROOT.rglob("*Packet.java"):
        src = path.read_text(encoding="utf-8")
        new, n = PAT.subn(
            lambda m: f"public static void handleServer({m['type']} {m['name']}, ServerPlayer sp) {{\n",
            src,
        )
        if n == 0:
            continue
        # 折叠后若再无 IPayloadContext 引用，连 import 一起摘掉
        body = "\n".join(
            ln for ln in new.split("\n") if ln.strip() != IMPORT
        )
        if "IPayloadContext" not in body:
            new = body
        path.write_text(new, encoding="utf-8")
        n_files += 1
        print(f"  {path.relative_to(ROOT)}")
    print(f"改动 {n_files} 个包")


if __name__ == "__main__":
    main()
