# Town Hall

The core facility of the colony. It manages the town name, colony level, experience, and reputation, and is the key to attracting wizard NPCs to join. The Town Hall must be built and the colony created before other buildings can operate.

## Building & Enabling

Press **V** to open the panel → the **Government** category of the build panel → place it. **Free on first build**.

After placement, **right-click** the Town Hall; the first interaction asks you to **name the town and create a colony**.

## Panel

| Item | Description |
|---|---|
| Town name | Change any time |
| Level | The colony level; higher levels raise the population cap and unlock more buildings |
| Experience bar | Progress of running the town; levels up automatically when full |
| Reputation | Accumulated from tourists leaving satisfied; the higher the reputation, the rarer and richer the tourists entering the gate each day |
| Blueprint selection | Opens the building selection panel to pick buildings and publish construction plans |

## Acting as a Warehouse When None Exists

When the colony has no warehouse building, the Town Hall panel gains a **Warehouse Access** button — clicking it opens the warehouse deposit/withdraw screen so you can supply materials for later construction. Once a warehouse is built, the button disappears and the warehouse building takes over.

## Spawn Tourists Toggle

The bottom of the Town Hall panel has a **Spawn Tourists** toggle, **on by default** — **a gold border around the button means it is currently on**. Turning it off stops this colony from spawning new tourists (tourists already present are unaffected and still tour, shop, and leave normally); click again to turn it back on.

> This toggle only controls this colony. A separate global switch, `tourist.spawnEnabled` (default `true`), lives in the config file — turning it off stops **every** colony from spawning tourists, regardless of each town hall's own toggle.

## Fallback Revive After a Total Wipe

Fallen wizards can only be revived at the [Altar](altar_guide.md), and the altar needs a **living wizard** to walk over and cast. So a **total wipe stalls the altar too** — when that happens, the **Revive Wizard** button at the bottom of this panel lights up with a gold border. One click brings this colony's **most recently fallen wizard** back at the **town hall door** (also weakened, at 1 HP and 0 mana).

| Item | Description |
|---|---|
| Requires | **Not a single wizard left** in this colony, and a death record must exist |
| How many | One click revives **one** wizard — the button greys out immediately afterwards |
| Cooldown | **5 minutes**, tracked per colony; the button shows a countdown while it runs |
| Cost | Free — consumes no materials |

The wizard you get back is your **only bootstrap point**: the remaining dead still have to be fetched one by one at the altar by that wizard. So make sure the town has a working altar.

## Leveling Up

When tourists with **all three need bars full** leave satisfied at night, the town gains experience. The higher a tourist's level relative to the town, the more experience they give. When experience is full, you level up automatically, unlocking advanced building blueprints and raising the population cap.

> Advanced buildings require a sufficient colony level; when your level is insufficient, the corresponding blueprints show as locked in the build bar.

---

[Warehouse & Materials](warehouse_guide.md)  
[Getting Started: From Empty Land to a Tourist Town](getting_started_guide.md)  
[Back to the Guide Index](index_guide.md)
