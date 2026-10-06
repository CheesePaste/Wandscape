package com.wsteam.wandscape.content.magic.worldresponse;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 《世界应答》的可选回应表（毕业魔法的四个分支）。
 *
 * <p><b>刻意不做成 `magic_spells/*.json`</b>：回应是同一法术的四个执行分支，不是独立法术——
 * 一旦进法术表，它们就得连带处理「能不能被卷轴绑定 / 被 NPC 装备 / 进 JEI 图鉴 / 进装备桶」
 * 四处清单（现有 {@code altar_only}、{@code MagicDef.SPECIAL_SPELLS} 这些过滤正是被这么逼出来的）。
 * 名字与说明走 lang 键（{@code worldresponse.wandscape.<id>}），视觉排布用 {@link #displayAngleDeg()}
 * 表达「向前在上」这类语义。将来要扩成数据驱动（企划案的 渡海/遁地/跃迁…），把这个枚举提升成
 * JSON 是一小步，不用先付代价。
 */
public enum WorldResponse {

    /** 上：沿视线推进。 */
    FORWARD("forward", -90f),
    /** 左：向上抬升。 */
    LIFT("lift", 180f),
    /** 右：移山填海（持续型：四周的阻挡临时让开，走过之后原样放回）。 */
    TERRAFORM("terraform", 0f),
    /** 下：处置眼前的阻碍者。 */
    JUDGE("judge", 90f);

    private final String id;
    private final float displayAngleDeg;

    WorldResponse(String id, float displayAngleDeg) {
        this.id = id;
        this.displayAngleDeg = displayAngleDeg;
    }

    public String id() {
        return id;
    }

    /** 轮盘上的显示角度（0 = 正右，-90 = 正上）。 */
    public float displayAngleDeg() {
        return displayAngleDeg;
    }

    public String labelKey() {
        return "worldresponse.wandscape." + id;
    }

    public String descKey() {
        return "worldresponse.wandscape." + id + ".desc";
    }

    /** 全部回应的 id（服务端下发给客户端渲染轮盘用）。 */
    public static List<String> ids() {
        return List.of(values()).stream().map(WorldResponse::id).toList();
    }

    @Nullable
    public static WorldResponse byId(String id) {
        if (id == null) return null;
        for (WorldResponse r : values()) {
            if (r.id.equals(id)) return r;
        }
        return null;
    }
}
