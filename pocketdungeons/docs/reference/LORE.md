# Pocket Dungeons Lore

> **Status 2026-10-10 (freshness pass).** The diary writing pass is done: 26
> entries ship as `diary/entry_1..26.json` (entries 1 to 7 are drafted in
> `LORE-DIARIES.md`, the rest live only in the JSON), and their bands and shell
> unlocks are in the files. Echo shards no longer mint the compass (retired in
> J1): the first compass is free from `/dungeon key` and a lost one is replaced
> at the level earned. The Herobrine Cube station is switched off for now
> (`CubeStation` javadoc). The Feral affix is retired (Restless replaced it).
> The note below is the original 2026 approval record and is kept as written.

> **Nothing in this document is shipped yet, and the approval to ship is
> partial.** `VISION.md` §9 has been revised: it no longer says "not a lore
> project," and instead permits discoverable fiction in vanilla written books
> under the constraints §8 below already imposes on itself. §4's request that the
> room-relocation trick stay unexplained is unchanged and is reinforced by §1.
>
> That revision unblocks two things and deliberately does not unblock a third:
>
> - **Unblocked: the retrofits.** Every row in §7 marked *Retrofit* describes a
>   mechanic that already shipped. They cost no mechanical change, which is why
>   they were never what §9 was guarding against.
> - **Unblocked: the diary writing pass.** §9 open question 2. §10 fixes the
>   voice; the rest needs a writing pass, not more design.
> - **Still gated: every row marked *Designed*.** The Herobrine Cube as "his
>   instrument," the endgame containment loop, the §16.4 compass retarget. In
>   those the fiction is driving the mechanic, which is exactly the failure §9
>   still guards. Each is scoped and judged as a mechanic on its own merits, and
>   if it is only good because of who it belonged to, it is not good.
>
> Design conversation and mechanical proposals live in
> `DOOR_LADDER_BRAINSTORM.md` §16, which this consolidates. Where the two
> disagree, this document is the summary and the brainstorm is the working.

---

## 1. What this is, and what it must not become

Two registers, deliberately separate, decided rather than drifted into:

- **Mechanical naming stays exactly as it is.** `Unhinged Feral Keystone [24]`,
  `Cooked`, `Big L`. Terse, funny, no reverence. None of the fiction below
  touches an item name, an affix name, or a chat line. This is not negotiable
  and is the reason the lore is allowed to exist at all.
- **The lore is a second register**, discoverable and fragmentary, closer to how
  vanilla tells the Deep Dark's story than to a book that explains itself on
  page one. Found, never announced. No exposition, no dialogue, no cutscene.

**The one thing that must never get an explanation** is the room relocation
(`VISION.md` §4): the room is captured from the entrance cell, cleared, and
re-stamped behind the terminal door. No message, no sound, and **no lore entry
either**. It is the most memorable thing in the design because nothing accounts
for it. If a future document, diary page or advancement text ever explains why
the room moves, that is a regression, not content.

---

## 2. The myth

Steve was a hero, and the corruption was an idea rather than a curse: that a
perfect world was possible, and that he could build one.

Pursuing it, he sought the power of the Ender. Absorbing the Ender Dragon's
power is what remade him into Herobrine, and what he built afterward was not the
perfect world. It was a fractured, recursive reflection of one, assembled wrong
and still assembling. That place is Pocket Dungeons.

The Herobrine Cube is his own instrument, the tool he fractured the world with,
which is why the right combination of items placed in it does not produce
anything Steve ever intended to make.

---

## 3. The world: built from fractured memory

The dungeon is less a place Herobrine designed than a place his broken memory
keeps rebuilding, out of the pieces of Steve's own adventures.

This is the diegetic reason runs are procedural and themed rather than one
authored dungeon. Every layout is an echo of somewhere Steve actually went,
replayed out of sequence and out of context, because the mind assembling it
cannot hold the order any more. It is also why the adventure graph is something
players map by walking it rather than something the world tells them: memory is
not sequential, so the route through it is not either.

Deeper runs reach more intact memories. That is what the rare graph nodes are,
and what a Herobrine Cube extract is: something that survived the fracture whole
enough to still work.

---

## 4. Alex

Steve's lover. She went in first.

She was trying to save him from his own madness, before anyone else knew there
was anything to save him from, and she delved these dungeons alone doing it. The
player is unknowingly repeating her attempt, with her tools, along her route.

Her diaries are the delivery mechanism for everything above, found at milestones
rather than handed out. They are not a lore dump cut into pieces; they are her
own arc, encountered out of order the way everything else here is:

- **Early:** hope. She believes she can reach him.
- **Middle:** dread, as what she finds tells her more than she wanted about what
  he has become.
- **Late:** her conclusion, which is also the player's, arrived at first by her:
  saving him is not the same as reaching him, and sealing him away may be the
  only mercy left.

**Her fate is deliberately unresolved.** Whether she succeeded, failed, or
simply stopped writing sets up a different reason the player is the one
finishing this, and that choice belongs with the authoring pass, not here.

---

## 5. The compass

The single object that ties the fiction to the mechanics.

Steve gave Alex **his own** compass, pressed into her hand the morning he left,
without saying why. A recovery compass points at where its owner last died.

Vanilla's behaviour supplies the rest without any help: a recovery compass with
nothing to point at **spins**, and steadies the moment there is a death to find.
So it spun while he lived. The day it steadied is the day it told her he was
gone. She has been following it ever since.

The name stops being a pun and becomes the objective. It is a **recovery**
compass. It points at where Steve died. Recovering him is the entire arc.

