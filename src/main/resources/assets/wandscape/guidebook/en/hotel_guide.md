# Hotel & Service Buildings

Service buildings make tourists spend energy and produce elements for the town. Hotels add overnight stays on top of that: once a tourist checks in, they remember that hotel, come back to sleep every night, wake up with full energy, and only check out when they leave town.

## Hotel

When night falls (from ~19:40) tourists prefer a hotel for the night; if they still have no hotel by late evening (~21:00), they head straight to the nearest one. Check-in **forces them into bed**; they **wake up in the morning with full energy**, shop during the day, and return to the same hotel to sleep each night. Once checked in, tourists are never cleared for being out late — they stay until their trip ends (or leave the same night they max out their needs).

| Item | Description |
|---|---|
| Beds | Fixed per hotel; when full, later tourists go elsewhere or leave |
| Animation | Tourists lie down to sleep on check-in (purely visual; does not affect the bed). If no bed is free they take the nearest one; if the hotel has no beds at all they stay put |
| Guest list | Checked-in tourists stay on the hotel's guest list after waking up; they only check out when leaving town |

## Service Buildings

Buildings like the Service Hall operate during the day: tourists enter to receive service, **spending energy and producing elements for the town**.

## Built-in Buildings

| Building | Beds | Energy Use | Elements Produced |
|---|---|---|---|
| Hotel | 8 | Higher | Earth / Wood / Water |
| Service Hall | None | Lower | Fire / Earth / Wind |

Different service buildings do different things: some produce different elements, some add overnight stays. To configure a new one: use the service mode of the [Creative Building Scanner](creative_scanner_guide.md) — it takes effect on export.

---

[Shop](shop_guide.md)  
[Tourist System Guide](tourist_guide.md)  
[Back to the Guide Index](index_guide.md)
