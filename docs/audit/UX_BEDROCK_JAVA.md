# UX review: every screen, on Bedrock and on Java

Audit of master 478cce26, 2026-11-04, read as a player. About 85 players, mostly new to the mods, about 40 percent on Bedrock (phones and Xbox: no hover, no resource pack, chat fades, right click is a tap or long press, typing hurts). From the Discord study: they read little, say "fishies", "ruins", "/keep", "the book", "commoner", "mercs"; short punchy lines get read, walls do not. Severity: high means a new player stalls or a Bedrock player cannot do what a Java player can; medium means a friend has to explain it; low is polish. Every "Before" is quoted from the file at the line given. UNVERIFIED marks what a real Bedrock client has not shown us. Coin is in Gold Sovereigns.

## 1. The first 30 minutes

### Java, brand new

| Step | What they see | Source | Where they stall |
|---|---|---|---|
| Login in the spawn town | Action bar: "You are in the spawn area. PvP, building and mob fighting are off here." | `charter/.../lang/en_us.json:411` | Fine. |
| About 2 s later, Currency's welcome | "Welcome" / "Two things are money here." / "Coin: copper Marks, iron Crowns, gold Sovereigns. Bank them with /bank all" / "Valor: earned in dungeons and at events. It cannot be traded" / "Type /shop ..." / green "[ Open the shop ]" | `chartercurrency/.../EconomySettings.java:132-137` | The first thing the server teaches is a bank command for coin they do not have. Nothing says what the server is. |
| Fortune's prompt (order with the above UNVERIFIED) | "Fantasy's Fortune: your reels are loaded. [Spin] [Later]" | `fortune/.../JoinPrompt.java:64-72` | They click Spin; the first spin is refused until 5 minutes online (`FortuneService.java:211`). The first click on the server is a "not yet". |
| They try to break, open or eat | Action bar: "Join a kingdom or sign on as a mercenary first. Open your Charter book to choose." | `en_us.json:361` | **They have no book.** Nobody is handed one since 2026-11-03 (`BookMod.java:23-31`). Nothing names the lectern. |
| They step on a keep pad | "Open your Charter book to join a kingdom." | `en_us.json:128` | Same dead end. |
| Charter's own join message | None. `CharterServer.join` delivers mail and trims extra books only | `CharterServer.java:141-157` | No title, no welcome, no direction. |
| A friend says: tap the lectern by the big block | Form or dialog titled "You belong to no faction", body "You belong to no kingdom.", buttons "Go home", "Go to spawn", "Go to hub", "Join a faction", "Become a mercenary", "Get a new Charter book", "More" | `NewcomerPages.java:66-100`, `en_us.json:575, 985, 927-928` | Title says faction, body says kingdom; two travel buttons for about one place (UNVERIFIED that spawn and the hub Nexus differ). |
| "Join a faction" | One button per kingdom, "Everdelle, the Fairy" ... sorted by member count, biggest first | `NewcomerPages.java:173-190` | The UI buries Azrak (5 people). Nothing says what a Fairy or a Fae can do; the Discord already asks "Wait a fae and fairy same thing". |
| A kingdom's page | "A lordship, 10 members, 1 keeps." / "Joining Faeloria makes you a member of the Fae race." / "The server's operators name each kingdom's ruler. Members can leave." / "Ask to join" | `NewcomerPages.java:200-230`, `en_us.json:610, 627` | "1 keeps". No ruler's name, no traits. The "Your Race" page (handoff item 6) was never built. |
| "Ask to join" | "You asked to join Faeloria." then "You have asked. Waiting for their answer." | `en_us.json:633` | **Now they wait**, unable to touch anything, until a Lord of that kingdom is online. For Azrak or Aurelia at off hours that is hours. |
| A Lord admits them | "Faeloria took you in." | `en_us.json:643` | No title, no "you are now a Fae: here is what that means". |
| Within a tick, Foundry | "Briarthorn Staff is in your recipe book. Craft: a stick, an iron ingot, a vine and sweet berries. Fits the 2 by 2 grid." | `foundry/.../RecipeUnlocks.java:64-69` | A good line. Chat only. |
| Fae only, each login until the first dash | A 50 word chat paragraph on the wings, plus an action bar hint of 67 characters near a jump | `foundry/.../JumpTip.java:15-18` | Every other race gets no movement teaching at all. |
| "Go to keep" | Boss bar "Going to your keep. Hold still.", action bar "Entering Faeloria, land of the Fae", chat "You are at your keep." | `en_us.json:1083, 407, 121` | Good. "land of the Dwarf" and "land of the Human" read wrong (singular). |
| First meaningful choice | Craft the rank 1 weapon in the 2 by 2 grid | | Minute 20 if a Lord was online; never if not. |

