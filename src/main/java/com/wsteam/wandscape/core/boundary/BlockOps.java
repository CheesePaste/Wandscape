package com.wsteam.wandscape.core.boundary;

import com.wsteam.wandscape.core.types.BlockType;
import com.wsteam.wandscape.core.types.GridPos;

/**
 * Core-layer boundary for block-level world operations.
 * Implemented by the Minecraft adapter layer.
 */
public interface BlockOps {

    /** Set a block at the given position. */
    void setBlock(GridPos pos, BlockType type);

    /** Get the current block type at the given position. */
    BlockType getBlock(GridPos pos);

    /** Check if the position is air (or replaceable). */
    boolean isAir(GridPos pos);

    /** Toggle a block state (door, lever, etc.). */
    void toggle(GridPos pos);

    /** Activate a block (button, pressure plate, etc.). */
    void activate(GridPos pos);

    /** Open the GUI for a block (chest, crafting table, etc.). */
    void openGui(GridPos pos);
}
