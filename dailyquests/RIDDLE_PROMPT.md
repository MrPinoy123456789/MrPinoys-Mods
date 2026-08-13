# Prompt: generating more daily riddles

Paste everything below the horizontal rule into ChatGPT. Update the "already in
the pool" list as the pool grows. The output goes under the `"quests"` key in
`config/dailyquests/quests.json`.

---

You are writing riddles for a Minecraft server. Once a day the server posts one
riddle. It secretly describes a vanilla item; players work out what it is and hand
that item in. There is no answer to type — turning in the right item *is* the
answer. The riddle is the entire puzzle, so it has to be worth reading.

## What a good riddle here actually does

A good riddle is **a small act of misdirection followed by a click.** It describes
something true about the item from an angle the player has never heard it
described from, leads them briefly toward the wrong answer, and then hands them
the right one. It rewards knowing Minecraft, not knowing vocabulary.

Study these four. They are the standard; match them.

> **"Look at me and I will hate you for it. I go where you point, and I take a
> little of you with me when I land. Bring me six."** → `minecraft:ender_pearl`
>
> Why it works: three separate real mechanics (endermen aggro when you look at
> them, pearls teleport you, pearls deal fall damage on landing), each stated
> obliquely. "Takes a little of you" is the click.

> **"I am what is left of an apology that arrived far too late, and far too
> close. Bring me eight."** → `minecraft:gunpowder`
>
> Why it works: it never mentions creepers, explosions, or green. It describes the
> *feeling* of the death, and the player supplies the rest.

> **"I have been drawn, cast, and mended, and I began in a corner nobody sweeps.
> Bring me twelve."** → `minecraft:string`
>
> Why it works: three uses (bow, fishing rod, repair) that each point somewhere
> different, resolved by one concrete image of a cobweb.

> **"I did not die in my bed. I came apart in the first light, and what fell out
> of me will buy the loyalty of any wolf. Bring me twelve."** → `minecraft:bone`
>
> Why it works: opens as a human tragedy, turns out to be a skeleton burning at
> dawn. The wolf line confirms it without naming it.

## What "lame" looks like — do not produce these

- **"I am white and fluffy and I come from sheep. Bring me twelve."**
  Names the source outright. There is no puzzle, only a lookup.
- **"I am the fire that burns without flame, born of the Nether's heart, forged
  in shadow and ancient night. Bring me five."**
  Abstract-noun mush. It sounds like a riddle and means nothing. It could be
  blaze powder, magma cream, netherrack, or a ghast tear. This is the single most
  common failure — do not write it.
- **"I am found in caves and used for making tools. Bring me twelve."**
  True of nine different items. A riddle with more than one correct answer is a
  bug, not a riddle.
- **"I am not alive, yet I grow; I have no mouth, yet I speak."**
  A generic riddle-shaped sentence with no Minecraft in it at all.

## Hard rules

1. **Exactly one item can satisfy the riddle.** Before you keep an entry, ask what
   a player might wrongly bring first, and make sure some line rules it out. If
   two items both fit, cut it or add a disambiguating detail.
2. **Never name the item, and never use a word that trivially contains it.** A
   riddle for `honeycomb` cannot say "honey" or "comb".
3. **First person, present tense, the item's own voice.** "I am…", "I sleep…",
   "I was…". But **no more than a third of your riddles may begin with "I am"** —
   open with a verb, a denial, a memory, an accusation, a place.
4. **Ground every riddle in at least one concrete Minecraft fact** — a mob's
   behaviour, a biome, a crafting use, a sound, a time of day, a way players
   actually die. Vibes alone are the failure mode above.
5. **One or two sentences, then the ask** as its own final sentence:
   `Bring me <number spelled out>.` — "Bring me twelve.", never "Bring me 12."
6. **The spelled-out number MUST equal the `count` field.** Verify every entry.
   This is the most common mechanical error.
7. **Solvable in a few seconds by someone who knows the game.** Aim for a knowing
   smile, not a stumper. If it needs the wiki, it is too clever.
8. **Dry, evocative, slightly archaic.** Wit is welcome; jokiness is not. No
   memes, no modern slang, no emoji, no exclamation marks, no rhyming couplets.
9. Never break the fiction with "player", "server", "quest", "craft", or "block".

## Rotate the technique

Do not write thirty of the same riddle. Spread your entries roughly evenly across
these six shapes, and vary them within each:

1. **False tragedy** — opens as something sad or human, resolves as a game
   mechanic. (the bone example)
2. **The grudge** — the item resents the player, or the mob it came from does.
3. **Three uses, one source** — list what it becomes, not what it is. (string)
4. **The death report** — describes how players usually encounter it dying.
   (gunpowder)
5. **Process** — describes the transformation that produced it, from the inside.
6. **The denial** — defines the item by what it is repeatedly mistaken for.
   "I am not the one you want. I am the one you keep finding instead."

## Item and count rules

1. **Vanilla only**, valid in modern Minecraft (1.21+). Nothing modded,
   creative-only, or unobtainable in survival.
2. **Renewable and farmable in a few minutes** of directed effort. This is a
   reason to log in, not an evening's work.
3. **Counts run 4–16, scaling inversely with rarity:**
   - bulk farmable (wheat, string, bone, redstone): 12–16
   - mid-tier (blaze rods, amethyst, honeycomb, prismarine): 6–12
   - genuinely annoying (phantom membrane, ghast tear, nautilus shell): 4–6
4. **Nothing gated behind a rare structure existing nearby** — no elytra, heart of
   the sea, dragon egg, or music discs.
5. **No duplicate items** within your output.

## Already in the pool — do not reuse these items

`minecraft:wheat`, `minecraft:blaze_rod`, `minecraft:prismarine_shard`,
`minecraft:glow_berries`, `minecraft:ender_pearl`, `minecraft:bone`,
`minecraft:slime_ball`, `minecraft:gunpowder`, `minecraft:amethyst_shard`,
`minecraft:copper_ingot`, `minecraft:redstone`, `minecraft:honeycomb`,
`minecraft:string`, `minecraft:phantom_membrane`

## How to work

Draft **forty** candidates. Then cut the weakest sixteen — anything that could
describe two items, anything that leans on adjectives instead of mechanics,
anything that sounds like the "lame" examples. Keep the **best twenty-four**.
Quality of the surviving riddles matters far more than hitting the number, so cut
without mercy and rewrite anything borderline rather than shipping it.

## Output

Reason and draft freely, then finish with **one** fenced `json` block containing
only the final array of 24 objects, in this exact schema:

```json
[
  {
    "riddle": "I am gold that no furnace made, and I ripen in rows. Bring me sixteen.",
    "item": "minecraft:wheat",
    "count": 16,
    "answer": "Wheat"
  }
]
```

`answer` is the plain item name, shown to the player only *after* a correct
turn-in. Use straight quotes and plain ASCII apostrophes; escape anything that
needs escaping. Nothing after the closing fence.
