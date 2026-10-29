# Charter: your first hour

This is a hands-on tutorial for someone who knows Minecraft well and has never
seen Charter. You will found a faction, raise its keep, stock it with food, claim
land, then raise an outpost, declare war on a rival and capture theirs. It
takes 30 to 45 minutes.

You don't need to install anything: Charter runs on the server, and your normal
Minecraft 26.2 client works as it is.

## The idea in one minute

On most faction servers you take land by breaking through walls. In Charter you
can't break anything on someone else's land, ever, and **a keep can never be
taken at all**. What is at stake in a war is smaller, and it is yours to defend.

- A faction holds land with a **keep**: a block called the **Nexus** with the
  3 by 3 chunks around it. A nation has one keep.
- Every keep has a **granary**, a chest of food. The granary is the keep's
  **upkeep**: a full one lasts 14 days. You are warned at 7, 3 and 1 days left.
  If it runs dry the claim lapses and you must put a Nexus down again.
- Every organization may raise one **outpost**: plant a **War Banner**. An
  outpost holds a **deposit** of diamonds. It is protected, unless another
  nation **declares war** to capture it.
- To capture it, they wait out a notice (12 hours), then hold its chunk for 10
  minutes with none of your people in it. You defend by standing in it, or by
  using `/outpost` to be there. If they win, they take the banner and the deposit.

So the game is about showing up, and about what you are willing to put at risk.

## Starting the tutorial

This needs an operator. If that's you, stand on open, fairly flat ground far
from spawn and anything built, and type:

```
/charter admin scenario run tutorial with <your name>
```

You'll get a book called **Charter: A First Hour**, the items you need, and two
pillars: an **emerald** one beside you, where your keep goes, and a **gold** one
12 chunks east, where your banner goes. Brinewatch, the rival faction, already
holds a keep just past the gold pillar. Its members are offline stand-ins, so
nobody will fight back.

The tutorial makes siege time run **24 times faster**, so an outpost raises in 5
minutes instead of 2 hours. The notice before a capture still counts in siege
time, so an operator can move the clock on for you with `/charter admin time
advance 12h`. On a normal server, a full granary lasts 14 days.

The book in your inventory has the same steps in short. This guide adds what
you should see at each step, and why.

## 1. Name a banner

You carry a white banner and a lodestone, and an **anvil** stands beside the
emerald pillar. Rename the banner at the anvil (it costs one level, and you were
given a few). **The name you give it becomes your faction's name.**

## 2. Craft the Nexus

Put the lodestone and your named banner in a crafting table, and nothing else.
You get a **Nexus** with a blue name: your faction's. The banner is used up.

## 3. Found your faction

Walk to the **emerald pillar**. Place the Nexus on the ground near it, the way
you'd place any block. Placing it **founds the faction and raises its keep in one
step**: the 64 diamonds you carry pay for the keep, and you become the **Lord**.

You should see a green message that your faction is founded. The **3 by 3
chunks** around the Nexus are now yours: the centre chunk is the **core**, the
eight around it are the **ring**. A faction needs a second member before it is
more than a name.

**Hold the compass** in your hand: the chunk borders around you are drawn in
particles. Green is yours, red is someone else's. (Holding a War Banner does the
same.)

The Nexus can't be broken by anyone, you included, while the keep stands.

### Your Charter

You were also handed a book and quill called the **Charter of Rights and
Freedoms**. Use it (right-click) or type `/charter`: it opens a menu showing where
you stand. **Rights** are what you may do on your own, **Freedoms** are what
someone else does for the faction, and **Your situation** lists votes waiting for
you. Open **your faction** from there to see members, the treasury, the keep and
how long its granary would last. Anything you may do is a button. The older chat
page is still there as `/charter page`.

### Fill the treasury

Things a faction builds are paid for from its **treasury**, not from your
inventory. An outpost (the War Banner) needs a deposit of 8 diamonds from the
treasury. Open your Charter, then your faction, and press **Pay in 8 diamond**.
The items leave your inventory and appear in the treasury line.

## 4. Stock the granary

Place a **chest in the same chunk as the Nexus** (the core chunk). A green
message says it's now part of your keep's granary. Chests placed touching it
join it, up to 8.

Right-click it. Instead of a normal chest screen, you get the **granary
screen**, with your provisions and how many days they'd last. Put **40
bread** in.

Why it matters: the granary is your keep's upkeep. A full one drains evenly over
14 days, and nothing else changes that (food also spoils slowly, fresh food
fastest). Warnings come at 7, 3 and 1 days left. When it's empty the claim
lapses: your blocks stay, but the land is open until you put a Nexus down again.

## 5. Claim land

Type `/charter map`. You get an 11 by 11 chunk map in chat:

- `@` is you, `K` and `k` your keep's core and ring, `o` farm claims
- yellow `+` marks free chunks touching your land: **click one to claim it**
- red is someone else's land; hover over any square to see what it is

Claim a chunk or two. These are **farm claims**, for crops and animals.
How much you can claim grows with your number of active members.

On your land, players from other factions can walk around, but they can't
build, break, open chests, set fires, flood it with lava or push blocks in with
pistons.

