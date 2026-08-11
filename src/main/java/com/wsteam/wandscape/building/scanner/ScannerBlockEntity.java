package com.wsteam.wandscape.building.scanner;

import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.building.scanner.CreativeScannerBlockEntity.ShopGoodData;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for the Survival Building Scanner.
 * Category is permanently locked to {@code custom}: tourists never interact with it,
 * it carries no maintenance cost, and comfort/magic/wonder are always zero.
 * All other logic (boundary/door/id/name/NBT/door detection) is inherited from the
 * full CreativeScannerBlockEntity — getters are overridden so the invariant holds
 * even if stale or hostile NBT is loaded.
 */
public class ScannerBlockEntity extends CreativeScannerBlockEntity {

    public ScannerBlockEntity(BlockPos pos, BlockState state) {
        this(com.wsteam.wandscape.Wandscape.BUILDING_SCANNER_BE.get(), pos, state);
    }

    public ScannerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        setCategory("custom");
    }

    @Override
    public String getCategory() { return "custom"; }

    @Override
    public int getComfort() { return 0; }

    @Override
    public int getMagic() { return 0; }

    @Override
    public int getWonder() { return 0; }

    @Override
    public List<ShopGoodData> getShopGoods() { return List.of(); }

    @Override
    public Map<String, Integer> getServiceElementOutput() { return Map.of(); }
}
