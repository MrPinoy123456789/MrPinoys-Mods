# Themed merchants: the Store takes the floor's mob drops

Status: built 2026-10-02 (`MerchantThemes`, `StorePricing`, `StoreNPC`; tests `StorePricingTest`, `ThemedMerchantGameTest`). Game test not yet run (see Verification). Source: "Theme-Driven Shop Rooms" spec, reconciled against the codebase.

## What the spec asked for, and what already existed

The spec asks for an uncommon Shop Room in the normal room pool, a merchant chosen by the floor's theme, mob drops as currency, randomized trades from pools, and the existing invincible vendor. Most of that was already here, so no new room type, no new NPC and no new weighting were added.

| Spec item | Already in the codebase | Change |
|---|---|---|
| Shop Room in the normal pool | `the_store` (weight 1, `maxPerDungeon` 1, `minDepth` 1, `content: store`), a spur candidate, plus the `anomaly_store_*` rooms and the Store cube recipe | none; frequency left alone |
| Reuse the invincible vendor | `StoreNPC` (tagged invulnerable villager, SGUI shop) | reused; spawn now takes the floor theme |
| Floor theme | the run theme id, already passed to the room stamp | `MerchantThemes` maps theme id to a merchant |
| Drops as currency | prices were emeralds only | each stock entry carries its own currency |
| Randomized trades from pools | 7 fixed category slots | pool groups with the spec's counts (common 2 to 3, utility 1 to 2, combat 1 to 2, rare 0 to 1, max 7 slots) |
| No salvage UI | the Salvage Bench is separate and untouched | none |

## Merchants

The mapping is the floor's `spawner_prefix` mobs read backwards.

| Theme | Mobs | Merchant | Takes |
|---|---|---|---|
| ossuary | skeleton, stray | Bone Collector | bones |
| deepslate, endless_mine | skeleton, zombie | Bone Collector | bones, rotten flesh |
| frostworks | stray, zombie | Frost Peddler | bones, rotten flesh |
| basalt_foundry | blaze, magma cube | Nether Merchant | blaze rods, magma cream |
| copper_works | creeper, zombie | Scrap Dealer | gunpowder, rotten flesh |
| ender_archive | enderman, silverfish | Archivist | ender pearls |
| infestation, rootworks | spider, cave spider, witch | Web Trader, Root Herbalist | string, spider eyes |
| anything else | | Wandering Merchant | emeralds |

Each slot is priced in one of the merchant's drops, or in emeralds one time in five, so a player who skipped the fights can still shop. A merchant never sells the drop it buys with. A drop's worth is `perEmerald` in `MerchantThemes` (a bone is 2 per emerald of goods, a pearl 0.5); those numbers are first guesses for playtest.

## Departures from the spec, on purpose

- **Blaze Rods, not Blaze Powder.** Blazes drop rods; powder only exists after crafting.
- **Arrows are not a currency.** They are scarce here (`DungeonDrops`), so a merchant that eats them punishes archers.
- **Bones are priced as scarce.** `DungeonDrops` cuts bone drops to a quarter, so a bone is worth more than a rotten flesh.
- **Potions are sold.** The combat group has a potion pool (healing, fire resistance, swiftness, strength, regeneration, night vision, water breathing). The potion is saved in the item field as `minecraft:potion#fire_resistance`, so older saved stores still load.
- **Frequency untouched.** `the_store` is already one room in the weighted pool, capped at one per dungeon. Whether that reads as "uncommon" is a playtest question.
- **The anomaly store is themed too.** `LayoutStamper` used to hand anomaly cells a null theme; the store anomaly now gets the floor's theme (other anomalies still get none).

## Fixed on the way through

- `StoreNPC.saveInventory` added a new inventory tag without removing the old one, and `loadInventory` read whichever came first, so a purchase could fail to stick. It now replaces the tag.
- A purchase counted the cursor stack as payment but only removed from the inventory. Payment is now counted from the inventory only.
- The old save format (`item,name,price,stock`) still loads, always priced in emeralds.

## Verification

Done: `compileJava`, `test` (including `StorePricingTest`), `compileGametestJava`.
Not done: `runGameTest` died at JVM start (Windows paging file too small, `hs_err_pid29948.log` in `build/run/gameTest`), so `ThemedMerchantGameTest` has never run. Run it, and the live check below, when memory is free.

Live check: reach a Store on an ossuary floor with bones in the pack; the villager should read Bone Collector, prices should read "N bones", and a buy should take bones and leave emeralds alone.
