# 🧳 Tourists

Tourists are the town's cash cow — and the pickiest guests: they walk into town along the roads in the morning, and leave on their own once they've had their fill. Your whole management loop revolves around these few need bars.

## What Tourists Want

Tourists have three **need bars**: **Comfort / Magic / Wonder**. They go to places that fill these bars — browsing shops, buying things, visiting service buildings, relaxing in a bathhouse — filling the bars one by one.

Each tourist also has a **profile**: favoring Comfort, favoring Magic, or favoring Wonder (at a glance, it's the difference between "this shop speaks to me" and "this shop doesn't understand me at all"). They prioritize feeding the bar they favor.

## The Info Screen (Right-click a Tourist to Open)

| Item | Description |
| :--- | :--- |
| **Need bars** | Comfort / Magic / Wonder — current and needed amounts |
| **Energy** | Stamina. Interactions consume it; when exhausted, only relax buildings are left |
| **Level** | Wealth level (1~5). Higher means more spending power and higher needs |
| **Wallet** | Money on hand |
| **Stay** | How many nights already stayed, how many days planned in total |
| **Itinerary** | Which buildings visited, with each visit's gains (Comfort+X Magic+Y Wonder+Z · Energy+W) |

## A Tourist's Day

1. **Arrival**: walks into town along the roads in the morning — **only on paved roads**.
2. **Explore**: automatically picks the most suitable place to go (computed from "need gap + energy + wallet" — whoever needs it most gets visited). Buying at shops, receiving services at service buildings, soaking at the bathhouse, withdrawing at the ATM.
3. **Overnight**: if night falls before they've had enough, sleep at the **hotel**, and continue with full energy in the morning.
4. **Departure**: tourists with **all three need bars full** leave content at night — the town gains **experience** then. Those who haven't had enough leave too, but give no experience at all.

Tourists stay **2~4 days** and visit each building only once during the whole stay — so don't expect one or two shops to feed everyone.

## The Three "Supply Stations"

- **Relax buildings** (bathhouses etc.): **restore energy**. Tourists whose energy is exhausted but who don't want to leave head straight here.
- **ATM buildings** (ATMs): **refill the wallet**. Tourists who ran out of travel money but haven't finished exploring come here to withdraw and continue.
- **Hotel**: sleep overnight to restore energy — see the [Hotel Guide](hotel_guide.md).

With all three in place, tourists can fill their need bars in town and leave satisfied.

## Queues & Capacity

How many tourists one building can **host at once** depends on how many **interact spots** it has (the spots where tourists perform actions). When all spots are full, later arrivals queue; those who wait too long leave. Want to host more? Add more interact spots, or build more buildings of the same type.

## Mage Tourists

About **5% of tourists are mage tourists**. Mage tourists with all three need bars full leave resumes at the [Tavern](tavern_guide.md) — free recruitment; this is how you build your wizard roster.

## How to Keep the Town Well Served

- **Roads paved and buildings built**: a bit of everything — shops, service, relax, ATM — gives tourists places to go.
- **Building values on target**: the Comfort/Magic/Wonder values a building carries decide which need bar it feeds and how fast.
- **Enough spots and roads**: enough spots and open roads let tourists explore smoothly, and more of them leave satisfied.

---

[Shop (Where the Money Is Made)](shop_guide.md)  
[Hotel & Service Buildings](hotel_guide.md)  
[Tavern (Recruiting Wizards)](tavern_guide.md)  
[📖 Back to the Guide Index](index_guide.md)
