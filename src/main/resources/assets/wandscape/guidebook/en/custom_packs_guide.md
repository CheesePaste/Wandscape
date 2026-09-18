# Importing Building Packs

> A building pack is simply a directory. Create `data/<namespace>/buildings/<pack name>/` and drop building JSON into it and they belong to that pack; add a `package.json` inside and the pack gets a name and an icon.

Once a town has many buildings, one flat list stops being readable. A pack is the unit that groups buildings, ships them as a set, and keeps two authors' same-named buildings from colliding — inside one pack, ids only have to be unique within that pack.

## The Directory Is the Pack

- `buildings/cottage.json` — a building sitting directly under `buildings/` belongs to the `default` pack
- `buildings/medieval/inn.json` — the directory name `medieval` is the pack name
- `buildings/medieval/package.json` — this pack's metadata
- `buildings/medieval/tower/watchtower.json` — deeper nesting is only file organisation, still the `medieval` pack
- `buildings/oriental/tea_house.json` — a different pack

Only the **first** level of subdirectories under `buildings/` names a pack; going deeper is tidying, not membership.

## Naming One File's Pack

A building JSON may also carry a `package_id` field of its own, which **outranks the directory**. Writing `default` there is the same as leaving it out, since it falls back to the directory — the field is for pulling one file out of its folder, nothing more.

## Pack Metadata

`package.json` sits in the pack directory, and every key in it may be omitted:

| Key | Default |
|---|---|
| id | the directory name |
| name | the pack id |
| description | empty |
| author | empty |
| version | `1.0.0` |
| icon | `minecraft:stone_bricks` |
| priority | 100 |

## Name and Ordering

`name` takes either a literal string or a language key — both go through the same "use the lookup if it resolves, otherwise use the text as written" path, so a plain name is perfectly legal and you need not invent a `wandscape.pack.*` key.

`icon` takes an item id. The lower the `priority`, the earlier the pack sorts, and the mod's own core pack sits at 0.

## The Default Pack

Every building the mod ships lives at the root and belongs to a pack called `default`, displayed as Core Pack.

**A `buildings/package.json` in any namespace is taken as that default pack's metadata** — to write your own pack's `package.json`, put it inside the pack directory; one at the root renames the default pack instead.

## When the Directory and the Id Disagree

A directory with no `package.json` is not an error; a placeholder pack is created, named after the directory. Writing a `package.json` whose `id` differs from its directory name leaves the pack list calling it one thing while its buildings hang off another, matching neither.

## Can Players Switch a Pack Off

Yes. The Settings Center has a Pack Library page that disables a whole pack. Disabling only affects what the build list shows — buildings already standing in the world keep working and keep paying out. It is there for players tidying a list, not for pack authors to gate features with.

## Working With the Scanner

The Creative Building Scanner has a target-pack field; type a name that does not exist yet and the export creates that directory and writes a `package.json` skeleton into it, recording you as the author. The pack name is lowercased, so `Medieval` and `medieval` are one and the same pack.

A scanner export goes into the save's own datapack under `data/wandscape/buildings/<pack name>/` — the pack and the namespace are two separate things, and an exported file always sits in the `wandscape` namespace.
