package com.wsteam.wandscape.core.component;

import com.wsteam.wandscape.core.types.GridPos;
/** World position of an entity. */
public record Position(GridPos pos) {

    public static Position of(int x, int y, int z) {
        return new Position(new GridPos(x, y, z));
    }
}
