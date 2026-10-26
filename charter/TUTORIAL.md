# Charter: your first hour

This is a hands-on tutorial for someone who knows Minecraft well and has never
seen Charter. You will found a faction, raise a keep, stock it with food, claim
land, then march on a rival and starve their keep out. It takes 30 to 45 minutes.

You don't need to install anything: Charter runs on the server, and your normal
Minecraft 26.2 client works as it is.

## The idea in one minute

On most faction servers you take land by breaking through walls. In Charter you
can't break anything on someone else's land, ever. Land is taken the way real
castles were: **by siege and hunger**.

- A faction holds land with a **keep**: a block called the **Nexus** with the
  3 by 3 chunks around it.
- Every keep has a **granary**, a chest of food. When an enemy camps next to
  your keep, you are **besieged**, and your keep starts eating its granary.
- Food only gets in if someone carries it in through a side the enemy hasn't
  closed off. If the granary runs dry, the keep **falls**.
- To besiege someone, you plant a **War Banner** next to their keep. It becomes a
  **camp**, which needs its own food store to keep going.

So the game is about supply: keeping your own food coming in, and cutting off
theirs.

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

The tutorial makes siege time run **24 times faster**, so a camp raises in 5
minutes instead of 2 hours and a siege lasts minutes instead of days. On a normal
server, a full granary holds out for 3 to 4 days.

The book in your inventory has the same steps in short. This guide adds what
you should see at each step, and why.

## 1. Found a faction

Type in chat:

```
/charter found "My Hold" lordship
```

Use any name you like in the quotes. **Lordship** means one person, the Lord,
decides; the other choice, **commune**, decides by members' votes. You're the
Lord.

Now type `/charter` on its own. This is your **faction page**: members, offices,
keeps, votes and recent history. **Everything you're allowed to do appears as a
link you can click.** You'll come back to this page often.

## 2. Fill the treasury

Things a faction builds are paid for from its **vault**, not from your
inventory. A keep costs 64 diamonds, and a War Banner costs 256 cobblestone. You
were given a little more than enough:

```
/charter vault deposit diamond 72
/charter vault deposit cobblestone 320
```

The items leave your inventory and appear in the vault on your faction page.

## 3. Raise your keep

Walk to the **emerald pillar**. Take the **Nexus** (a lodestone with a blue
name) and place it on the ground near the pillar, the way you'd place any block.

You should see a green message that your keep is raised. The **3 by 3 chunks**
around the Nexus are now yours: the centre chunk is the **core**, the eight
around it are the **ring**.

**Hold the compass** in your hand: the chunk borders around you are drawn in
particles. Green is yours, red is someone else's. (Holding a War Banner does the
same.)

The Nexus can't be broken by anyone, you included, while the keep stands.

## 4. Stock the granary

Place a **chest in the same chunk as the Nexus** (the core chunk). A green
message says it's now part of your keep's granary. Chests placed touching it
join it, up to 8.

Right-click it. Instead of a normal chest screen, you get the **granary
screen**, with your provisions and how long they'd last under siege. Put **40
bread** in.

Why it matters: while nobody is besieging you, nobody eats from your granary
(food still spoils slowly, fresh food fastest, and quickly if you overfill it).
Under siege, your keep eats from it every hour; the more members you have, the
faster. When it's empty, the keep falls.

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
yours.

Try to break a block on their land. You can't, and you get a message saying the
land is protected. That's the core rule of Charter: **walls don't take keeps.
Hunger does.**

## 7. Plant a war banner

Go back to the **gold pillar**. It stands in an empty chunk just outside
Brinewatch's wall. Place the **War Banner** on the ground in that chunk.

You'll see:

- a hay bale with a lit campfire beside the banner, sending up a **smoke
  column** that can be seen from far away
- a message that your camp is **raising**

A camp takes 2 hours of siege time to raise, which is **5 minutes** in the
tutorial. While it raises, **anyone can pull the banner down**, which ends the
camp. That's the defenders' window: a camp raised in the open can be stopped
before it's finished, if someone's watching the walls.

Raising a camp against someone also gives up your own new-faction protection,
the three days of shelter every new faction gets.

## 8. Feed the camp

Place the **barrel right next to the banner**. A message says it's now your
camp's **store**. Open it and put **48 bread** in.

