# 🏪 Shop

Shops are where the town makes money: tourists spend money on goods, and you collect elements into the warehouse. It looks simple, but it is the engine of the whole economy — how tourists' need bars get filled depends largely on the shops.

## How a Shop Runs

- A shop's **goods list** is decided by its building config: each good carries its own **Comfort / Magic / Wonder values** (the Bread Shop sells food, the Flower Shop sells flowers, the Book Shop sells books — each with its own taste).
- Tourists stand on the shop's **interact spot** to "browse" (the little figure really does look like browsing), staying for the building's configured **interaction duration**.
- Tourists spend their **travel money**, and you collect elements into the warehouse according to each good's elemental value and the **profit rate** — the higher the profit rate, the more you earn from the same good.
- The values on goods fill the tourists' matching **need bars**: goods with high Comfort feed comfort lovers, goods with high Magic feed magic lovers. Configure the goods right, and tourists stay satisfied.

## What You Need to Do

**Right-click the shop** to open the panel and adjust each good's **max stock** (0~64): the shelves only hold so much — if it sells fast, stock more. The system auto-restocks from the warehouse; the max stock decides how long a good lasts.

A shop isn't done when built — **it needs an interact spot, the right goods, and road access**. Do all that, and the shop becomes a shop that truly makes money.

## The Shops Available

The mod ships with a Bread Shop, Flower Shop, Book Shop, and Magic Shop. Want to open your own new shop? Use the shop mode of the [Creative Building Scanner](guide:creative_scanner_guide) to configure goods and profit rate — it takes effect the moment you export.

(By the way: a shop's goods list is defined in the building JSON — `goods` + `profit_rate` + interaction duration. The Bread Shop has 6 kinds of food, the Flower Shop 6 kinds of flowers, the Book Shop 6 kinds of books, each with its own focus — placing them on different streets attracts tourists of different profiles.)

---

[Tourist System Guide](guide:tourist_guide)  
[📖 Back to the Guide Index](guide:index_guide)
