package com.wsteam.wandscape.warehouse;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.content.warehouse.ColonyItemBank;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.ItemKey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColonyItemBankChangedEventTest {

    record ItemChangeRecord(UUID colonyId, ItemKey key, long newCount, long delta) {}
    record ElementChangeRecord(UUID colonyId, ElementType type, long newAmount, long delta) {}

    private final List<ItemChangeRecord> itemEvents = new ArrayList<>();
    private final List<ElementChangeRecord> elementEvents = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ColonyItemBank.setItemChangeNotifier((colonyId, key, newCount, delta) ->
                itemEvents.add(new ItemChangeRecord(colonyId, key, newCount, delta)));
        ColonyItemBank.setElementChangeNotifier((colonyId, type, newAmount, delta) ->
                elementEvents.add(new ElementChangeRecord(colonyId, type, newAmount, delta)));
    }

    @AfterEach
    void tearDown() {
        ColonyItemBank.setItemChangeNotifier(null);
        ColonyItemBank.setElementChangeNotifier(null);
    }

    @Test
    void addItem_firesItemChangeEventWithPositiveDelta() {
        ColonyItemBank bank = new ColonyItemBank();
        UUID colonyId = UUID.randomUUID();
        ItemKey key = ItemKey.of("minecraft:oak_log", null);

        bank.add(colonyId, key, 32);

        assertEquals(1, itemEvents.size());
        assertEquals(colonyId, itemEvents.get(0).colonyId());
        assertEquals(key, itemEvents.get(0).key());
        assertEquals(32, itemEvents.get(0).newCount());
        assertEquals(32, itemEvents.get(0).delta());

        bank.add(colonyId, key, 10);
        assertEquals(2, itemEvents.size());
        assertEquals(42, itemEvents.get(1).newCount());
        assertEquals(10, itemEvents.get(1).delta());
    }

    @Test
    void consumeItem_firesItemChangeEventWithNegativeDelta() {
        ColonyItemBank bank = new ColonyItemBank();
        UUID colonyId = UUID.randomUUID();
        ItemKey key = ItemKey.of("minecraft:iron_ingot", null);

        bank.add(colonyId, key, 50);
        itemEvents.clear();

        assertTrue(bank.consume(colonyId, key, 20));
        assertEquals(1, itemEvents.size());
        assertEquals(30, itemEvents.get(0).newCount());
        assertEquals(-20, itemEvents.get(0).delta());

        // Consuming everything
        assertTrue(bank.consume(colonyId, key, 30));
        assertEquals(2, itemEvents.size());
        assertEquals(0, itemEvents.get(1).newCount());
        assertEquals(-30, itemEvents.get(1).delta());
    }

    @Test
    void consumeItem_insufficientDoesNotFireEvent() {
        ColonyItemBank bank = new ColonyItemBank();
        UUID colonyId = UUID.randomUUID();
        ItemKey key = ItemKey.of("minecraft:diamond", null);

        bank.add(colonyId, key, 5);
        itemEvents.clear();

        assertFalse(bank.consume(colonyId, key, 10));
        assertTrue(itemEvents.isEmpty());
    }

    @Test
    void addAndConsumeElement_firesElementChangeEventWithDeltas() {
        ColonyItemBank bank = new ColonyItemBank();
        UUID colonyId = UUID.randomUUID();

        bank.addElement(colonyId, ElementType.FIRE, 100);
        assertEquals(1, elementEvents.size());
        assertEquals(ElementType.FIRE, elementEvents.get(0).type());
        assertEquals(100, elementEvents.get(0).newAmount());
        assertEquals(100, elementEvents.get(0).delta());

        assertTrue(bank.consumeElement(colonyId, ElementType.FIRE, 40));
        assertEquals(2, elementEvents.size());
        assertEquals(ElementType.FIRE, elementEvents.get(1).type());
        assertEquals(60, elementEvents.get(1).newAmount());
        assertEquals(-40, elementEvents.get(1).delta());
    }
}