### Bedrock phone, brand new (differences)

- The Currency welcome is chat that fades; "[ Open the shop ]" does nothing; "/bank all" means opening the keyboard.
- Fortune sends a native modal form ("Your reels are loaded." / Spin / Later, `JoinPrompt.java:55-60`): the best first impression the server makes on a phone, and it refuses them for five minutes.
- The "Open your Charter book" action bar line is seen and means nothing. The only cue to the lectern is end rod sparks every two seconds (UNVERIFIED on Bedrock).
- The book itself is a native Floodgate form with icons, legacy colours, 24 character labels (`BedrockForms`, `BedrockText`): in good shape.
- The recipe unlock is chat only; a phone with chat hidden never learns the weapon exists. The Bedrock recipe book does list it (`docs/KINGDOM_SERVER.md`, UNVERIFIED live).
- "Right-click: bank this stack" (`CoinStacks.java:128`) and "Sneak and right-click: Stone Skin" (`azrak_pick_1.json` lore) assume a mouse.
- Shift-click to buy a stack (`ShopMenu.java:191`) has no touch gesture (UNVERIFIED).

### The first week, the long game, and what is missing

Week one in practice (from the Discord): iron farms, villager halls, walls, recruiting. None of it touches Charter, Currency or Fortune except the daily spin. The mods are invisible to the busiest hours of play. Missing: a reason to visit another kingdom's land (the gathering passives exist "to make kingdoms trade", `docs/RACES.md` section 3, and there is no stall, post or market); a reason to go into the wild beyond ores (dungeons are never announced; the only text is the reply to a bare `/dungeon`, `WildText.java:13`); any kingdom-wide view (who is online, where the Bank is, what the Lord asked for).

Long game: war needs two touching outposts and nobody has planted one; the grace ends 2026-11-06, so week two's conflict will be RP the Chronicle does not record. Justice (arrest with a lead, cells, execution, banishment) matches the RP rules word for word and nobody has been told. Renown is spent by a typed command (`RenownCommands.java:267`); commoners never see it. Fortune's 7, 30, 60 and 100 day milestones are the only long arc with a visible countdown. Weapon tier 3 is the gear ceiling; after that, nothing.

Return visits today: the daily spin and streak (strong); friends (strongest, not the mods); the 14 day Welcome Back spin ("The kingdom kept your seat warm.", `Reveal.java:68`, good); kingdom muster (in the spec, "not built yet", `FortuneCommands.java:252`); the Chronicle (dry dated lines, Bedrock rendering UNVERIFIED).

What is missing between the systems (fun, not correctness): coin has sources and almost no sinks players want, since weapon upgrades cost diamonds, gold blocks, netherite and amethyst and never coin; Valor buys three things; Fortune's jackpot symbol is a "Crown" drawn on a second valueless gold coin (`kingdoms:gold_coin`) while the iron coin is also a Crown; race traits make no demand on other races; a dungeon clear credits Valor and Renown but no kingdom sees "Bob cleared the Deep Quarry for Azrak" anywhere.

## 2. Screen by screen, with rewrites

### 2.1 Arrival and the newcomer path

**UX-1 (high). Told to open a book they do not have.**
`en_us.json:361` `land.drifter`. Before: "Join a kingdom or sign on as a mercenary first. Open your Charter book to choose." After: "Pick a kingdom first. Walk to the sparkling lectern in spawn and tap it."
`en_us.json:128` `pad.join_hint`. After: "Tap the sparkling lectern in spawn to pick a kingdom."
Add a one-time join title for kingdomless players: title "Kingdoms of Fantasy", subtitle "Pick your kingdom at the sparkling lectern". Titles reach Bedrock. The code has no join title (`CharterServer.java:141-157`).

