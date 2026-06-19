package com.wsteam.wandscape.shared.api;

import java.util.List;

import com.wsteam.wandscape.shared.data.ElementType;

public interface ElementApi {
    ElementType fromId(String id);
    int getTier(ElementType type);
    List<ElementType> getByTier(int tier);
}
