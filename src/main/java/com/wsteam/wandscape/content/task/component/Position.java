package com.wsteam.wandscape.content.task.component;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.content.task.types.GridPos;
/** World position of an entity. */
public record Position(GridPos pos) {

    public static Position of(int x, int y, int z) {
        return new Position(new GridPos(x, y, z));
    }
}