A camp eats too. **A camp with an empty store besieges nobody.** And if the
enemy claims every chunk around your camp, it's **cut off** from home: nothing
can be carried in, and it eats twice as fast. That's how raids end.

## 9. The siege

When the camp has raised (watch for the message), Brinewatch's keep is
**besieged**: its granary starts going down every hour. Brinewatch's granary was
nearly empty to begin with, so this won't take long.

**Stand in your camp's chunk.** Each attacker standing in the camp makes the
keep eat faster, and each defender standing in their keep slows it down. Being
there helps, but it's never required: a siege also runs while everyone is
offline.

## 10. Assault

Once a keep's granary is below a quarter full, its garrison is **starving**.
Then attackers who walk **inside the keep's walls** make it eat **four times as
fast**. You still can't break anything there: you're just standing in their
hall.

## 11. The fall

When the granary runs dry, the keep **falls**. Chat gives you two clickable
choices:

- **Raze**: the Nexus breaks, the keep is gone, and **plunder** (a share of
  their vault's diamonds and cobblestone) spills out on the ground at the Nexus.
  Go and pick it up.
- **Occupy**: you hold their keep and its land for a while; they still own it on
  paper. When the occupation ends, you leave under a truce.

Pick Raze the first time. You've played the whole loop of attack.

## What next

Attack is half the game. The other half is defence, and it's where most of the
play is:

- **Running supplies.** A besieged keep has twelve sides (the chunks touching its
  wall). The enemy closes the ones its camps stand on; the rest stay open. Food
  carried in on foot or by minecart through an open side counts; food that comes
  in through a closed side, or by ender pearl or teleport, doesn't.
- **Breaking a camp.** Each camp has a **sally hold**. Defenders standing beside
  the camp in their own land wear it down; attackers standing in the camp hold it
  up. When it runs out, the camp is exposed, and anyone can break its banner,
  which ends the camp.
- **Justice.** A faction can arrest trespassers with a lead, hold them in a
  marked cell, fine them, or execute them (a short respawn wait, then banishment
  from that land for a while). Every punishment has hard caps, so nobody can be
  locked up for days.
- **The Chronicle.** Everything that happens is written up as a story. Put a
  lectern in your keep's core chunk to read your faction's.

Ask your operator for the other scenarios, preferably with friends: `sally`
(break a camp, then run food in), `partial-siege` (runners on foot and by cart),
`encirclement` (a full ring), `raid` (farms), and `jail` (arrest and sentencing).

## Resetting

`/charter admin scenario clear` takes the whole tutorial down: Brinewatch, and
the faction you founded during it. Then you can start over. Blocks you placed,
and the pillars, stay in the world.

The faster siege time and the other tutorial settings last until the server
restarts, or until an operator types `/charter admin time scale 1`.

## If something goes wrong

| What you see | Why, and what to do |
|---|---|
| "You already belong to ..." when starting | You're in a faction already. Run `/charter admin scenario clear`, or leave it from your `/charter` page |
| The Nexus is refused | Check the vault holds 64 diamonds (`/charter`). A keep also can't go within 4 chunks of another keep |
| The chest is just a chest | It must be in the **same chunk** as the Nexus. Press F3 + G to see chunk borders |
| The barrel is just a barrel | It must touch the banner and be in the banner's chunk |
| The camp raises but nothing happens | Is there bread in the camp store? A camp with an empty store besieges nobody |
| A link in chat does nothing | Links run out after a while. Open the page again (`/charter`, `/charter map`) |

## Words you'll see

| Word | Meaning |
|---|---|
| Keep | A permanent base: the Nexus and its 3 by 3 chunks |
| Nexus | The block at a keep's centre. It can't be broken while the keep stands |
| Core, ring | The Nexus's chunk, and the eight chunks around it |
| Farm claim | Extra land you claim next to your own, for fields and animals |
| Granary | The chests in a keep's core chunk that hold its food |
| Camp | A temporary base raised by planting a War Banner |
| Camp store | The barrel or chest beside a camp's banner that holds its food |
| Besieged | An enemy camp stands beside your keep; your keep eats its granary |
| Encircled | Every side is closed: nothing can be carried in |
| Starving | The granary is below a quarter full; assault becomes possible |
| Sally | Defenders standing beside a camp to wear down its protection |
| Fall | A besieged keep whose granary ran dry; the besieger chooses Raze or Occupy |