**UX-2 (high). Four names for one thing.** organization (`en_us.json:5, 114`), faction (`:575` title, `:587` button), kingdom (`:985` body), nation (`:115, 657`). Players say kingdom. Minimum set: `page.no_org` and `teleport.no_org` to "You are in no kingdom."; `book.newcomer.title` to "No kingdom yet"; `book.button.join` "Join a faction" to "Pick a kingdom"; `book.join.title` to "Pick a kingdom"; `teleport.no_keep` to "Your kingdom has no keep yet."; `book.leader.intro` (`:934`) "Run your faction." to "Run your kingdom."; `book.war.intro` (`:657`) "A nation goes to war for one thing" to "A kingdom goes to war for one thing". The live `config/charter/en_us.json` override keeps old words for any key it holds; delete it when these land.

**UX-3 (high). Biggest kingdom first.** `NewcomerPages.java:176` sorts by member count descending. Sort smallest first or shuffle, and put the count on the button: "Azrak, the Dwarf (5)" fits the 24 character cap.

**UX-4 (high). The kingdom page says nothing about the race.** `NewcomerPages.java:213-221`, `en_us.json:610`. Before: "A lordship, 10 members, 1 keeps." / "Joining Faeloria makes you a member of the Fae race." / "The server's operators name each kingdom's ruler. Members can leave." After (three body lines): "Faeloria, the Fae. Queen Heather rules. 10 members." / "Fae fly on leaf wings, see in the dark and sense herbs. Weapon: the Briarthorn Staff." / "Joining makes you a Fae for good." Drop the keep count: every kingdom here has one. Build the "Your race" page under Where I stand with the same three lines (handoff item 6).

**UX-5 (high). Ask to join can strand a player for hours.** `en_us.json:633`. Either let a waiting applicant break, place and eat in the wild, or let the Lord set "open doors", or at least say who must answer: "You asked to join Azrak. A Lord of Azrak must say yes; until then you can look around spawn. Azrak's Lords: krqth." The Discord bot already pings Lords (`discord.notify_on`).

**UX-6 (medium). The only instant door is forever, in four lines.** `en_us.json:590-591`. Before: "A mercenary takes a race and wields its weapons, but belongs to no kingdom, holds no land and answers to no one. Mercenaries are on their own unless they group up, and anyone can be attacked anywhere." / "Choose with care: you cannot choose again. Joining a kingdom makes you of its race, unless its charter lets in every race." After: "A mercenary has a race and its weapon, no kingdom, no land, and can be attacked anywhere." / "Your race is for good. Joining a kingdom later makes you its race." The confirm (`:988`, "Become a Fae mercenary? You cannot choose again.") is right. The button could read "Become a merc" with "mercenary" once in the body; the players already say mercs.

**UX-7 (medium). Two travel buttons for one place.** `en_us.json:927-928` "Go to spawn" and "Go to hub". Hide "Go to spawn" when the hub keep contains world spawn (UNVERIFIED that it does), or rename to "Go to spawn town" and drop the hub button.

### 2.2 Home and Where I stand

**UX-8 (medium). Governance jargon on the home page.** `en_us.json:683-684` "A lordship. You are a member." Players never chose a lordship. After: "Faeloria. Queen Heather rules. You are a Commoner." (the top rank's name and the member's rank both exist, `Tags.java:112-115`).

**UX-9 (medium). Dead weight when governance is locked.** The glossary teaches Commune and Council (`:1046, 1048`); Where I stand says "Your ruler decides, and you can ask them for a seat or a power." (`:613`); the "Who decides" page says locked (`:760`). Hide the glossary entries and the button when `leadersChangeGovernance` is false. The breakaway button "I didn't vote for you" (`:776`) with "Break away from this faction." (`:777`) is exactly the owner's joke-plus-plain-meaning pattern; keep the pattern, show the button only with player founding on.