## 6. Meet a rival

Walk **east** (the way the sun rises) past the gold pillar. With the compass in
hand you'll see Brinewatch's borders in red. Their keep is 12 chunks east of
yours, and they have an outpost beyond it.

Try to break a block on their land. You can't, and you get a message saying the
land is protected. That's the core rule of Charter: **a keep is never taken.**

## 7. Plant an outpost

Go back to the **gold pillar**. Place the **War Banner** on the ground in that
chunk. Your treasury keeps **8 diamonds** of its own as a deposit (the second
outpost of a nation costs 4 more, and so on).

You'll see:

- a hay bale with a lit campfire beside the banner, sending up a **smoke
  column** that can be seen from far away
- a message that your outpost is **raising**

An outpost takes 2 hours of siege time to raise, which is **5 minutes** in the
tutorial. While it raises, **anyone can pull the banner down**, which ends the
outpost and gives you the deposit back.

## 8. Stock the outpost

Place the **barrel right next to the banner**. A message says it's now your
outpost's **store**. Open it and put **48 bread** in.

The store is your supply: `/outpost` takes you to your outpost from anywhere in
the same dimension and costs it 1 food. It refuses if the store is empty.

## 9. Declare war

Once your outpost has raised, open your Charter and press **War**. It lists
the outposts of other nations. Press the one of Brinewatch, then **Declare war**.
The page tells you what it takes: you may declare because you hold a raised
outpost of your own, nothing can be done to theirs for 12 hours, and if the war
runs out with nothing taken neither nation may declare on the other for 3 days.

Both nations are told, now and again when capture opens. Brinewatch's War page
says when.

## 10. Take it

When the notice is over, walk to Brinewatch's outpost and **stand in its
chunk**. If any member of Brinewatch stands in it too, the hold starts over. With
none of them there for **10 minutes**, the banner comes down, the land is
released, and the 8 diamonds of their deposit go to your treasury. For 30
minutes only you may plant an outpost on that chunk.

You've played the whole loop of attack.

## What next

Defence is the other half of the game, and it's most of the play:

- **Showing up.** An outpost is only taken when its chunk is held with none of
  its people in it. Stand in it, or use `/outpost` to arrive in time.
- **Feeding the keep.** The granary is the keep's upkeep. Warnings go to every
  member when it gets low. Let it run dry and the claim lapses; your blocks
  stay, but the land is open to everyone until you put a Nexus down again.
- **Your own travel.** `/home` takes you to your bed, `/keep` to your nation's
  keep, `/outpost` to your outpost. Each waits 5 seconds (don't move, don't get
  hurt) and then needs 30 seconds before the next.
- **Justice.** A faction can arrest trespassers with a lead, hold them in a
  marked cell, fine them, or execute them (a short respawn wait, then banishment
  from that land for a while). Every punishment has hard caps, so nobody can be
  locked up for days.
- **The Chronicle.** Everything that happens is written up as a story. Put a
  lectern in your keep's core chunk to read your faction's.

Ask your operator for the `jail` scenario (arrest and sentencing).

## Resetting

`/charter admin scenario clear` takes the whole tutorial down: Brinewatch, and
the faction you founded during it. Then you can start over. Blocks you placed,
and the pillars, stay in the world.

The faster time and the other tutorial settings last until the server
restarts, or until an operator types `/charter admin time scale 1`.

## If something goes wrong

| What you see | Why, and what to do |
|---|---|
| "You already belong to ..." when starting | You're in a faction already. Run `/charter admin scenario clear`, or leave it from your `/charter page` |
| The Nexus is refused | Check you carry 64 diamonds, and that the Nexus has a name (an unnamed one only works for a faction that already exists). A keep also can't go within 4 chunks of another keep, and a nation has only one |
| The chest is just a chest | It must be in the **same chunk** as the Nexus. Press F3 + G to see chunk borders |
| The barrel is just a barrel | It must touch the banner and be in the banner's chunk |
| The banner is refused | The treasury needs 8 diamonds for its deposit (press Pay in on the faction page), and an organization has only one outpost |
| You cannot declare war | You need a raised outpost of your own, the target must be raised, and there is no truce with that nation |
| A link in chat does nothing | Links run out after a while. Open the page again (`/charter`, `/charter page`, `/charter map`) |

## Words you'll see

| Word | Meaning |
|---|---|
| Keep | A permanent base: the Nexus and its 3 by 3 chunks. Nobody can take it |
| Nexus | The block at a keep's centre. It can't be broken while the keep stands |
| Core, ring | The Nexus's chunk, and the eight chunks around it |
| Farm claim | Extra land you claim next to your own, for fields and animals |
| Granary | The chests in a keep's core chunk that hold its food: the keep's upkeep |
| Lapse | A keep whose granary ran dry loses its claim; blocks stay |
| Outpost | A smaller base raised by planting a War Banner, with a deposit of diamonds |
| Outpost store | The barrel or chest beside an outpost's banner that holds its food |
| Deposit | The diamonds an outpost holds; the captors take them |
| War | A declared goal to capture one outpost, with a notice, a hold and a cooldown |
