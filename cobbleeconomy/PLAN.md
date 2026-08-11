# Hand-off: shop curation — diamond ladder + cobblestone sink

## Context

`cobbleeconomy` is a Fabric 26.2 / JDK 25 server-side mod: a two-currency economy
(cobblestone = common, diamond = premium), independent balances, a bank, and a
configurable shop. Read `@/a:/cobbleeconomy/README.md` in full — it documents
the shop config format, admin commands, and the currency-sink philosophy
(**"you are responsible for the sinks"** — money spent in the shop is destroyed,
not moved to an admin account).

**This is a config/curation task, not a code change.** No source files need
editing. Everything happens through `/cobbleeconomy shop add` /
`shop setprice` commands, or by hand-editing `config/cobbleeconomy/shop.json`
directly (format documented in the README under "Configuration").

## What to do

### 1. Diamond sink — the `wondrous` item catalog

`wondrous` is a sibling mod with a small catalog of custom items (see its
README for the full list: pocket crafting stations, flying boots, area tools).
`cobbleeconomy` already supports referencing a wondrous item id instead of a
vanilla registry item, prefixed with `wondrous:` — this is documented in the
README with a working example:

```json
"big_hole_shovel": { "item": "wondrous:big_hole_shovel", "quantity": 1,
                      "price": 12, "currency": "diamond", "category": "Rare Resources" }
```

Add a shop entry for every item in `wondrous`'s catalog, priced in diamonds
only. `cobbleeconomy` should become **the only way to acquire wondrous items** —
there is no other distribution path for them once this is done.

**Pricing target:** assume an active player earns roughly 10-15 diamonds/day
once the other faucets (dailyquests, scheduled trivia, bounties) are live.
Price the cheapest item around 2 days' income and the most expensive (flying
boots) around 2-3 weeks' income. Build out a real price ladder across the
catalog rather than clustering prices — the point is a visible progression.

**Do not price the area tools (`big_hole_pick`, `big_hole_shovel`) until
confirming their balance fix has landed** — there's a separate hand-off for
`wondrous` that fixes them being strictly better than vanilla diamond tools with
no drawback. If that fix hasn't shipped yet, either hold off adding these two
items or price them high enough that the imbalance is a minor issue rather than
the main event.

### 2. Cobblestone sink — common/uncommon vanilla blocks

Add a straightforward vanilla-block catalog priced in cobblestone: ice, sand,
terracotta, quartz, and similar common/uncommon building blocks. These don't
need careful individual pricing — they exist so that ordinary building activity
has somewhere to spend cobblestone, since cobblestone generators are effectively
free money printers per the README's own warning. Pick reasonable per-item
prices and move on; this can be retuned later based on the total-supply number
`/baltop` prints.

## What NOT to change

- No code changes to `cobbleeconomy` itself — the `wondrous:` item-reference
  feature and dual-currency pricing already work per the README.
- Don't add a cobblestone<->diamond exchange rate. The currencies are
  deliberately independent; adding a conversion undermines that.

## Done when

- Every item in `wondrous`'s catalog has a corresponding `shop.json` entry
  priced in diamonds, forming a visible cheap-to-expensive ladder.
- A cobblestone-priced block catalog exists covering common building materials.
- `/shop` in-game shows both catalogs correctly, and `/buy` works for at least
  one item from each.
- Note the total-supply numbers `/baltop` prints for both currencies at the
  time this is done, as a baseline to compare against after two weeks of the
  new faucets (scheduled trivia, bounties) running.