**UX-10 (low). Hover text that carries meaning.** `link.leave_hover` (`:28`), `votes.abstain_hover` (`:67`), `justice.link_execute_hover` (`:549` "Kill {player} once; they keep their items"), `discord.code.hover` (`:897`), every `book.tip.*`. Bedrock loses them (`BedrockText` keeps colour, bold, italic only). Put "They keep their items." in the execute confirm body.

### 2.3 Chat lines that tell players to type

**UX-11 (medium).** These reach Bedrock as chat and ask them to type:
- `:93` `notice.invited` "{org} invited you to join. Type /charter to answer." After: "{org} invited you. Open your Charter book or tap a lectern to answer."
- `:97` `notice.vote_opened` "... Type /charter vote to cast yours." After: "... Your Charter book shows it under Needs you."
- `:86` `vault.owed_left` "... collect the rest from /charter vault." After: "... the rest arrives when you have room."
- `:486` `cell.none` "... type /charter cell mark <name>." After: "Open the Leader's Desk, Justice, Mark a cell here."
- `:561` `chronicle.no_faction` "The realm's Chronicle is /charter chronicle realm." After: "The Realm Chronicle is under More in your Charter book."
- `:416` `borders.on` answers the typed command only; leave it.

### 2.4 The action bar

**UX-12 (high). Six systems share one line.** Crossings (`Crossings.java:78-83`), "Hold still" (`en_us.json:110`), `keep.closed` (`:420`), senses every 2 s ("Ore sense: Iron Ore, about 6 blocks away", `RaceTraitsRuntime.java:243`), the recharge bar (`AbilityBar.java:115`), "Leaf wings ●●○" (`DoubleJump.java:146`), "Wingbeats ●●○" (`FairyFlight.java:86`), Currency's look-at ("Bank: right-click to open. In the bank: 12 Sovereigns", `BankStation.java:366`). A Dwarf walking into Azrak with a pick near iron sees three lines fight; "Entering Azrak" is gone before it is read. Rule: senses and recharge yield for 3 s after any crossing, teleport or refusal. Sense line shorter: "Iron Ore, 6 blocks" (the dust trail already says which sense).

**UX-13 (low). Singular race.** `en_us.json:407` "Entering {kingdom}, land of the {race}" gives "land of the Dwarf". After: "Entering Azrak, Dwarf lands" (no plural needed).

### 2.5 Money words

**UX-14 (high). Marks in Charter, Sovereigns in Currency.** 24 Marks strings in `en_us.json`: `:103` `notice.paid` "{org} paid you {amount} Marks."; `:509` `term.fine` "a fine of {count} Marks"; `:949` `book.leader.withdraw` "Take Marks from the treasury" (28 characters, over the 24 cap); `:1021` `book.grant.power.spend` "Spend Marks"; `:1057` the glossary "The money of the realm. A treasury holds Marks..."; `:214` `notice.outpost_lost` "... deposit of {deposit} Marks"; plus `vault.withdrawn`, `vault.marks`, `book.limit`, `book.leader.take`, `book.leader.withdraw_amount`, `book.justice.fine`, `justice.link_fine`, `notice.fined`, `motion.treasury.*_marks`, `motion.justice.fine`, `book.breakaway.cost_nexus`. A 2 Sovereign fine shows today as "a fine of 128 Marks". Rewrite all to Sovereigns and divide by 64 at the display layer. `book.leader.withdraw` to "Take Sovereigns". Glossary: "The money of the realm. Gold struck at a Mint. 1 Sovereign = 8 Crowns = 64 Marks."

**UX-15 (medium). The welcome contradicts the Mint.** `EconomySettings.java:134` "Coin: copper Marks, iron Crowns, gold Sovereigns." then `MintListener.java:94` "The Mint strikes gold only. Copper and iron are not money here." After (three lines): "Money here is the Gold Sovereign, struck from gold at a Mint." / "Valor is earned in dungeons and cannot be traded." / "Banks and shops are in every keep and in spawn." Drop "/bank all" and the dead "[ Open the shop ]"; the Bank block has a "Bank all your coin" button (`BankStation.java:294`).

