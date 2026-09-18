# Importing Spells

> A datapack can only retune a spell that already exists; **adding a new one takes Java**. Read that first, or your new spell will load cleanly, show up in the creative tab and the strategy slots, and then do nothing at all.

Every file under `magic_spells` describes one spell a mage can cast: how much mana it costs, how long it cools down, how it picks a target, and which magic circle is drawn underfoot. Files go in `data/<namespace>/magic_spells/<id>.json` and `/reload` picks them up.

## Retune, Not Add

At cast time the spell id lands in a **hardcoded** dispatch branch. The eight spells the mod ships — Beam, Heal, Meteor, Petrification, Enfeeble Field, Fortification, Conversion, Desperation — each have their own executor, and any id outside that list falls to the default branch: one line in the log saying the executor is unknown, and nothing happens.

## What a Datapack Can Change

So what a datapack can change is numbers and looks, not behaviour. For a new spell to actually do something, someone has to add a branch to `MagicSpellExecutors`, and that is addon code. Teleport and Revive are not implemented through this dispatch at all — a datapack cannot reach them either.

## The File Name Is the Id

**The registry key is the file name**; the `id` field inside is read only when dispatching. The two must match. When they do not, the scroll resolves by file name and the cast dispatches by the `id` field, and the result is a scroll you can hold and use that does nothing.

## Fields

| Key | Meaning |
|---|---|
| id | the spell id, defaulting to the file name |
| category | `normal`, `special` or `altar`, default `normal` |
| default_group | `single_target`, `aoe`, `defense` or `support`, default `support` |
| mana_cost | mana spent per cast |
| base_cooldown | cooldown in ticks |
| target_mode | `hostile_nearest`, `hostile_lowest_hp`, `ally_lowest_hp`, `self`, `none` or `dead_ally`, default `none` |

## category and altar_only

Within `category`, only `altar` does anything: it keeps the spell out of the creative tab, JEI, the strategy slots and scroll use.

Note that it and `altar_only` are **two independent checks** — writing one without the other gives a half-broken spell that some entry points block and others let through. An altar spell needs both.

## Effect and Altar Fields

| Key | Meaning |
|---|---|
| effect | only `circle_id`, `color` and `damage` are read |
| altar_only | whether it is cast only as an altar rite |
| altar_cooldown / altar_duration | the altar rite's cooldown and duration |
| cast_time | one line of text in the scroll tooltip |
| range | parsed, and read by nothing |
| description | the JEI page body, used when the language key is missing |

## Keys Nobody Reads

`effect` reads three keys and nothing else; anything else you put there (a heal amount, a radius, a particle, a duration) is never looked at. Heal amount, heal radius and meteor count are code constants, and `damage` is currently read by Meteor alone. `cast_time` and `range` take no part in casting either.

## Preconditions

`conditions` decides when a mage is willing to cast. `self_hp_max` means cast only **below** that health fraction, not above; `ally_hp_max` works the same way and never holds when there is no ally in range; `no_effect` requires the caster *not* to carry a status effect, and it must be written as a full `namespace:path` id — a bare name never matches.

## Magic Circles

`effect.circle_id` points at `data/<namespace>/magic_circles/<id>.json`. **A missing circle does not stop the cast**: the server still applies the damage or the heal, it is only the client that has nothing to draw, so the spell looks like it fizzled when it actually landed. Beam is the one exception and refuses to cast without its circle.

## The Circle File

A circle file is its own little geometry recipe — ring, arc, polygon, star and glyph primitives, each with particles, colour and animation curves, and unknown keys are ignored. Circles are a registry of their own, so you can swap one in without touching the spell. Leave `circle_id` out entirely and the spell falls back to Beam's circle and colour.

## Making It a Scroll

Mages cast from scrolls, so the spell ultimately needs a `craft_recipes` entry with `type: "spell"` pointing at it through `output.magic_id` (see [Importing Crafting Recipes](custom_recipes_guide.md)). The scroll is one shared item, with no per-spell texture or model.

## The Display Name

The name in the scroll tooltip and the JEI page body come from two language keys, `magic.wandscape.<spell id>` and `magic.wandscape.<spell id>.desc`, falling back to the raw id and to the file's `description`.

## Getting a Mage to Use It

A mage only uses the spell once the scroll sits in one of its cast strategy slots. On the way in the server files it by `default_group`, keeps at most three per group, and drops duplicates; a scroll whose id resolves to no spell is discarded outright, so the slot can look occupied while holding nothing.
