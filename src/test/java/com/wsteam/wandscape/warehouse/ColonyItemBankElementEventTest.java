package com.wsteam.wandscape.warehouse;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.wsteam.wandscape.shared.data.ElementType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: warehouse element balance changes must be signalled so the
 * V-panel top-bar numbers resync in real time. add/consume that actually change
 * the balance notify; zero-amount and insufficient consumes do not.
 */
class ColonyItemBankElementEventTest {

    private Consumer<UUID> originalNotifier;
    private final List<UUID> notified = new ArrayList<>();

    @BeforeEach
    void setUp() {
        originalNotifier = ColonyItemBank.setElementBalanceNotifier(notified::add);
    }

    @AfterEach
    void tearDown() {
        ColonyItemBank.setElementBalanceNotifier(originalNotifier);
    }

    @Test
    void addElementNotifies() {
        ColonyItemBank bank = new ColonyItemBank();
        UUID colonyId = UUID.randomUUID();
        bank.addElement(colonyId, ElementType.EARTH, 10);
        assertEquals(List.of(colonyId), notified);
    }

    @Test
    void consumeElementNotifies() {
        ColonyItemBank bank = new ColonyItemBank();
        UUID colonyId = UUID.randomUUID();
        bank.addElement(colonyId, ElementType.WOOD, 10);
        notified.clear();

        assertTrue(bank.consumeElement(colonyId, ElementType.WOOD, 4));
        assertEquals(List.of(colonyId), notified);
    }

    @Test
    void insufficientConsumeDoesNotNotify() {
        ColonyItemBank bank = new ColonyItemBank();
        UUID colonyId = UUID.randomUUID();
        bank.addElement(colonyId, ElementType.FIRE, 2);
        notified.clear();

        assertFalse(bank.consumeElement(colonyId, ElementType.FIRE, 5));
        assertTrue(notified.isEmpty());
    }

    @Test
    void zeroAmountAddDoesNotNotify() {
        ColonyItemBank bank = new ColonyItemBank();
        bank.addElement(UUID.randomUUID(), ElementType.DARK, 0);
        assertTrue(notified.isEmpty());
    }

    // ---- Element ledger boundary math (independent of notification) ----

    @Test
    void consumeZeroAmount_returnsTrueNoChange() {
        ColonyItemBank bank = new ColonyItemBank();
        UUID colonyId = UUID.randomUUID();
        bank.addElement(colonyId, ElementType.EARTH, 5);
        assertTrue(bank.consumeElement(colonyId, ElementType.EARTH, 0));
        assertEquals(5, bank.countElement(colonyId, ElementType.EARTH));
    }

    @Test
    void consumeExactAmount_removesLedgerEntry() {
        ColonyItemBank bank = new ColonyItemBank();
        UUID colonyId = UUID.randomUUID();
        bank.addElement(colonyId, ElementType.WOOD, 4);
        assertTrue(bank.consumeElement(colonyId, ElementType.WOOD, 4));
        assertEquals(0, bank.countElement(colonyId, ElementType.WOOD),
                "exact consume must remove the ledger key entirely");
    }

    @Test
    void consumePartialAmount_decrementsLedger() {
        ColonyItemBank bank = new ColonyItemBank();
        UUID colonyId = UUID.randomUUID();
        bank.addElement(colonyId, ElementType.FIRE, 10);
        assertTrue(bank.consumeElement(colonyId, ElementType.FIRE, 3));
        assertEquals(7, bank.countElement(colonyId, ElementType.FIRE));
    }

    @Test
    void consumeInsufficient_returnsFalseNoChange() {
        ColonyItemBank bank = new ColonyItemBank();
        UUID colonyId = UUID.randomUUID();
        bank.addElement(colonyId, ElementType.FIRE, 2);
        assertFalse(bank.consumeElement(colonyId, ElementType.FIRE, 5));
        assertEquals(2, bank.countElement(colonyId, ElementType.FIRE));
    }

    @Test
    void consumeFromUnseededColony_returnsFalse() {
        ColonyItemBank bank = new ColonyItemBank();
        assertFalse(bank.consumeElement(UUID.randomUUID(), ElementType.WOOD, 1));
    }

    @Test
    void addSameElementTwice_sumsBeforeConsume() {
        ColonyItemBank bank = new ColonyItemBank();
        UUID colonyId = UUID.randomUUID();
        bank.addElement(colonyId, ElementType.WOOD, 3);
        bank.addElement(colonyId, ElementType.WOOD, 5);
        assertEquals(8, bank.countElement(colonyId, ElementType.WOOD));
        assertTrue(bank.consumeElement(colonyId, ElementType.WOOD, 6));
        assertEquals(2, bank.countElement(colonyId, ElementType.WOOD));
    }

    @Test
    void elementLedger_isolatedPerColony() {
        ColonyItemBank bank = new ColonyItemBank();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        bank.addElement(a, ElementType.EARTH, 10);
        assertEquals(10, bank.countElement(a, ElementType.EARTH));
        assertEquals(0, bank.countElement(b, ElementType.EARTH));
    }
}