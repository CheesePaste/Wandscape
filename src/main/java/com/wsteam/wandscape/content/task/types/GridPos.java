package com.wsteam.wandscape.content.task.types;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.task.ecs.World;

/**
 * Block position in the world: (x, y, z).
 * Immutable value type with no Minecraft dependency.
 */
public record GridPos(int x, int y, int z) {

    public static final GridPos ORIGIN = new GridPos(0, 0, 0);

    /** Manhattan distance to another position. */
    public int manhattanTo(GridPos other) {
        return Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z);
    }

    /** Euclidean distance squared (cheaper than sqrt). */
    public double distSq(GridPos other) {
        int dx = x - other.x;
        int dy = y - other.y;
        int dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public GridPos add(int dx, int dy, int dz) {
        return new GridPos(x + dx, y + dy, z + dz);
    }

    public GridPos add(GridPos other) {
        return new GridPos(x + other.x, y + other.y, z + other.z);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
