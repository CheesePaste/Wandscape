package com.wsteam.wandscape.api;

import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;
import com.wsteam.wandscape.content.npc.data.RecruitmentCandidate;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * 生成殖民地法师时的<b>可选覆盖规格</b>：所有字段 @Nullable，null = 不覆盖该维度 / 走默认。
 *
 * <p>供 {@link NpcApi#spawnNpc} 与酒馆招募（{@link TavernApi}）使用——整合包/附属模组可借此
 * 生成指定属性、等级、皮肤、帽色、习得魔法、名字的法师（例如一个更强的自定义 NPC，或带自定义
 * 招募花费）。{@code attributes} 是<b>基础属性</b>（base，非 effective）；缺席的键走「按小镇等级掷点
 * 默认」兜底。
 *
 * <p>用法：
 * <pre>{@code
 * NpcSpawnSpec spec = NpcSpawnSpec.builder()
 *         .name("传奇法师")
 *         .level(20)
 *         .skinVariant(3)
 *         .attributes(Map.of(AttributeType.SPELL_POWER, 90f, AttributeType.MAX_HP, 200f))
 *         .spells(List.of("heal", "fireball"))
 *         .strategyPreset("AGGRESSIVE")
 *         .build();
 * npcApi.spawnNpc(colonyId, pos, spec);
 * }</pre>
 */
public final class NpcSpawnSpec {

    @Nullable private String name;
    @Nullable private Integer level;
    @Nullable private Integer skinVariant;
    @Nullable private Integer hatColor;
    @Nullable private String strategyPreset;
    @Nullable private List<String> spells;
    @Nullable private Map<AttributeType, Float> attributes;

    public NpcSpawnSpec() {}

    private NpcSpawnSpec(NpcSpawnSpec o) {
        this.name = o.name;
        this.level = o.level;
        this.skinVariant = o.skinVariant;
        this.hatColor = o.hatColor;
        this.strategyPreset = o.strategyPreset;
        this.spells = o.spells;
        this.attributes = o.attributes;
    }

    /** 返回一个把 {@code spells} 替换为给定值的副本（其余字段不变）。 */
    public NpcSpawnSpec withSpells(@Nullable List<String> v) {
        NpcSpawnSpec n = new NpcSpawnSpec(this);
        n.spells = v == null ? null : List.copyOf(v);
        return n;
    }

    /**
     * 由一份真实掷点档案（{@link RecruitmentCandidate}）生成规格：等级 + 全部可见属性基础值。
     * 整合包作者可先 {@code npcAttributesApi.roll(level)} 出真档案，转成规格后
     * 再 {@code .build()}/{@code .withXxx} 微调，或直接手填「特殊 NPC」数值。
     */
    public static NpcSpawnSpec fromCandidate(RecruitmentCandidate c) {
        return NpcSpawnSpec.builder()
                .level(c.level())
                .attributes(Map.of(
                        AttributeType.MAX_HP, c.maxHp(),
                        AttributeType.MOVE_SPEED, c.moveSpeed(),
                        AttributeType.SPELL_POWER, c.spellPower(),
                        AttributeType.WORK_SPEED, c.workSpeed(),
                        AttributeType.SPELL_SPEED, c.spellSpeed(),
                        AttributeType.ARMOR_VALUE, c.armorValue(),
                        AttributeType.MAX_MANA, c.maxMana()))
                .build();
    }

    public static Builder builder() { return new Builder(); }

    @Nullable public String name() { return name; }
    @Nullable public Integer level() { return level; }
    @Nullable public Integer skinVariant() { return skinVariant; }
    @Nullable public Integer hatColor() { return hatColor; }
    @Nullable public String strategyPreset() { return strategyPreset; }
    @Nullable public List<String> spells() { return spells; }
    @Nullable public Map<AttributeType, Float> attributes() { return attributes; }

    /** 空规格（全部走默认）或默认规格来源。 */
    public static final class Builder {
        private final NpcSpawnSpec spec = new NpcSpawnSpec();

        public Builder name(String v) { spec.name = v; return this; }
        public Builder level(int v) { spec.level = v; return this; }
        public Builder skinVariant(int v) { spec.skinVariant = v; return this; }
        public Builder hatColor(int v) { spec.hatColor = v; return this; }
        public Builder strategyPreset(String v) { spec.strategyPreset = v; return this; }
        public Builder spells(List<String> v) { spec.spells = v == null ? null : List.copyOf(v); return this; }
        public Builder attributes(Map<AttributeType, Float> v) {
            spec.attributes = v == null ? null : Map.copyOf(v);
            return this;
        }
        public NpcSpawnSpec build() { return spec; }
    }
}
