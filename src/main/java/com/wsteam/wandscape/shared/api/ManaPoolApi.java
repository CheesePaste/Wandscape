package com.wsteam.wandscape.shared.api;

import java.util.UUID;
public interface ManaPoolApi {
    long getMana(UUID colonyId);
    long getMaxMana(UUID colonyId);
    boolean consumeMana(UUID colonyId, long amount);
    boolean addMana(UUID colonyId, long amount);
}
