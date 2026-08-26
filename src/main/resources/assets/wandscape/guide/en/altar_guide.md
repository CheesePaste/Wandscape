# Altar

The facility for casting **ritual spells**. The currently available ritual spell is **Revive**. Ritual spells are not auto-cast by wizards in combat and must be ordered at the altar.

## Casting a Ritual Spell

1. Press **V** to open the panel, then **aim your crosshair at the altar and right-click** to open the Altar panel.
2. The list shows castable spells; each row marks **name / mana cost / cooldown / channel duration**.
3. **Single-click** a row to select it (highlights only, does not cast).
4. Click **Submit** in the bottom right — the colony dispatches a wizard with enough mana to walk to the altar and cast; the magic circle unfolds at the altar's center.
5. "Casting" is shown until the cast finishes; each altar keeps an **independent cooldown per spell**, so they never interfere.

## Revive

| Item | Description |
|---|---|
| Target | The **most recently deceased wizard** of this colony, regardless of where they died |
| Effect | The wizard is reborn at the **altar's center**, with name, appearance, attributes, and inventory restored |
| Cost | The revived wizard is **weak** — 1 HP and 0 mana, recovered through out-of-combat regeneration and mana regen |
| Prerequisite | A death record must exist (death records are kept permanently, and only removed after a successful revive) |

If a wizard dies near a building (within 20 blocks), that counts as defending the colony — no altar needed; they are revived automatically at the town hall door (also weakened).

## Tips

- Casting consumes the **performing wizard's** mana; you can't submit while no colony wizard has enough mana.
- **Locked on publish**: submitting locks the spell until the cast ends, preventing duplicate casts.
- Altar spells are not part of a wizard's auto-cast strategy and must be ordered at the altar manually.

---

[Wizard NPC Guide](npc_guide.md)  
[Cast Strategy Guide](strategy_guide.md)  
[Back to the Guide Index](index_guide.md)