The darkest reading, and the best one: he gave it away *before* he died. Either
he knew, or some lucid part of him did, and he handed her the one object that
could lead her back to him afterward. That was the last of the better times.

**Every player's keystone is the same compass, echoed.** A world rebuilt from
fractured memory keeps rebuilding the same objects, so the fact that everyone
mints one is consistent rather than a hole. Every player is unknowingly carrying
Alex's compass and walking Alex's route.

---

## 6. The arc

1. **Descend.** Follow the compass into the wreckage, deeper into what is left
   of him. This is the shipped loop.
2. **Restore.** Past the endgame zone, the goal is not to defeat Herobrine. It
   is to undo what the Ender did and return Steve to what he was.
3. **Seal.** Success seals Steve and Herobrine away together, rather than
   destroying either.
4. **Contain.** The world he built from his own fractured memory does not stop
   existing because he is sealed. Every run after that is containment work:
   going back in to keep a chaotic, memory-built place from spilling out.

Step 4 is the endgame loop, and it is the narrative answer to the same question
`DOOR_LADDER_BRAINSTORM.md` §5 question 1 asks mechanically. Mechanically, a
level-100 player has access the ladder gates. Narratively, they are the one
still doing the containment work everyone else stopped worrying about the moment
the seal held. Neither answer was written to match the other.

---

## 7. Where the lore and the mechanics touch

The load-bearing part of this document. Most of these were **not designed**;
they were noticed afterward, which is why they fit rather than feeling applied.
Honest labelling matters here, because a coupling that arose by accident is
evidence the fiction suits the mod, while one invented to fit proves nothing.

| Mechanic | Status | Lore meaning | Origin |
|---|---|---|---|
| Keystone is a recovery compass | Shipped (M3) | Steve's compass, pointing at where he died | **Retrofit.** Chosen because echo shards find things |
| Echo shards mint the keystone | Retired (J1); the first compass is free | The one vanilla tool for finding what is buried | **Retrofit** |
| Tier-3 Ender/ancient-city palette | Shipped (M6 T6.2) | The wreckage nearest the power that remade him | **Retrofit.** Authored as "rare and deep-tier" |
| Procedural themed runs | Shipped | Echoes of Steve's own adventures, replayed wrong | **Retrofit** |
| Compass spins outside the dungeon | Vanilla behaviour | "They only spin when there is nothing yet to find" | **Retrofit.** Found while designing navigation |
| Adventure graph, walked not told | Proposed (§2.2) | Memory is not sequential | Designed |
| Compass points at the terminal pad, then the room | Proposed (§16.4) | Following him in, then finding the way back | Designed |
| Herobrine Cube: rituals, not crafting | Proposed (§3.5) | His instrument, the one that fractured the world | Designed |
| Intensifier bands as diary milestones | Shipped ladder, proposed use | How far into him you have gone | Designed on shipped |
| Endgame containment loop | Proposed (§16.2) | The job that outlives the seal | Designed |
| **Room relocation** | **Shipped** | **None. Never.** | **Deliberately uncoupled** (§1) |

---

## 8. Delivery rules

1. **Found, not announced.** No quest log, no narrator, no cutscene, no
   tutorial. A player who never reads a diary loses nothing mechanical.
2. **Out of order, on purpose.** Diaries arrive by milestone, not by chapter.
   Nobody reads this start to finish the first time.
3. **Vanilla items only.** `minecraft:written_book` carries every diary. No new
   item, no client mod, consistent with `VISION.md` §9's non-goals.
4. **Two registers never mix.** Nothing in section 2 through 6 changes an item
   name, an affix name, or a system message.
5. **The trick stays silent.** See §1.
6. **The mechanics still carry the weight.** If a system only makes sense with
   the fiction attached, the system is wrong. Every coupling in §7 is a mechanic
   that already worked.

---

## 9. Open questions

1. ~~**Does `VISION.md` §9 get revised?**~~ **Decided: yes, in part.** The bullet
   became "Lore is a second register, never the point," permitting discoverable
   fiction in written books while keeping every guard that mattered. The
   retrofits and the diary pass are unblocked; the *Designed* couplings are not.
   See the header.
2. **The actual diary text.** Not drafted. It needs a writing pass, not
   paragraphs inside a design document. §10 is one specimen, not a start.
3. **Alex's fate.** Succeeded, failed, or stopped writing. See §4.
4. **Is the endgame zone a second dimension?** M8 D1's call. The Ender palette
   points that way; nothing here commits to it.
5. **Is anything ever stated outright?** The current answer is no. Worth
   revisiting once diary text exists and can be judged rather than imagined.

---

## 10. Specimen: one diary entry

Not the authoring pass. One entry, to fix the voice: short, plain, page-shaped
for a vanilla written book, explaining nothing it can imply instead.

> **Page 1**
> He gave me his own. Pressed it into my hand the morning he left and would not
> say why.
>
> **Page 2**
> It spun for eleven days. I told myself that was good. They only spin when
> there is nothing yet to find.
>
> **Page 3**
> On the twelfth it stopped, and pointed, and has not moved since.
>
> **Page 4**
> I have been walking that way ever since.
>
> I do not know what it is I am going to recover.

What that entry is doing, for whoever writes the rest: the gift is the horror
and neither of them saw it at the time; she is the one who called it grim first,
as a joke; the last line lets the item's own name do the work rather than
stating the arc. Nothing is explained. A player who has been paying attention
gets it, and nobody else is told.
