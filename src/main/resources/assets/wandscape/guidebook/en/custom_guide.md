# Customization

> Files go into a data pack; `/reload` applies them. The in-game scanner is the easiest way in.

Three ways in: the **scanner** exports a house in-game without touching any file; a **data pack** changes buildings, element values, recipes, spells and chests with JSON; the **API** is for mod authors writing Java.

## Where the files go

Into your save's `<world>/datapacks/<pack>/data/<namespace>/<category>/`. Any namespace will do; you only need `wandscape` with the same file name to override something the mod ships.

(Next page)

Buildings go in `buildings/<pack>/`, element values in `element_mappings/`, recipes and scrolls in `craft_recipes/`, spells and circles in `magic_spells/` and `magic_circles/`, wild chests in `exploration_regions/`, balance numbers in `wandscape_balance.json`. This is where the scanner exports.

## World or global

Data packs follow the **world**: every save carries its own, and scanner exports live only inside it. The mod's own content sits in the jar.

The settings centre is **global** (`config/wandscape-*.toml`): server-side keys are shared, client-side ones affect only your machine. Scanner presets are global too.
