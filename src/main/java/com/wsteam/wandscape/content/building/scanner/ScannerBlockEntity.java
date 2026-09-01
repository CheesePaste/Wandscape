package com.wsteam.wandscape.content.building.scanner;
import com.wsteam.wandscape.content.task.ecs.World;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;

/**
 * Block entity for the Survival Building Scanner.
 * Category is permanently locked to {@code custom}: tourists never interact with it,
 * and comfort/magic/wonder are always zero.
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

    /** 生存扫描器导出为纯建筑：不携带任何可复制物品的 NBT（防“藏物品→打印”刷物品）。 */
    @Override
    public boolean isSafeExport() { return true; }

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