**UX-16 (medium). Crown means two things.** Currency: Iron Crown. Fortune: "Three Crowns" is the jackpot on `kingdoms:gold_coin` named "Gold Coin" (`fortune/core/.../Settings.java:69`, `foundry/.../gold_coin.json`, "Coin of the realm", no value). Rename the symbol "Sun" (the Sovereign icon is a sun) and draw it with the real Sovereign item, so the jackpot is "Three Suns" and looks like the coin players hold. Retire `kingdoms:copper_coin`, `iron_coin`, `gold_coin`.

### 2.6 The Bank, the Mint and the shop

**UX-17 (low). Good screens, mouse words.** `BankStation.java:285-333`: "Your money" with "Show me the money!" (approved joke, plain meaning beside it), "Bank all your coin", "Take out 1 Sovereign" / 8 / 64, Mint lore "Hold gold ingots and right-click it." / "Sneak and right-click: strike them all.", "Open the shop". After: "Hold gold ingots and tap it." / "Sneak and tap: strike them all." `CoinStacks.java:128` "Right-click: bank this stack" to "Tap a Bank with it to bank the stack" (the lore does not say on what today).

**UX-18 (medium). The tax is announced after the strike.** `MintListener.java:104` "One coin in five goes to your kingdom". Put it in the look-at line before the first strike: "Mint: tap with gold ingots. 10 Sovereigns an ingot, 2 go to Azrak."

**UX-19 (medium). Shift-click.** `ShopMenu.java:190-191` "Click: buy 1" / "Shift-click: buy all you can afford". Bedrock touch has no shift-click (UNVERIFIED). A "Buy a stack" button beside the entry, or at least "Tap: buy 1. Shift-tap (Java): buy all you can afford."

**UX-20 (low). The purchase confirm is a Java dialog.** `ShopDialogs.java:63-86`: "16 x Iron Ingot" / "Costs 2 Sovereigns" / "Coin: 12 -> 10", Buy, Cancel. Geyser's rendering is UNVERIFIED; Currency has no Floodgate form path the way Charter does. Check once; two buttons is probably fine.

**UX-21 (low). "SERVER WEALTH" on every login.** `LoginSnapshot.java:121` with a coin emoji heading (outside the vanilla glyph set; may draw as a box on Java too, UNVERIFIED). Heading "Gold Sovereigns"; show the board once a day per player. A leaderboard on every login is a flex to the rich and a nag to everyone else.

### 2.7 Fortune

**UX-22 (low). The best screen in the suite.** Lever "Pull the lever" with "\"Do you feel lucky?\" Every pull pays something." (`MachineGui.java:380-381`), Close with "\"I'll be back.\" Closing keeps your reward." (`:361`), the reveal as title and subtitle ("Three Diamonds" / "+24 Valor and a diamond", `Reveal.java:49-50`). Keep it.

**UX-23 (medium). Over the cap, and a broadcast that reads wrong.** `MachineGui.java:355` "Annals: this week's biggest wins" (32) to "This week's big wins". `Reveal.java:85-86` "Bob has spun Fantasy's Fortune on 7 days: Bob the Steadfast." to "Bob the Steadfast: 7 days at Fantasy's Fortune."

**UX-24 (medium). First Week tips are in the spec, not the jar.** `fortune/SPEC.md` 5.3 lists seven one-line tips; none exists in `fortune/` (grep). The gifts exist (`Settings.java:119-121`), the teaching does not. Shown as the reveal subtitle they reach Bedrock; the cheapest onboarding the server could have.

**UX-25 (low). The first spin refusal.** `FortuneService.java:211`. Skip the join prompt until the five minutes are up, or say so: "Your reels are loaded. They open after 5 minutes on the server."

### 2.8 Weapons and race items

