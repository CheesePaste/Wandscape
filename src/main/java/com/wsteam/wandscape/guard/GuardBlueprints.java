package com.wsteam.wandscape.guard;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.op.api.AtomicOp;
import com.wsteam.wandscape.task.engine.dsl.BlueprintRegistry;
import com.wsteam.wandscape.task.engine.dsl.BlueprintSteps;
import com.wsteam.wandscape.task.runtime.TaskSequence;

/**
 * 守卫相关代码蓝图注册。{@code guard:attack} 的目标是区域半径等纯参数，
 * 不需要实体 id/方块坐标，用代码 lambda 注册（同 gather:*）。
 */
public final class GuardBlueprints {
    private GuardBlueprints() {}

    public static void registerDefault(BlueprintRegistry registry) {
        registry.register("guard:attack", GuardBlueprints::attackSteps);
    }

    private static TaskSequence attackSteps(Map<String, JsonElement> params) {
        int attackRange = intParam(params, "attackRange", Config.GUARD_RANGE.get());
        int releaseRange = intParam(params, "releaseRange", Config.GUARD_RELEASE_RANGE.get());
        return new TaskSequence(
                List.of(new AtomicOp.AttackMonsterOp(attackRange, releaseRange)),
                "Guard attack");
    }

    private static int intParam(Map<String, JsonElement> params, String key, int def) {
        JsonElement el = params.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsInt() : def;
    }
}
