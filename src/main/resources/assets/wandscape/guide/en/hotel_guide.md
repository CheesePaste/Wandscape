# 🏨 Hotel & Service Buildings

Service buildings do two things: **make tourists spend energy and produce elements for you**. One type also **keeps tourists overnight** — that is the hotel. Both count as the `service` category; the only difference is "whether there are beds".

## Hotels (the Kind That Keeps You Overnight)

The hotel is tourists' resting place: when energy is bottomed out or night falls before they've finished exploring, tourists come here for a night, and **check out in the morning with full energy**, then keep exploring.

- **Beds** are decided by the building's `max_occupancy` — when full, later tourists go elsewhere or leave.
- Tourists lie down to sleep on check-in (purely visual, doesn't affect the bed), and get up on their own in the morning.
- Checking in also takes an **interact spot**; if spots are full, they queue up as well.

## Service Buildings (the Kind That Doesn't Keep Overnight)

Buildings like the Service Hall operate during the day: tourists go in to "receive service", **spending energy and producing elements for you**. They work well as "element supply stations" — place one where tourists get tired, they pay energy and you collect elements, a win-win.

## What Makes Them Run

Every service building has a set of parameters: how much **tourist energy each service consumes**, **which elements it produces**, whether it has beds, and how long the interaction takes. Want to build your own? Configure the parameters with the service mode of the [Creative Building Scanner](creative_scanner_guide.md) — it takes effect the moment you export.

(The mod ships with an Inn and a Service Hall: the Inn has 8 beds, consumes more energy, and produces Earth/Wood/Water; the Service Hall has no beds, consumes little, and produces Fire/Earth/Wind. Different styles — placed in different spots, each has its own uses.)

---

[Shop (Where the Money Is Made)](shop_guide.md)  
[Tourist System Guide](tourist_guide.md)  
[📖 Back to the Guide Index](index_guide.md)
