package com.wsteam.wandscape.content.npc.internal;

import com.wsteam.wandscape.api.NpcAttributesApi;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;

import java.util.List;

/** {@link NpcAttributesApi} 装配实现：直接读写 {@link NpcAttributes} 规则表（单一事实源）。 */
public final class NpcAttributesApiImpl implements NpcAttributesApi {

    @Override
    public boolean isVisible(AttributeType type) {
        return type.isVisible();
    }

    @Override
    public List<AttributeType> visible() {
        return NpcAttributes.ORDER;
    }

    @Override
    public float lower(AttributeType type) { return NpcAttributes.lower(type); }

    @Override
    public float upper(AttributeType type) { return NpcAttributes.upper(type); }

    @Override
    public float perLevel(AttributeType type) { return NpcAttributes.perLevel(type); }

    @Override
    public float trainStep(AttributeType type) { return NpcAttributes.trainStep(type); }

    @Override
    public float defaultFor(AttributeType type) { return NpcAttributes.defaultFor(type); }

    @Override
    public float effective(AttributeType type, float base, int level, float equipBonus) {
        return NpcAttributes.computeEffective(type, base, level, equipBonus);
    }

    @Override
    public void overrideSpec(AttributeType type, float lower, float upper, float perLevel, float trainStep) {
        NpcAttributes.overrideSpec(type, lower, upper, perLevel, trainStep);
    }

    @Override
    public void resetSpec(AttributeType type) {
        NpcAttributes.resetSpec(type);
    }

    @Override
    public void setDefault(AttributeType type, float value) {
        NpcAttributes.overrideDefault(type, value);
    }

    @Override
    public void resetDefault(AttributeType type) {
        NpcAttributes.resetDefault(type);
    }

    @Override
    public void overrideCosts(double trainBase, double trainGrowth, long upgradeBase, int maxTrainSteps) {
        NpcAttributes.overrideCosts(trainBase, trainGrowth, upgradeBase, maxTrainSteps);
    }

    @Override
    public void resetCosts() {
        NpcAttributes.resetCosts();
    }
}