**UX-26 (medium). Lore walls.** `aurelia_oathblade_1.json` has 7 lore lines of about 100 characters. On a phone the inventory tooltip wraps narrow. Before: "Right click: Mikiri, a 0.75s parry. The next melee hit or arrow is stopped." / "  You counter for 6 damage, Weakness and Slowness. 12s recharge, half back on a parry." After: "Right click: Mikiri (parry). Block the next hit, counter for 6. 12s." The staff's "(on Bedrock: tap once and keep it in hand)" is exactly right; the Oathblade's Quickdraw ("Sneak, hold right click and let go") needs the same note. One template edit in `gen_weapons.py`. "Mikiri", "Quickdraw", "Glamour", "Mischief" with the plain word beside them are good names in the owner's style.

**UX-27 (low). One weapon named after its kingdom.** Runed War Pick, Oathblade, Tide Trident, Briarthorn Staff, Everdelle Wand. "Glimmer Wand" would match the Fairy sense name; or accept it.

**UX-28 (low). Developer lore on retired items.** `antler_circlet.json`, `fairy_wings.json`, `forgeman_pauldron.json`, `kingdom_cape.json`, `tidal_gills.json`: "A retired trinket, kept for something new later." If any can still be found: "Kept for a story not yet told."

**UX-29 (medium). Only the Fae are taught to move.** `JumpTip.java:12-18`. One action bar line per race, once per login until used: "Human: sprint and tap sneak to slide." / "Fairy: sneak and jump to take off. Jump again to beat your wings." / "Merfolk: sprint swim and jump to dart." / "Dwarf: you walk faster under a roof of stone."

### 2.9 Wild dungeons

**UX-30 (high). Nothing says they exist.** The lodestone is a bedrock block in a stone brick ruin with a cobweb (`WildLodestones.java:114, 178-189`); Java sees a jigsaw lookalike (`WildMarkerDisplays.java`), Bedrock sees bedrock (same file, `isBedrock` skip). Options that reach Bedrock: a sign post beside it, a real lodestone block (every text calls it a lodestone), an end rod spark ring like the terminals, a text display, and one welcome or Chronicle line: "Ruined stone and a dark block in the wild mark a dungeon. Tap it." Live check Geyser's rendering of the two-button entry dialog first (`WildDialogs.java` through `DialogKit`); if it does not render, Bedrock players cannot enter at all.

**UX-31 (low). The model for the rest of the suite.** `WildText.java`: "Copper. Iron. Coal." / "Ready." / "Enter" / "Not now" / "Leave?" / "You keep what you carry." / "The ceiling is giving way. Leave now." Short, concrete, a verb on the button. Dungeon names (The Deep Face, Deep Quarry, The Gilded Seam, The Dive) are good.

### 2.10 The Chronicle

**UX-32 (medium). A clerk, where the Discord wants a town crier.** `DefaultTemplates.java:21-122`: "On {when}, {player} joined {org}{from? after leaving %}." / "On {when}, {attacker} took {outpost}, banner and land." Correct, dated, dull. `config/charter/templates.json` is live config, so this needs no build. Three lines in the crier voice:
- Joined with a `from`: "Hear ye: {player} has left {from} for {org}. The Betrayal, some will call it."
- OutpostCaptured: "{attacker} has taken {outpost} from {defender}, banner and all. Hear ye."
- Muted: "{lord} of {org} bade {player} hold their tongue for {duration}." (UNVERIFIED whether `{durationMillis}` is formatted or printed raw; check the Describe layer.)
Events with no line yet that players already tell stories about: first weapon tier 3 in a kingdom, first wild dungeon cleared, a kingdom reaching 10 members, a Fortune jackpot. No swearing, no real-life details, no sex or affairs; the Discord study's section 11 says why.

### 2.11 Tags and ranks

**UX-33 (low). Tags show a rank only after a Lord edits ranks.** Default "[Faeloria] Heather". Every kingdom on the Discord already has King, Queen or Emperor. Seed each kingdom's top rank with the title the ops know at creation so "[Azrak] Emperor krqth" shows on day one. Bedrock tag rendering UNVERIFIED.

### 2.12 One name per thing

