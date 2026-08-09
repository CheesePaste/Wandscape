# 🏛️ Altar

Wizards can die in battle. The Altar is your regret medicine — for now it knows only one ritual spell: **Revive**. Wizards never use this spell on their own in combat; you must order it at the altar.

## Casting a Ritual Spell

1. Press **V** to open the panel, then **right-click the altar** to open the Altar panel.
2. The list shows the castable spells; each row marks **name / mana cost / cooldown / channel duration**.
3. **Single-click** a row to select it (just highlights, doesn't cast).
4. Click **Submit** in the bottom right — the colony dispatches a wizard with enough mana to walk to the altar and cast; the magic circle unfolds at the altar's center.
5. "Casting" is shown until the cast finishes; each altar keeps an **independent cooldown per spell**, so they never interfere.

## How Revive Works

- **Target**: the **most recently deceased wizard** of this colony, no matter where they died.
- **Effect**: the wizard is reborn at the **altar's center**, with name, appearance, attributes, and inventory restored as they were.
- **Cost**: the revived wizard is **weak** — 1 HP and 0 mana, recovered slowly through out-of-combat regeneration and mana regen.
- **Prerequisite**: a death record must exist (death records are kept permanently, and only removed after a successful revive).

## A Few Tips

- Casting consumes the **performing wizard's** mana; you can't submit while no colony wizard has enough mana.
- **Locked on publish**: submitting locks the spell until the cast ends, preventing accidental duplicate casts.
- Altar spells aren't part of a wizard's auto-cast strategy — for revives, come to the altar; don't expect them to get up on their own.

---

[Wizard NPC Guide](npc_guide.md)  
[Cast Strategy Guide](strategy_guide.md)  
[📖 Back to the Guide Index](index_guide.md)
