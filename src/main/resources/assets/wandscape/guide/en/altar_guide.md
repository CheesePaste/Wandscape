# 🏛️ Altar Guide

The Altar is the town's core ritual building: you issue a command here, and the colony dispatches a mage who walks to the altar center and casts the **altar-exclusive spell** (e.g. Revive) on your behalf.

---

## 🎯 How to use

1. Approach the altar and **right-click** to open the Altar panel.
2. The list shows all altar-castable spells (each row: name / mana cost / cooldown / duration).
3. **Left-click** a row to select it (highlighted).
4. Click **Submit** at the bottom right — the colony assigns a mage with enough mana to walk over and cast it.
5. Until the cast finishes the spell shows as "Casting", and that altar enters its own cooldown for the spell.

> 💡 Altar-exclusive spells (like Revive) can only be cast at an altar; mages never auto-cast them in combat.

---

## 📖 UI Controls Reference

| Control / Label | Type | Details |
| :--- | :--- | :--- |
| **Spell list** | List | All altar-castable spells. Each row shows name, mana cost, cooldown, and channel duration. |
| **Row status** | Text | `Ready` = available; `Cooldown Ns` = altar cooldown still running; `Casting` = a mage is casting it; `Queued` = already submitted this session. |
| **Submit** | Button | Publishes the altar-cast task for the selected spell. Enabled only when a spell is selected and not locked / cooling / queued. |

---

## ⚙️ Mechanics

- **Mana cost**: casting consumes the mage's mana; you cannot submit while no colony mage has enough mana (≥ cost).
- **Cast flow**: the mage walks to the altar center and channels for `duration` ticks.
- **Per-altar cooldown**: cooldowns are stored per altar (building); altars never share them. Counting starts when the cast finishes.
- **Locked on submit**: submitting locks the spell until the cast ends, preventing duplicate casts.
- **Revive**: requires a revivable death record in the colony (death records persist permanently, removed only after a successful revive).

---

👉 [Go to Mage NPC guide](guide:npc_guide)  
👉 [Back to main test page](guide:test_guide)