| Thing | Words in the text | Players say | Use |
|---|---|---|---|
| A kingdom | kingdom, faction, organization, nation | kingdom | kingdom |
| The home land | keep, core, plot, keep core | ruins, base, /keep | keep (the Chronicle may say "the ruins of Azrak" for colour) |
| The centre block | Nexus, lodestone, core | the big block | Nexus on buttons; "the Nexus (a lodestone)" once in the glossary |
| Money | Marks, Sovereigns, Crowns, coin | nobody yet | Sovereigns; Crowns and Marks only on the coins themselves |
| Fortune jackpot symbol | Crown | | Sun |
| The ruler | Lord, ruler, leader, King, Queen, Emperor | King, Queen, Emperor | the kingdom's own top rank name; "Lord" in the Desk and operator text |
| A fighting base | outpost, camp (`name.camp_at`, `map.kind.camp`) | nobody yet | camp (the code already half says it; "outpost" is the owner's word) |
| A player with no kingdom | mercenary, drifter (`land.drifter`), newcomer | mercs | mercenary; never show "drifter" |
| The menu | the Charter book, Charter, the menu, terminal, lectern | the book | "your Charter book" and "the lectern" |
| Spawn | spawn, the hub, spawn town, spawn area, the admin kingdom | spawn | spawn |
| A dungeon | dungeon, wild dungeon, lodestone, the place | nobody yet | a Dig, opened by the Digstone (see 2.14) |

### 2.13 Jokes: where they are and where they fit

Present and good, never on an error: "Show me the money!" (Bank), "I'll be back." and "Do you feel lucky?" (Fortune), "I didn't vote for you" (break away), "Go to the Nexus (I'll be back)" (operator menu), raider band names "Vex and the City", "The Illager Illuminati", "Crossbow Anonymous" (`npc.name.*`), "Mikiri" (Sekiro). Places that could take one with the plain meaning kept: Fortune stake tiers (`Settings.java:42-44`) "High roller: \"Shut up and take my money\" (20 Sovereigns, +100%)"; the "Come back tomorrow" lever (`MachineGui.java:383`) "Come back tomorrow. \"Tomorrow is another day.\"". Not on the teleport timer, not on the mercenary confirm (a one-way door stays plain).

### 2.14 Names the server could coin

| Thing | Proposal | Why |
|---|---|---|
| Gold Sovereign | Suns ("ten Suns an ingot") | the icon is a sun; one syllable; the shop keeps "Sovereigns" on the label |
| Iron Crown, Copper Mark | Crowns, coppers | already what they are |
| A wild dungeon | a Dig | every wild dungeon is mining themed; "ruins" is taken by the keeps |
| Its entrance block | the Digstone | says what it is and what it opens; "wild lodestone" collides with the keep's Nexus lodestone |
| An outpost | a camp | half the code's keys already say camp |
| Azurius | the fishies (light lines), Azurius (formal) | Buck coined it himself |
| The admin kingdom | the Muddle | the tag already says [Moss] |
| The daily spin | the Fortune ("done your Fortune yet?") | short |
| Defection | The Betrayal | the Discord's own headline |

## 3. Bedrock versus Java parity

| Feature | Java | Bedrock (Geyser and Floodgate) | Severity | Fix |
|---|---|---|---|---|
| First direction on join | Nothing from Charter; Currency and Fortune chat | Same, chat fades; Fortune modal form | high | Charter join title (UX-1) |
| "Open your Charter book" lines | Chat and action bar | Same, and no book | high | Reword to the lectern (UX-1) |
| Charter book menus | Vanilla dialogs | Native forms with icons, 24 char labels (icon paths UNVERIFIED) | low | Check icons once on a real client |
| Hover tips in the book | Form and power tips | Lost | low | Consequences in bodies (UX-10) |
| Clickable chat | Works | Dead text | medium | No click links in welcomes or notices (UX-11, UX-15) |
| Written book Chronicle | A book screen | UNVERIFIED; the Bedrock plan says build paged screens | medium | Phase 6 of the Bedrock plan |
| Currency purchase confirm, shop editor | Vanilla dialogs | Geyser translation, UNVERIFIED | low | Check once |
| Pocket Dungeons entry dialog | Vanilla dialog | Geyser translation, UNVERIFIED; if it fails, no entry at all | high until checked | Check first |
| Wild lodestone marker | Jigsaw lookalike | Plain bedrock | high | Sign, sparks or a real lodestone (UX-30) |
| Fortune machine | 54 slot animated chest, glint on the payline | Same chest at 3 ticks a frame; glint UNVERIFIED; `bedrockMode: "form"` fallback shows the result in words | low | Watch one phone spin |
| Fortune reveal | Title and subtitle | Same | none | |
| Weapon abilities | Right click, hold and release, sneak and right click | Staff: tap casts after 1 s. Oathblade Quickdraw hold and release UNVERIFIED | medium | Same tap path; say so in the lore |
| Ability recharge bar | Action bar | Same | none | |
| Senses | Dust trail and action bar | Same, dust colour UNVERIFIED | low | Shorter line (UX-12) |
| Fae wing dash, Human slide, Merfolk dash | Work | Work; Fairy wingbeats need jump while gliding, UNVERIFIED; touch sneak is a toggle | medium | Check once |
| Race looks | Polymer resource pack | Skin bones, prototype; skipped silently on persona skins | medium | Tell the player; a tag colour fallback |
| Name tags "[Faeloria] Queen Heather" | Team prefix | Geyser builds from the prefix, colour rounded, UNVERIFIED | low | Check once |
| Shop bulk buy | Shift-click | No shift gesture (UNVERIFIED) | medium | A "buy a stack" button (UX-19) |
| Banking a coin stack | Right click | Tap or long press; lore says right-click | low | Reword (UX-17) |
| `/keep`, `/spin`, `/bank`, `/shop` | Typed | Keyboard pain; pads, lecterns, the Bank block and Fortune machines cover them if present | low | A machine and a Bank in spawn and every keep (`fortune admin machine keeps` is "not built yet") |
| Item lore | Hover anywhere | Inventory tooltip only, wraps narrow | medium | Shorter lore (UX-26) |
| Weapon models | 3D per tier | Flat icon per tier; needs the pack accepted | low | fine |
| Bolt visuals | Item display riding the bolt plus trail | Trail only | low | fine |
| Fairy glide | Glide with nothing in the chest slot | A stand-in elytra appears in the slot (display only); cannot glide with a chestplate | medium | Say so once |
| `/enchants` | Clickable names and arrows | Plain text with the command to type | none | |
| Emoji in "SERVER WEALTH" | May be a box | UNVERIFIED | low | Words (UX-21) |

## 4. The ten text changes with the most value for the least work

All strings. 1 to 8 need no jar if the live text override is replaced and `config/chartercurrency` and `config/charter/templates.json` are edited; 9 and 10 are content JSON.

1. `land.drifter` (`:361`) and `pad.join_hint` (`:128`): point at the sparkling lectern (UX-1).
2. Every player visible `faction`, `organization`, `nation`: kingdom (UX-2).
3. The kingdom page: drop "{keeps} keeps", add the ruler and one trait line (UX-4).
4. Every Marks string: Sovereigns, divided by 64 at display (UX-14).
5. The Currency welcome: three lines, no command, no click link (UX-15).
6. `templates.json`: three crier lines (UX-32).
7. Fortune: "This week's big wins"; the milestone broadcast reordered (UX-23).
8. The Mint look-at line says the tax before the strike (UX-18).
9. The jackpot symbol "Sun" on the real Sovereign item (UX-16).
10. Weapon lore: one ability per line, half the words, the Bedrock tap note on every hold ability; one template edit in `gen_weapons.py` (UX-26).

## 5. What this review could not verify

Every Bedrock rendering claim marked UNVERIFIED: form icons, Geyser's translation of Currency and Pocket Dungeons dialogs, the written book Chronicle, container glint, dust colours, name tag prefixes, emoji glyphs, touch shift-click. The order Currency's welcome and Fortune's prompt arrive in. The exact wording of the first spin refusal. Whether `{durationMillis}` is formatted in Chronicle lines. What `/shop` sells on the live server. Whether world spawn and the hub Nexus are one spot. The live checks in `TEST_GAPS.md` section 2 settle all of these.
