package com.wsteam.wandscape.road.core;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public record TransportRoute(List<SplineLeg> legs) {
    public boolean isEmpty() {
        return legs == null || legs.isEmpty();
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        if (isEmpty()) return tag;

        ListTag legsTag = new ListTag();
        for (SplineLeg leg : legs) {
            CompoundTag legTag = new CompoundTag();
            legTag.putDouble("uStart", leg.uStart());
            legTag.putDouble("uEnd", leg.uEnd());
            legTag.putBoolean("offRoad", leg.offRoad());

            // Serialize SplineModel
            SplineModel spline = leg.spline();
            if (spline != null) {
                ListTag splineTag = new ListTag();
                for (SplinePoint sp : spline.getPoints()) {
                    CompoundTag spt = new CompoundTag();
                    
                    ListTag aTag = new ListTag();
                    aTag.add(net.minecraft.nbt.DoubleTag.valueOf(sp.getAnchor().x()));
                    aTag.add(net.minecraft.nbt.DoubleTag.valueOf(sp.getAnchor().y()));
                    aTag.add(net.minecraft.nbt.DoubleTag.valueOf(sp.getAnchor().z()));
                    spt.put("a", aTag);
                    
                    ListTag pTag = new ListTag();
                    pTag.add(net.minecraft.nbt.DoubleTag.valueOf(sp.getControlPrev().x()));
                    pTag.add(net.minecraft.nbt.DoubleTag.valueOf(sp.getControlPrev().y()));
                    pTag.add(net.minecraft.nbt.DoubleTag.valueOf(sp.getControlPrev().z()));
                    spt.put("p", pTag);
                    
                    ListTag nTag = new ListTag();
                    nTag.add(net.minecraft.nbt.DoubleTag.valueOf(sp.getControlNext().x()));
                    nTag.add(net.minecraft.nbt.DoubleTag.valueOf(sp.getControlNext().y()));
                    nTag.add(net.minecraft.nbt.DoubleTag.valueOf(sp.getControlNext().z()));
                    spt.put("n", nTag);
                    
                    spt.putBoolean("l", sp.isLocked());
                    splineTag.add(spt);
                }
                legTag.put("spline", splineTag);
            }
            legsTag.add(legTag);
        }
        tag.put("legs", legsTag);
        return tag;
    }

    public static TransportRoute fromNbt(CompoundTag tag) {
        if (!tag.contains("legs", Tag.TAG_LIST)) {
            return new TransportRoute(List.of());
        }

        List<SplineLeg> legs = new ArrayList<>();
        ListTag legsTag = tag.getList("legs", Tag.TAG_COMPOUND);
        for (int i = 0; i < legsTag.size(); i++) {
            CompoundTag legTag = legsTag.getCompound(i);
            double uStart = legTag.getDouble("uStart");
            double uEnd = legTag.getDouble("uEnd");
            boolean offRoad = legTag.getBoolean("offRoad");

            SplineModel model = new SplineModel();
            if (legTag.contains("spline", Tag.TAG_LIST)) {
                ListTag splineTag = legTag.getList("spline", Tag.TAG_COMPOUND);
                for (int j = 0; j < splineTag.size(); j++) {
                    CompoundTag spt = splineTag.getCompound(j);
                    ListTag aTag = spt.getList("a", Tag.TAG_DOUBLE);
                    ListTag pTag = spt.getList("p", Tag.TAG_DOUBLE);
                    ListTag nTag = spt.getList("n", Tag.TAG_DOUBLE);
                    boolean locked = spt.getBoolean("l");
                    
                    SplineVec3 a = new SplineVec3(aTag.getDouble(0), aTag.getDouble(1), aTag.getDouble(2));
                    SplineVec3 p = new SplineVec3(pTag.getDouble(0), pTag.getDouble(1), pTag.getDouble(2));
                    SplineVec3 n = new SplineVec3(nTag.getDouble(0), nTag.getDouble(1), nTag.getDouble(2));
                    
                    model.getPoints().add(new SplinePoint(a, p, n, locked));
                }
            }
            legs.add(new SplineLeg(model, uStart, uEnd, offRoad));
        }
        return new TransportRoute(legs);
    }
}
