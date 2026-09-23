"""一次性脚本：把全库 PacketDistributor.sendToXxx( 换成 Net.toXxx(，并修 import。

纯 token 替换，无判断。不归属仓库，跑完即弃。
"""
import re
import pathlib

ROOT = pathlib.Path("src/main/java")
SKIP = {"src/main/java/com/wsteam/wandscape/foundation/networking/Net.java"}

# 最长优先，避免 sendToPlayer 抢先匹配 sendToPlayersTrackingChunk
METHODS = [
    ("sendToPlayersTrackingEntityAndSelf", "toTracking"),
    ("sendToPlayersTrackingEntity", "toTracking"),
    ("sendToPlayersTrackingChunk", "toTrackingChunk"),
    ("sendToAllPlayers", "toAll"),
    ("sendToServer", "toServer"),
    ("sendToPlayer", "toPlayer"),
]
ALT = "|".join(re.escape(k) for k, _ in METHODS)
LOOKUP = dict(METHODS)

CALL_RE = re.compile(
    r"(?:net\.neoforged\.neoforge\.network\.)?PacketDistributor\.(" + ALT + r")\b"
)

IMPORT_OLD = "import net.neoforged.neoforge.network.PacketDistributor;"
IMPORT_NEW = "import com.wsteam.wandscape.foundation.networking.Net;"


def fix_imports(lines):
    lines = [ln for ln in lines if ln.strip() != IMPORT_OLD]
    if any(ln.strip() == IMPORT_NEW for ln in lines):
        return lines
    idxs = [i for i, ln in enumerate(lines) if ln.startswith("import ")]
    if not idxs:
        return lines
    target = "com.wsteam.wandscape.foundation.networking.Net"
    pos = None
    for i in idxs:
        body = lines[i][len("import "):].rstrip(";").strip()
        if body > target:
            pos = i
            break
    if pos is None:
        pos = idxs[-1] + 1
    lines.insert(pos, IMPORT_NEW)
    return lines


def main():
    touched, calls = 0, 0
    for path in ROOT.rglob("*.java"):
        rel = path.as_posix()
        if rel in SKIP:
            continue
        src = path.read_text(encoding="utf-8")
        if "PacketDistributor" not in src:
            continue
        new, n = CALL_RE.subn(lambda m: "Net." + LOOKUP[m.group(1)], src)
        if n == 0:
            print(f"  [未匹配但含关键词] {rel}")
            continue
        new = "\n".join(fix_imports(new.split("\n")))
        path.write_text(new, encoding="utf-8")
        touched += 1
        calls += n
    print(f"改动文件 {touched} 个，替换调用 {calls} 处")


if __name__ == "__main__":
    main()
