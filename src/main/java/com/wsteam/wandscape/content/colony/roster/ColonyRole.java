package com.wsteam.wandscape.content.colony.roster;

import javax.annotation.Nullable;

/**
 * 小镇花名册档位：一个 (玩家, 小镇) 关系格上的角色，四档，OWNER 最高。
 *
 * <p>语义（2026-10 裁定，别自行扩展）：
 * <ul>
 *   <li>{@link #OWNER} 全部权限，含改小镇设置与调整他人档位。**唯一且可转让**，转让后前任降 MANAGER。
 *       同时是友军白名单的归属身份锚点——这正是它不可多个的原因（见 docs/multiplayer-survey.md §10.3.1）。</li>
 *   <li>{@link #MANAGER} 全部操作性权限（建造/拆除、法师招募解雇调策略装备、跟随、任务调度……），
 *       但**不能**调档位、不能改设置。能操作镇内法师，却**不是法师的 Owner**（法师归属仍是
 *       {@code WandscapeNpc.colonyId}，不随管理人变）——「能管」与「归属」是两个轴。</li>
 *   <li>{@link #MEMBER} 仓库存取 + 工坊下单 + 友军白名单。不碰建筑、不碰法师管理。</li>
 *   <li>{@link #ALLY} **仅**友军白名单（不被该镇法师攻击），零操作权限。</li>
 * </ul>
 *
 * <p>不在花名册 = {@code null}（非成员），无任何权限——包括商店消费与旅店入住。
 *
 * <p>零 MC 依赖，纯判定，可单测。
 */
public enum ColonyRole {

    OWNER(4),
    MANAGER(3),
    MEMBER(2),
    ALLY(1);

    private final int rank;

    ColonyRole(int rank) {
        this.rank = rank;
    }

    /** 档位高低（越大越高）。用于比较与持久化无关的排序。 */
    public int rank() {
        return rank;
    }

    /** 本档位是否不低于 {@code min}。 */
    public boolean atLeast(ColonyRole min) {
        return this.rank >= min.rank;
    }

    /** 操作性权限：建造/拆除建筑、管理法师、让法师跟随、任务调度等（MANAGER 及以上）。 */
    public boolean canOperate() {
        return atLeast(MANAGER);
    }

    /** 仓库存取 + 工坊下单（MEMBER 及以上）。 */
    public boolean canWarehouseAndOrder() {
        return atLeast(MEMBER);
    }

    /** 治理权限：改小镇设置（改名/起名风格/游客开关）、调整他人档位、转让（仅 OWNER）。 */
    public boolean canGovern() {
        return this == OWNER;
    }

    /** 存档/网络用的名称解析；未知或 null 返回 null（调用方按「非成员」处理）。 */
    @Nullable
    public static ColonyRole byName(@Nullable String name) {
        if (name == null) return null;
        for (ColonyRole role : values()) {
            if (role.name().equalsIgnoreCase(name)) return role;
        }
        return null;
    }
}
