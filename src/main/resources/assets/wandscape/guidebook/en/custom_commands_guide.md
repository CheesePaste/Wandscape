# Commands

> Every command lives under `/wandscape`. Reading state needs no permission; anything that touches the colony needs an admin. The developer/debug set is tucked away under `/wandscape test` and is not covered here.

Most of what the panel does, a command can do too. What commands actually add is the other two things: **laying the numbers out** and **rescuing a colony that has locked up**.

## Permissions, and which colony a command picks

Read-only queries (`status`, `list`, `view`) are open to anyone. Anything that changes a colony's assets — adding or removing elements and items, setting the level, granting experience, renaming, recruiting a mage, cancelling or demolishing buildings and road edges — requires admin rights. The developer/debug commands hang off a separate `/wandscape test` tree, admin-only from top to bottom, and they do not show up in a normal player's autocomplete at all.

Every command first has to decide which colony it is working on, and the order is fixed: **it looks for a colony you founded**, and uses that one wherever you happen to be standing. Only if you founded none does an admin fall back to the colony at their feet, while a normal player is simply told no colony was found. That is why reading someone else's world needs admin: you can see the buildings at your coordinates, and the command still says there is no colony there.

## Colony

| Command | What it does |
|---|---|
| `/wandscape colony status` | Level, experience, the three values, tourist and mage counts, work under construction, all seven elements |
| `/wandscape colony list` | Name, short id and level of every colony on the server |
| `/wandscape colony create <name>` | Create a colony (op-2) |
| `/wandscape colony destroy` | Destroy a colony (op-2) |
| `/wandscape colony level <n>` | Set the level outright, within the level cap (op-2) |
| `/wandscape colony exp <n>` | Grant experience, which may level the colony up (op-2) |
| `/wandscape colony name <name>` | Rename the colony (op-2) |

`create` places the colony 5 blocks in front of you, or under your feet if you are sneaking. It also spawns 3 starter mages and sets off a ring of fireworks. **One colony per player**: if you already own one, the command fails outright rather than making a second.

`destroy` destroys **the colony you founded**. Only when you never founded one does it fall back to the colony at your feet.

## Elements and Warehouse

| Command | What it does |
|---|---|
| `/wandscape element view` | The seven element balances |
| `/wandscape element add <element> <amount>` | Add elements (op-2) |
| `/wandscape element remove <element> <amount>` | Remove elements (op-2) |
| `/wandscape element clear` | Clear all seven elements (op-2) |
| `/wandscape warehouse view` | Item kinds, piece count and capacity in use |
| `/wandscape warehouse add <item> <amount>` | Add items (op-2) |
| `/wandscape warehouse remove <item> <amount>` | Remove items (op-2) |
| `/wandscape warehouse clear` | Clear all items (op-2) |

Elements are written as their English ids: `earth`, `wood`, `water`, `fire`, `metal`, `wind`, `dark`. The warehouse holds **items** only; elements go through `element`. Item ids must be written out in full, such as `minecraft:iron_ingot`.

A `remove` that asks for more than the colony holds fails and **takes nothing at all** — there is no half-finished deduction down to zero.

## Buildings and Roads

| Command | What it does |
|---|---|
| `/wandscape building list [category]` | Colony buildings: name, category, state, size, anchor and short id |
| `/wandscape building cancel <buildingId>` | Cancel a building under construction and refund its materials (op-2) |
| `/wandscape building demolish <buildingId>` | Demolish a building; the drops go to the colony warehouse (op-2) |
| `/wandscape road status` | Road edge count, how many are in each state, and total paved length |
| `/wandscape road cancel <edgeId>` | Withdraw an edge under construction and refund its materials (op-2) |

You do not have to type an id in full: the 8-character short id from `list` works as-is, and so does any run of characters from the middle of it.

`demolish` refuses in one case: **the town hall, the warehouse and the workstation cannot be demolished while only one of their kind is left**, so a colony cannot lose the buildings it needs to keep running.

## Mages, Tourists and the Tavern

| Command | What it does |
|---|---|
| `/wandscape npc list [idle]` | Mage roster: level, idle or on a task, health, mana and equipped spells |
| `/wandscape tourist list` | Tourist roster: state, level and the three need bars |
| `/wandscape tourist clear` | Clear the colony's tourists |
| `/wandscape tavern list` | The mage resumes currently waiting in the tavern |
| `/wandscape tavern recruit` | Recruit one mage (op-2) |
| `/wandscape guard status` | Guard zone count, the nearest threat, and active guard tasks |

`tourist clear` needs no permission, and it sends tourists home the **normal way** rather than deleting them — use it when the town is overcrowded or a tourist is stuck.

`tavern recruit` costs every element on every use, at a price set in the server config. Not enough elements means the command fails without charging anything, and the mage it brings in is always level 1 regardless of the town's level — for a higher-level mage, hire a résumé instead.

Both tavern commands pick their colony by **where you are standing**, not by who owns it: outside every colony they fall back to the first colony on the server rather than reporting an error, so walk into the town you mean before recruiting for it.

## When Things Lock Up

| Command | What it does |
|---|---|
| `/wandscape recover status` | Live statistics for the task pool and building queues |
| `/wandscape recover clear` | Empty the task pool and building queues, and release the mages |

`clear` also needs no permission, and its price is that **every task in progress is discarded on the spot**: whatever a mage was holding is dropped and the building queues are emptied with it. It is the last resort when mages stand still and do nothing at all, not routine maintenance.

## The Guide Book

| Command | What it does |
|---|---|
| `/wandscape guide [page]` | Open the in-game guide book |

Players only. With no page name it opens the manual's home page; give a page name to jump straight there — the entry id (`/wandscape guide townhall_guide`), the short form without `_guide` (`/wandscape guide warehouse`), or a category (`/wandscape guide category:buildings`). The same page names work either way: with Patchouli installed it opens the Patchouli book, without it opens the mod's own reader.

## Curios Only

| Command | What it does |
|---|---|
| `/wandscape curios list [target]` | List a mage's curio slots |
| `/wandscape curios set <slot> <amount> [target]` | Set a slot count outright (op-2) |
| `/wandscape curios add <slot> <amount> [target]` | Grow a slot count (op-2) |
| `/wandscape curios remove <slot> <amount> [target]` | Shrink a slot count (op-2) |

These exist only with Curios installed. **Leaving the target out hits every mage on the server**, not just the ones near you; name the target to touch a single mage.
