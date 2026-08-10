# 🧳 Tourists

Tourists walk into town along roads in the morning and leave once their three need bars are full. The management loop revolves around their three need bars.

## Three Need Bars

Tourists have three **need bars**: **Comfort / Magic / Wonder**. They go to places that fill these bars — shops for goods, service buildings for service, relax buildings to recover energy — filling them one by one.

Each tourist has a **profile**: favoring Comfort, Magic, or Wonder, and prioritizes the bar they favor.

## The Info Screen (Right-click a Tourist to Open)

| Item | Description |
| :--- | :--- |
| **Need bars** | Comfort / Magic / Wonder — current and required amounts |
| **Energy** | Consumed by interactions; when exhausted, only relax buildings remain |
| **Level** | Wealth level (1~5). Higher means more spending and higher needs |
| **Wallet** | Travel money on hand |
| **Stay** | Nights stayed / total days planned |
| **Itinerary** | Buildings visited and gains per visit (Comfort+X Magic+Y Wonder+Z · Energy+W) |

## A Tourist's Day

1. **Arrival**: walks into town along roads in the morning — **only on paved roads**.
2. **Explore**: automatically picks the most suitable destination (computed from need gap + energy + wallet — the most lacking bar gets visited first). Buying at shops, services at service buildings, soaking at bathhouses, withdrawing at ATMs.
3. **Overnight**: from nightfall (~19:40) tourists prefer a **hotel**; after checking in they remember that hotel, come back to sleep every night and wake with full energy. Tourists still without a hotel head straight to the nearest one in the late evening (~21:00).
4. **Departure**: tourists with **all three need bars full** leave happily that night, and the town gains **experience**; hotel guests stay until their trip ends (2~4 days); tourists with neither a hotel nor full bars leave late at night (after ~21:00).

Tourists stay **2~4 days** and visit each building only once during the whole stay (**ATMs are the exception**: while travel money is still left and the wallet runs low again, they can go back for another withdrawal after a short cooldown — money comes out in batches).

## Three Support Buildings

| Type | Function |
|---|---|
| **Relax buildings** (bathhouses etc.) | Restore energy; visited by tourists whose energy is out but who won't leave |
| **ATM buildings** (ATMs) | Refill the wallet; visited by tourists out of travel money but unfinished |
| **Hotel** | Sleep overnight to restore energy — see the [Hotel Guide](hotel_guide.md) |

With all three in place, tourists can fill their need bars in town before leaving.

## Queues & Capacity

| Item | Description |
|---|---|
| Capacity | Set by the building's number of **interact spots** |
| Queue | When all spots are full, later arrivals queue; those who wait too long leave |
| Expand | Add more interact spots, or build more buildings of the same type |

## Wizard Tourists

About **5% of tourists are wizard tourists**. Wizard tourists with all three need bars full leave resumes at the [Tavern](tavern_guide.md) and can be recruited **for free**.

## Management Tips

- **Roads paved and buildings built**: a bit of everything — shops, service, relax, ATM — gives tourists places to go.
- **Values on target**: the Comfort/Magic/Wonder values a building carries decide which need bar it feeds and how fast.
- **Enough spots and roads**: smooth travel raises the share of tourists leaving with all bars full.

---

[Shop](shop_guide.md)  
[Hotel & Service Buildings](hotel_guide.md)  
[Tavern](tavern_guide.md)  
[📖 Back to the Guide Index](index_guide.md)
