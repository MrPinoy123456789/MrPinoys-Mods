# Playtest research agenda

What we want to learn from playtests, most important first. The `/playtest`
interviewer reads this before every session, covers the open items that the
session gives it a way to ask about, and updates each item afterwards. The owner
can add, reorder or retire items at any time.

Status: `open` (no evidence yet), `partial` (some evidence, keep asking),
`answered` (enough to act on), `retired` (no longer relevant). Evidence cites
the playtest file (`docs/playtests/<date>-<n>.md`).

## Format

Each item: an id, the question, **why we care**, **what would change our
mind** (the answer that would make us change the design), status, and evidence.

---

## A1. Do players read and use the omen bar?
- **Why:** omen is the loop's pressure (chests and keystone level at a bank). If
  it is not read, banking decisions are blind and the band feels arbitrary.
- **Would change our mind:** players cannot say what their omen was or why it
  rose, or say the bar is noise. Then the bar or the rise cues need rework.
- **Status:** partial
- **Note 2026-10-10:** the pressure is lives now (five per trip, a death or a side door spends one) and the trip sidebar shows floor, lives, spawners, haul and compass. Ask whether they read lives and the sidebar; omen is only the internal name.
- **Evidence:** 2026-09-26-1.md: bar title cluttered; the spawner count is what they track. 2026-09-27-1.md: no model of what raises omen; the band name "calm" is opaque; calm on all 10 floors, so omen never bit (medium).
- **Changed 2026-09-27:** the bar now leads with the spawner gate (`Spawners 2/3 | Omen 1/4, 3 chests`), a floor clear shows a big "Floor 2 of 3 cleared" title, and the dwell cue says "in an unsolved room". Retest: can they say what raised the omen?
- **2026-09-27-3.md:** retest failed: omen bit for the first time (sensor rises, 2 chests on F1 and F2) and the player saw the bar fill but not why; guessed "I took a long time". Still reads as noise to a new player; wants spawners as a countdown, omen hidden at 0, a flavour line on each rise. Then rejected omen as a reward penalty: wants it to raise danger, like a wanted level (high).
- **2026-09-29-1.md:** omen stayed 0 across all 5 cleared floors and the interval; one death rescue did not visibly change the band (still calm at the bank). The danger model had nothing to show this session (low).
- **2026-09-29-2.md:** rescues now tick floor omen to 1 (seen twice); the player never mentioned the bar unprompted and banks still happen at band calm. Omen is changing state, but there is still no sign he reads it (low).
- **2026-09-29-3.md:** first observed run failure at max omen: an ominous floor 2 ended `run_failed` at floor/interval omen 4 after an arrow death. The danger model finally bit. He never commented on the fail, so whether the bar telegraphed it is still unknown (medium).
- **2026-09-30-2.md:** the player asked whether deaths raise omen, learned they do not, then argued deaths should add omen and proposed a wider scale: 10 baseline plus 5 per extra party member. He also proposed consumable totems reducing omen by 1. This is strong counterplay design input, but still not evidence that the bar itself is being read (high).
- **2026-10-01-1.md:** omen bit again on a level 9 basalt_foundry run: three rescues, then `run_failed` at floor and interval omen 4. The failure now works in live play, but there is still no spontaneous evidence that the player watches the bar or understands the countdown before it ends (medium).
- **2026-10-01-2.md:** the sculk sensor room finally connected omen to a cause in his head: he noticed the sensor text (though it flashed too fast to read, PD-100), solved the room by hoeing the sensors, and later guessed "is it my bad omen?" about fast enemies. He banked immediately after clearing a floor at max omen 4 (band 2), which may be the first omen-influenced exit. Omen is starting to be something he reasons about (medium).
- **2026-10-02-1.md:** omen is now clearly read: player asked about Silence affix duration, asked for an omen-gain sound, and proposed Silence should give omen on consumable use rather than blocking it. The sculk-sensor line and spider-speed confusion show the bar is being used as an explanation, which is progress (high).
- **2026-10-06-1.md:** he watches the bar closely enough to spot it lying: "My omen was 4/4 but the bar wasn't full, it didn't seem to progress properly with deaths and eating" (PD-158; journal confirms interval omen hit 4). Reads it, distrusts it now (high).

## A2. What makes a player go home, and when?
- **Why:** bank-anywhere replaced the forced safe room. The depth bonus and the
  omen head start past floor 3 are meant to make "one more floor?" a real
  gamble.
- **Would change our mind:** players always go home at the same point for the
  same reason (no real decision), or never feel pulled either way. Then the
  depth bonus or head start numbers need tuning.
- **Status:** partial
- **Note 2026-10-10:** the reason to go home is the haul, which is at risk until banked (half is lost to a failed dungeon), and the pull is "one more floor with the haul in hand". Bands and the one band worse checkpoint rule are gone; reward chests (a base count plus the zone depth bonus) still roll into the reward barrel on the way home. Leaving a floor in progress now fails the dungeon for the leaver; leaving between floors cashes out in full.
- **2026-10-04-1.md:** two banks (after 3 floors each), the second when the pack was at 0 free slots. The gain was not understood: "Did my keystone upgrade ... like it was supposed to?" then "I thought I was going to get more levels since I did multiple echo shard / more challenging floors" (medium). Full pack and the keystone result, not the omen, were the pull home.
- **2026-10-05-1.md:** he quit the Mineshaft on its final floor, but said "I had to go (unrelated to the test or game)". No finish observed, so the shard/vault/page pull (L25) is still unmeasured (low).
- **2026-10-06-1.md:** first full dungeon finish observed (Mineshaft, 3 floors); he stayed to the end unprompted and the diary page landed. The chart-scrap leftovers warning was understood ("brining X Charts and losing Y" model holds). New wrinkle: a full resource clear paid 0 charts, and his verdict is resource floors should pay like normal floors (medium).
- **Evidence:** 2026-09-27-1.md: "I don't know what home offers so I never want to go"; went home for a full inventory and chest storage, always after floor 2, never reached the floor 3 head start (high on the current state: the payoff is invisible, not mistuned).
- **Changed 2026-09-27:** the go-home screen lists what home pays (reward chests, key progress, kit refill) and the floor-end line names what each lever pays. Retest: does the go-home point move, and is the reason ever the omen or depth?
- **2026-09-27-3.md:** moved from floor 2 to floor 3, at the green "TIME TO GO HOME" screen, but the player asked why it was time and what descending would do: a traffic light, not a weighed gamble. Descended again one minute after going home (medium).
- **2026-09-29-1.md:** the floor 2 bank was an accidental GO HOME pull, not a decision (see A5); the floor 3 bank followed a rescue at calm band. No weighed go-home moment observed (medium).
- **2026-09-29-2.md:** two voluntary banks, both after floor 3 via the HOME lever (key 4 then 5). Floor 3 is becoming his routine exit; still never weighs the door 2+ gamble because the gate hides it (low).
- **2026-09-30-2.md:** two more voluntary 3-floor banks (level 6 and level 7). Floor 3 remains the routine exit, though a later preview offered and accepted a paid step 2 basalt_foundry run (medium).
- **2026-10-01-2.md:** three voluntary home-lever banks: after floor 4 (band 0), after floor 3 (band 0), and after one overclocked floor cleared at max omen 4 (band 2). The last one may be the first exit driven by the omen rather than routine (medium).
- **2026-10-02-1.md:** player said outright "I really see no need to continue descending after I've completed 3 floors. The new 'Go Home' UI/UX should illustrate this." The desired stop point is now 3 floors, and the screen should show it with diagrams, a progress bar, and rewards at nodes rather than paragraphs (high).
- **2026-10-02-2.md:** two more voluntary home-lever banks, both after floor 3 (band 1, then band 0), no comment on the screen itself. The Go Home redesign is still untested in play (low).
- **2026-10-08-1.md:** the pull home is now the finish itself: three full dungeon finishes in one session (Infestation, Spawner Dungeon, Copper Works), all unprompted, plus two mid-trip lever banks. The friction moved to the end screens: "Haul 0 scrap" after the finish auto-bank (PD-179) and the unreadable "Home 4 chests" line (PD-180) (high).

## A3. Does the strict resource economy feel tense or tedious?
- **Why:** scarcity of blocks and durability is a core strength (owner). It
  should create decisions, not chores.
- **Would change our mind:** players describe running dry as unfair, or grind
  outside the loop to avoid it. Compare with blocks and durability spent per
  floor and the kit top-ups in the digest.
- **Status:** partial
- **2026-10-04-1.md:** food never ran short (13 to 63); the pack hit 0 free slots at the second door. He discards gear at almost no durability ("scrap for nothing"), surplus string and sticks. Clutter is the cost now, not scarcity (medium).
- **2026-10-05-1.md:** pack hit 0 free slots again by floor 3. His D20 verdict: keep resource nodes for ore randomization, but restore interior breakability "like in the previous iteration". He used run storage unprompted (arrows, shield, string). Mineshaft ore rooms are too generous; 4 raw iron is his medium-high anchor (high).
- **Evidence:** 2026-09-27-1.md: wood is the bottleneck (about 4% oak log per tier 1 chest); ingots and diamonds unused for lack of sticks; early loot reads as useless; mob-dropped tools keep full durability (PD-69) (high).
- **Changed 2026-09-27:** wood early, fewer bones and emeralds, trims later game, more and shorter-lived weapons and armour (owner decisions 2026-09-27 in `AUDIT_2026-09.md`). Retest over three runs.
- **2026-09-27-3.md:** no wood complaint this session; proposed the Grove as a small capped wood source (its logs are all unbreakable wall). Only one interval played, so the three-run retest is still open (low).
- **2026-09-29-1.md:** the complaint flipped to surplus: "accumulating too much gear and vault keys", wants a scrap or redemption outlet. Two full intervals, no scarcity friction mentioned (medium).
- **Changed 2026-09-29:** the salvage bench (a grindstone station, `docs/reference/SALVAGE_PROPOSAL.md`): tagged gear pays 1 emerald per tier, vault keys 1 (ominous 3), mob gear XP only. Retest: does the surplus complaint go away, and do `salvage` journal events show it used every interval without emeralds piling up at the gamble?
- **2026-09-30-2.md:** salvage worked once explained (3 mob gear for XP, 5 keys plus 1 ominous key for 8 emeralds), but the player had to ask whether it existed and how to use it. Emeralds still accumulated with no sink; merchant rooms, two-way trading, workstation durability/refills, and utility blocks like cobblestone and sand all came up as economy pressure valves (high).
- **2026-10-01-1.md:** the bench paid once for 1 XP, but surplus pressure shifted to themed materials: "I have very little use for this amount of magma cream and blaze powder." The economy still needs sinks for category-specific drops, not just keys and gear (high).
- **2026-10-01-2.md:** economy produced the densest feedback of any session: lapis essentially never drops (supply_tier_2 only, weight 2) while enchanting is live, totems are dead loot ("need an omen reducing mechanic"), the enchanting table should be crafted not looted, netherite came too early ("tier should be gated"), mending gear is common enough to break the durability economy, arrows are usefully scarce, and he wants sand for brewing. Surplus and scarcity are both mispriced in places (high).
- **Owner direction 2026-10-01:** Mending becomes an expensive operation that "locks in" a piece of gear (probably a paid station action, emerald sink), not a common random enchant on loot.
- **Changed 2026-10-02 (fix batch, `docs/plan-2026-10-02-1.md`):** stack caps removed; tier 3 spawner gear untrimmed and armour drops floored at 0.08; echo shards per interval, floor chance and Ordeal chance; ender chest loot became obsidian; a Guard's Bag; dead-end fountains; Silence charges omen instead of blocking. Evidence to collect: `echo_shards` journal events (L13), armour pieces per floor, whether the stack merge complaint is gone. Still open: sinks for string, bones, blaze powder, magma cream and end stone (ROADMAP backlog).
- **2026-10-02-1.md:** economy feedback exploded into concrete asks: remove custom stack caps; too much trimmed armour and armour in general; spawners mixing mob types; bones/blaze/string/endstone/ender chests/ominous banners all dead loot without sinks; need more echo shards (1 per interval + 50% chance per floor, ordeals too); staging-room storage; dead-end fountain boon; guard kit; set-bonus trims. The scarcity/surplus balance is now the dominant design problem (high).
- **2026-10-02-2.md:** the shard rule is settled: one guaranteed shard per third floor plus random Ordeal rewards, no plain floor chance (he disliked a `floor` shard). Stations should be crafted, not handed out by Lemon; reroll moves to the enchanting table (pick a property); Mending is sold as a book by the librarian, not "locked in"; tridents cannot be salvaged; piglin trading should draw from a dungeon loot table; mending should cost more XP or repair slower (high).
- **2026-10-07-1.md:** the scrap model itself is the friction now: six Lemon asks in ten minutes reconstructing pool vs chart vs level, ending in his verdict that overleveled players can never earn scrap but must spend it on branches (he wants it thought through). Owner follow-up after the session: functionally sound but very confusing, and he expects it to stay confusing even with UI/UX fixes. Emeralds visibly flood (+8 per finish, emerald pay on underleveled floors; 44 in the pack, 251 idle at home). Scarcity is solved; currency legibility is the open problem (high).
- **2026-10-08-1.md:** merchant sinks are used unprompted now: 7 shop_sales on one floor plus junk sold to the home Librarian (rotten flesh, string for emeralds). Owner's new economy proposal: each level requires more scrap than the previous and higher floors pay more (PD-182) (high).

## A4. Does the kit top-up feel fair and understood?
- **Why:** the top-up by band ties the economy to omen. It only works if players
  notice it and connect it to how the stretch went.
- **Would change our mind:** players do not notice it, or think it is random.
- **Status:** retired 2026-10-05 (dungeon structure W5; the kit is granted once and nothing refills it, so there is no top-up left to be fair or understood). The evidence below stays as the record.
- **2026-10-04-1.md:** the kit chest refilled on both banks and replaced leftovers. "The kit is good for what it is" but he is thinking of dropping refills: 4 or 5 starting kits, nothing topped up (high; an owner decision, not a player confusion).
- **Evidence:** 2026-09-27-1.md: not noticed until asked right after a go-home; the one restock seen was a spyglass the player called useless, delivered invisibly to the kept dungeon inventory (medium).
- **Changed 2026-09-27:** the restock line says where the items went and flashes on the action bar. Retest: noticed unprompted?
- **2026-09-27-3.md:** noticed unprompted, but as absent: "I don't think my bag refilled" after a top-up of 6 arrows and a spyglass. "Kit refilled" on the screen implies a full refill (medium).
- **2026-09-30-2.md:** one bank granted only a spyglass and another granted nothing; neither drew a comment. Still no evidence the player connects top-ups to band (low).

## A5. Is the HOME lever and staging room readable without help?
- **Why:** the staging room carries three doors, a commit lever, the HOME lever
  and screens. A new player must understand it unaided.
- **Would change our mind:** hesitation, wrong lever pulled, or "I did not know
  I could go home".
- **Status:** partial
- **Note 2026-10-10:** the first staging room is now the Astrolabe Room (act astrolabe, a row of dungeon doors with a sign, doormat and bulb each, a wide window onto the picked dungeon and a glinting DESCEND lever); later floors keep three doors, a commit lever and the HOME lever. Retest the Astrolabe Room with a fresh player (L55).
- **Evidence:** 2026-09-27-1.md: levers read correctly unaided, wants HOME as a verb; the home screen and "banks what you carry" were fully opaque; levers swap sides between floors (PD-70) (high).
- **Changed 2026-09-27:** the lever sign reads GO HOME, the levers no longer swap sides (PD-70), and the screen is in plain words. Retest with a fresh player if one is available.
- **2026-09-27-3.md:** owner role-played a first login: levers used without hesitation; nothing on join says to type `/dungeon`; the green title prompts "why now?" (medium).
- **2026-09-29-1.md:** regression evidence: the floor 2 bank was an accidental GO HOME pull while reaching for door select ("I was trying to change my door selection"). The commit/home controls are still confusable after the PD-70 fix (high).
- **2026-09-29-2.md:** PD-84 verified in game: two deliberate `home_lever` banks with the confirm dialog in the pull path, no misclick all session. New readability issue surfaced instead: the preview glass sits behind the doors (PD-85) (medium).
- **2026-10-02-1.md:** staging room feedback: the ender chest should be a staging-room run storage; HOME screen needs diagrams not text, a progress bar, and no "3 reward chests" pitch; "Going home pays" sign is too wordy. The staging room is now the focus of UI confusion, not the levers (high).
- **2026-10-02-2.md:** selecting a door replaces its light with the preview glass on the two low doors, and the third door has no glass (PD-116). His fix: the selected door vanishes while its light stays on, restored when the selection changes. Discovery gap: he did not know the station picker held the Lectern (medium).
- **2026-10-08-1.md:** lever mechanics are fine now (three clean home-lever banks), wording is the remaining gap: the end-screen "Home 4 chests" line had to be explained and "Haul 0 scrap" after a finish read as a bug (PD-179, PD-180) (medium).

## A6. Do door choices feel meaningful?
- **Why:** each floor now banks its own door step (averaged). Door 1 is free,
  door 2 is ominous, door 3 costs fuel.
- **Would change our mind:** players always pick the same door, or cannot say
  why they picked one.
- **Status:** partial
- **Note 2026-10-10:** fuel is gone and the front offer is no longer three random doors: the player picks the act and the dungeon in the Astrolabe Room (PD-181). Later doors deal a step (+1 to +3) and side doors cost lives. Ask the choice in those terms.
- **2026-10-04-1.md:** "I like that I have to gamble my success on the run for more reward" (positive), but themes jump each floor and "the floors in a set seem kind of disconnected". Proposes a dungeon with one entry floor, then an acyclic graph of choice-gated floors (high).
- **2026-10-05-1.md:** the structure landed: "yes, it felt good" for one-place feel, and he named The Main Drift unprompted. But inside a resource dungeon every door read +0/+0/L2 and spare doors are identical copies, so the choice "feels arbitrary" (PD-151, PD-153) (high).
- **2026-10-06-1.md:** new unified board seen; he read it fine (asked what "Same as door 1" meant) then ruled: no identical doors, reroll affixes until they differ (PD-153 superseded). Cost now reads per door (`Cost: N echo shards` / `free`), scrap line as "+N chart scrap" style; layout pass (floor count on its own line, smaller text) still pending (high).
- **2026-10-07-1.md:** the two-sheet board landed cleanly: he read both sheets correctly on first sight and rated the layout "okay". Remaining friction: where a side branch's scrap cost shows ("do any of these choices cost scrap?") and an untrue ore hint (PD-161). Door choice itself now reads as a real pick between affix flavours (high).
- **Evidence:** 2026-09-27-3.md: the free door on all four descents, committed 1 to 8 seconds after the preview; reason not asked yet (low). 2026-09-29-1.md: the free door again, committed 18 s after preview on a check-in run that was quit at once; reason still unasked (very low).
- **2026-09-29-1.md:** asked directly: "I would take deeper doors, but my keystone level is too low I think so they're unavailable." Door 3 needs level 15 while he sat at keystone 1-3; the free door is forced, not chosen. His suggestions: hide unavailable doors so a newly unlocked door is itself the signal; put door 2 at level 7 or 8 (high).
- **2026-09-29-2.md:** gating verified in game: at keystone ~4-5 the selector showed exactly one door ("it correctly showed 1"). He reached key 5 by session end, still under the door 2 gate of 7; no real door choice observed yet (medium).
- **2026-09-30-1.md:** at keystone 5 only door 1 was available; it rolled PD-78 `blaze_cellar` and failed six commits. "There is no other doors due to my keystone level." Gating now amplifies a bad roll into a session blocker (high).
- **2026-09-30-2.md:** first observed non-free choice after progression caught up: a step 2, level 9 basalt_foundry preview with Feral and Ominous was committed for 3 fuel over the level 8 frostworks option. The paid door finally produced a real pick, but only after many levels of forced door 1 (medium).
- **2026-10-01-1.md:** the player again chose the paid step 2 basalt_foundry offer over level 8 frostworks. The choice is now repeatable, and the consequence is real: the harder run ended at max omen without a floor clear (medium).
- **2026-10-01-2.md:** door 2 unlock verified in words, not just logs: "Yes, I've been getting second floors and they work." At keystone 9-10 the selector offers real options each descent (high).
- **2026-10-02-1.md:** echo-shard subtraction on Greater doors verified "Good"; player proposed a clear shard economy (1 per interval, 50% per floor, ordeals chance). Doors are now meaningful, but the resource pacing needs tuning (medium).
- **2026-10-02-2.md:** doors ran at keystone 12 to 15 with step 2 and step 3 offers each descent (Molten, Overclocked, Feral); no door choice was questioned. The staging door preview itself is the friction (PD-116) (low).
- **2026-10-08-1.md:** the deepest door verdict yet: "The 3 random doors doesn't really work with the new dungeon act system", "players should be able to choose the act and dungeon", repeated as his top "first thing you'd change" at wrap. The random-offer model itself is now on the table (PD-181) (high).

## A7. What pulls a player into another session?
- **Why:** the game has to stand on its own and hold people for hours.
- **Would change our mind:** no clear pull (no goal they are chasing), or the
  pull is outside the mod entirely.
- **Status:** partial
- **2026-10-04-1.md:** pull is the risk gamble per run, plus resource dungeons: a Mineshaft or Cow Pits he would revisit "even if it doesn't reward keystone levels so that they can stockpile iron" (high).
- **2026-10-05-1.md:** he picked the Mineshaft over the Spawner Dungeon capstone on the first trip, consistent with the resource-dungeon pull (low).
- **2026-10-07-1.md:** two unprompted first-clears in one session (Copper Works, Cow Pits) and a stated aesthetic pull: "it has a minecraft-scp vibe that I want throughout the mod" (medium).
- **Evidence:** 2026-09-27-1.md: the imagined pull is a stocked home and feeling "kitted out" (tree farm, many chests) (low).
- **2026-09-30-2.md:** the strongest pull remains economy and progression utility: salvage, merchant rooms, emerald sinks, workstation limits, and Lemon-granted generation perks were all proposed as reasons to keep collecting (medium).
- **2026-10-02-2.md:** the pull he described is narrative: a campaign with a boss floor (Herobrine or Steve nearly kills the player, Alex rescues them, he escapes), after which the other modes become the search for him. Plus a vertical endless mode (endless mines as the inverse of regular floors, more Ordeals). Strongest A7 evidence so far (high).
- **2026-10-08-1.md:** three dungeon finishes in one session including the Spawner capstone, which rolled straight into Copper Works; the campaign loop pulls. New expectation matching his narrative pull: each dungeon's last floor should end in a bigger challenge (PD-184) (high).

## A8. Where does a session drag, and where does it spike?
- **Why:** pacing across a floor and an interval. Long floors, repeated rooms and
  dead time are the enemies of "hours".
- **Would change our mind:** consistent low points in the same room types, zones
  or phases (the digest's per-floor seconds and check-ins point at them).
- **Status:** partial
- **2026-10-04-1.md:** floor 1 was the slowest floor twice (828 s and 731 s with 2 spawners) while later floors took 218 to 526 s. He also felt the story "progressing very quickly" through four themes in about 2 hours (medium).
- **2026-10-05-1.md:** same pattern: floor 1 slowest again (443 s vs 343 s). Dark rooms read as surprising but not a stall; he wants a door warning (PD-154) (medium).
- **Evidence:** 2026-09-27-1.md: "doesn't feel like it's getting too risky"; only the pillager floor spiked; floor 2 took 4 to 6.5 min (low).
- **2026-09-30-2.md:** good spikes came from surprise slimes, dungeon-wide endermen, darkness that made the player pause before moving, and varied ominous enemies and rooms. The level 7 ominous floors still did not feel much harder than the prior level (medium).
- **2026-10-01-1.md:** basalt_foundry's identity produced the spike this time: fire damage was heavy enough to rescue twice and the player framed preparation as part of the theme. That is good pressure, but it ended in a run failure before any floor completed (medium).
- **2026-10-08-1.md:** the Spawner capstone produced the session's peak: floor omen 4, lives 1, "just barely enough resources to succeed. Including enough pet dogs for the initial wave". Drags: ~30 s poison downtime ("hide for 30 seconds", PD-178) and the hopper-key toll room he named worst of the night (PD-186) (medium).

## A9. Does the room (home) matter to the player?
- **Why:** the room is the long-term product: decorating, trophies, showing it
  off. Room saves now keep paintings, frames, stands and pets.
- **Would change our mind:** players never decorate, or treat the room as a
  corridor.
- **Status:** partial
- **Evidence:** 2026-09-27-1.md: the home room gives no sign it is yours or buildable; the player began furnishing it with reward chests unprompted (medium).
- **Changed 2026-09-27:** going home prints a line saying the room is theirs to build in and that chests there are safe storage. Retest: do they decorate?
- **2026-09-29-2.md:** yes, but blocked: he tried to grow a tree at home and could not (interior ~6 blocks, trees need 7+). The "productive home" pull keeps resurfacing; home headroom is now the obstacle (medium).

---

## Emerging themes

Themes the interviewer noticed that are not yet agenda items. Promote one to an
item when it recurs or the owner says it matters.

- **Early loot with no immediate use** (emeralds, armor trims, diamond and gold): raised unprompted in both sessions (2026-09-26-1.md, 2026-09-27-1.md). Candidate for promotion.
- **"Bank" is read as item storage**, and the inventory swap on going home is silent (2026-09-27-1.md).
- **A productive home** (plant a sapling for wood) as the pull back home, versus the strict economy (2026-09-27-1.md).
- **Interviewer reliability affects sessions:** the player quit when the interviewer dropped (2026-09-27-1.md). Again in 2026-09-27-3.md: a two minute silence while Lemon looked something up (and was held by a fight) read as being ignored; the player asked Lemon to say it is looking, vanish, and come back with the answer. Third time in 2026-09-27-4.md: server wait dropped the player's question (PD-79), it timed out, and the player stopped the session until Lemon is fixed. Recommend promoting (harness, not game).
- **Omen should raise danger, not cut rewards** (2026-09-27-3.md): a wanted level with waves of lootless mobs, and a reason to court high omen. Strong opinion from the owner; tied to A1.
- **Onboarding on first join:** nothing says how to start (`/dungeon`) or what the session is (2026-09-27-3.md, role-played new player).
- **Lemon placement:** three sessions of refinement (2026-09-27-3, -4, 2026-09-29-1). Converged spec: appear at a good spot, stay anchored (bubble included), rotate only to face the player, relocate only for a new point of interest. Implemented in `Lemon.java` on 2026-09-29; verify in play.
- **Big-title delivery is liked:** floor-clear title praised unprompted and suggested for the go-home moment too (2026-09-29-1.md). Candidate: more big-text moments where they fit.
- **Locked doors shown but unreachable** (2026-09-29-1.md): door 3 wanted keystone 15 while the player sat at 1-3; he would rather locked doors be hidden entirely. Tied to A6.
- **Surplus needs a sink** (2026-09-29-1.md): "too much gear and vault keys", wants scrap or redemption. Tied to A3.
- **Interviewer reliability resolved once:** 2026-09-29-1.md is the first session since the theme emerged where nothing was missed; both questions answered in 10 to 13 s after the PD-79 fix. Keep the theme until PD-80 also proves out. 2026-09-29-2.md: two more misses, both agent-side (a shell quoting error aborted the reply; pausing the wait loop silently lapsed llm mode and one question hit the fallback at wait_s 0). PD-80 false-down also reproduced twice. 2026-09-30-1.md: PD-80 reproduced again and the false-down gap left one question unanswered for 45 s. 2026-10-01-1.md: two quiet waits stayed correct, but one player question still timed out before an llm reply; later asks answered in 8 to 12 s.
- **Dead mechanics vs dead time** (2026-09-29-2.md): the hold-the-plate early-complete fix removed dead time but he called the result "pointless"; his counter-proposal is waves that spawn off the plate itself, escalating with remaining time. A mechanic should be rescued, not skipped. 2026-10-07-2.md, owner verdict after the shipped wave version: "works just fine but is kind of lame": the plate needs a real redesign, not more tuning.
- **Flat rooms leave systems stranded** (2026-09-29-2.md): building blocks are dead loot because nothing is vertical; trap blocks telegraph instead of catch. Same root cause: geometry never demands climbing or hides mechanisms.
- **Lemon steers the test** (2026-09-29-2.md): player wants Lemon to bias dungeon generation toward rooms needing coverage. Harness feature, ties to verification pain (thicket could not be inspected before teardown).
- **Station discoverability** (2026-09-30-2.md): the Salvage Bench worked, but only after the player asked whether it existed and how to open it. 2026-10-01-1.md shows one successful bench payout after the open-on-any-use fix, but still not a true first-discovery test. Candidate for promotion if other hidden stations repeat the pattern.
- **Merchant economy** (2026-09-30-2.md): the player independently proposed merchant rooms, two-way buy/sell trades, and Ordeal-gated merchants as the missing sink for emeralds and collected clutter. 2026-10-01-1.md sharpened the need: magma cream and blaze powder are accumulating without a use.
- **Lemon as a negotiated system** (2026-09-30-2.md): building on "Lemon steers the test", the player wants item requests, dungeon-generation favors, temporary perks, and persuasion through conversation.
- **Light as architecture** (2026-09-30-2.md): dark rooms, covered lamps, ceiling-high interior walls, and player-placed torches produced the strongest pacing feedback this session.
- **Fixture drops** (2026-10-01-1.md): the player suggested mined torches and similar placed pickups should drop nothing. This could preserve lighting as a resource decision without cluttering the inventory.
- **Campaign arc and unlockable modes** (2026-10-02-2.md): structure the game as a campaign; finishing it unlocks other modes. His version: a boss floor against Herobrine or Steve, Alex rescues the player and he escapes, and the other modes are the search for him. Ties to A7 and to Alex diaries as meta progression. Candidate for promotion.
- **3D maps and Ordeals as vertical gates** (2026-10-02-2.md): a procedural map with vertical movement gated by Ordeals; endless mines as the vertical inverse with more Ordeals. Needs a dimension height decision (`dimension_type/void.json` is y 0 to 256). Ties to "Flat rooms leave systems stranded".
- **Stations should be crafted, not handed out** (2026-10-02-2.md): he would rather craft each station than take it from Lemon; reroll belongs to the enchanting table; Mending is a bought book. Ties to A3.
- **Player-facing text is too technical** (2026-10-05-1.md): four complaints in 50 minutes: the door screen has too much text, "Loot Tier 2" is "back endy", "+0 toward your key, +0 so far" is verbose, and the floor title should be "Mineshaft: Floor 2". A wording pass across the new structure UI is warranted (PD-151, PD-152, PD-153). 2026-10-06-1.md continues it: the board is still "a little crowded" (wants the floor count on its own line, non-title text ~20% smaller), resource names too verbose ("gold ore", not "deepslate gold ore" and "gold ore"), and "Side branch: 1 echo shard" was rejected in favour of a per-door cost line.
- **Mineable interiors are the game's feel** (2026-10-06-1.md): on a mineshaft seam room: "one of my favourites so far ... It feels like minecraft", with the designer-grade note "the durability of the tools balances the abundance of cobblestone". He proposed hiding ore inside interior walls ("a very cheap feature"). The reverted break rule is a keeper.
- **Run storage wants to be a container, not a service** (2026-10-06-1.md): he traced the auto-return flow correctly, then asked for it as "an enderchest": a persistent player-managed inventory. Design direction decided by the owner.
- **Interviewer reliability, again** (2026-10-02-2.md): at least 8 questions were hidden from the agent by the `wait` cursor (PD-118) and answered by the fallback; the owner noticed and said so. A direct log monitor worked. Keep the theme open until PD-118 is fixed and a session runs clean. 2026-10-07-1.md: new fault shape: `lemon_reply`/`lemon_say` intermittently return "No player was found" while the player is online (PD-163); one player ask hit the fallback over it.
- **The currency model reads as two numbers** (2026-10-07-1.md): scrap pool vs chart level vs "keystone" confused a migrated player through six questions (PD-162); he wants scrap visible on the GO HOME board and a scrap total that means what the compass says.
- **Overlevel economy seam** (2026-10-07-1.md): floors below the compass pay emeralds, branches charge scrap, so high-level players are locked out of branches; plus emerald inflow is high again. Owner: "this should take more thinking through".
- **SCP/anomaly vibe as house style** (2026-10-07-1.md): "a minecraft-scp vibe that I want throughout the mod", unprompted on Cow Pits.

### Added 2026-10-03-2 (see docs/playtests/2026-10-03-2.md)

- Party play friction: bag chest, stations and building for a second member, loot doubling (PD-131, PD-132). First session, watch for a second mention.
- Themed and funny rooms: the player wants rooms that tell a story by layout (bouncy castle slime pit, farm animal rooms, a shrub tree room).
- Lemon tips in the moment: the player saw tips only later in chat. Ask whether bubbles are noticed during play.

### Session 2026-10-07-2 (see docs/playtests/2026-10-07-2.md)

- **The currency model reads as two numbers -> now reads as none.** The Haul and Blood Doors replacement is mechanically sound (every bank path journaled correctly) but the player's one-sentence explain was "I can't tell yet, feels like it doesn't exist." Confusion traded for invisibility. His proposal: "pull the lever is the payout ritual": bank on the lever even after a finish, with quit/disconnect as failsafe. Supersedes the PD-162 thread.
- **Overlevel economy seam: partially closed.** No emerald floor pay observed anywhere; underleveled floors pay 1 scrap; side doors now price in lives not scrap (the unreachable-currency half is gone). Emerald inflow is lower (finish +8, key redemption, merchant buys exist). Remaining: whether +1 scrap on a far-below floor reads as an insult was not tested (he reset to compass 0).
- **Party rules need party scale.** One session produced: lives should scale with size, vaults should be once per player, and the owner-death purge (PD-167). A shared five-life pool across two players plus per-party vaults reads wrong to him.
- **Interviewer reliability, again:** new fault shape: `lemon_reply` delivers but never cancels the 45 s fallback, so `lemon unanswered` journals on answered asks (PD-176, five times). Plus `pdmark-*` marker commands error in latest.log (PD-177).
- **Named floors oversell generic rooms:** "it doesn't look like a drip cavern, it looks like a generic dungeon". The final floor generated generic halls under a flavor name, and the floor-start title never shows the name at all (PD-173). Ties to "themed and funny rooms".
- **Merchant economy:** redstone (witch drops) is the new dump material with no buyer; player explicitly asked for a merchant sink "like the other dump materials". Confirms the existing thread.
- **Visible-ore rooms carry the mining fantasy:** he wants more mineshaft_seam-style rooms; Rootworks's palette nodes (existing template blocks) were invisible across three floors while seam rooms got mined. Relates to "Mineable interiors are the game's feel".
- **Fail UX wants a beat:** on failure, land at Home with a "Wasted" screen, not a boot out of the world.

### Session 2026-10-08-1 (see docs/playtests/2026-10-08-1.md)

- **The random-door front offer is rejected.** Strongest structural verdict of the session: "The 3 random doors doesn't really work with the new dungeon act system", "players should be able to choose the act and dungeon", and it was his answer to "first thing you'd change" at wrap (PD-181). Supersedes the door-screen polish thread (L28).
- **Chat is a log, not a channel.** "I never read any of the run messages in chat, I usually use them as a log if I think I've missed some information" (PD-190). He also accepted a persistent text display as enough HUD ("persistent text should be more than enough", PD-187). Critical state belongs on titles, boards and displays.
- **Capstones want a finale.** "There wasn't a boss for copper works", "There should be some extra challenge in the last floor" (PD-184). Matches his earlier campaign pull: endings should escalate.
- **Sculk wants one language.** Sensors feeding omen while shriekers spawn mobs reads as two systems; he wants both to mean stealth, and wants sensor_gallery replaced (PD-183).
- **The toll room is the worst room.** Asked for the worst room of the night at wrap: "the room with the key in the hopper to open the iron doors" (PD-186).
- **Interviewer reliability, again:** the watcher held the loop and auto-thinks every ask, but five asks still hit the fallback and journaled `llm_late`, all agent-side poll latency. The harness needs the watcher to send a canned hold reply itself.
- **Theme promotion candidate: copper theming.** "could we thematically make it so that all mobs wear two pieces of copper, including weapons?" scoped to Copper Works (PD-185). Same instinct as "themed and funny rooms": rooms and dungeons should commit to their bit.

### Session 2026-10-09-1 (see docs/playtests/2026-10-09-1.md)

- **The Kennels cannot be played.** First trip: a `kennels_tier_2` spawner queued wolves that can never satisfy their spawn rules on a stone hall floor and the floor soft-locked (PD-194). Second trip: fire spread burned the wooden kennel rooms and he ended the session on it (PD-195). Both broke inside 20 minutes of the rework reaching him.
- **Spawn rules are a theme/system contract, not a detail.** Any trial-spawner config whose potentials include a mob with restrictive spawn rules (wolves need spawnable ground and light) can stall a floor the same way wherever it lands. The fix belongs where configs meet rooms, not in one room.
- **Keys as a run resource.** "Vault keys should last the dungeon lifespan, not the floor's": carrying a key forward into the same dungeon is an expectation he already has (PD-196). Tension with J7's never-leave-the-floor rule needs a ruling.
- **Interviewer pacing:** "We're doing a playtest, right? You haven't asked much." Watch for the interview going quiet during long investigations; he wants the questions too.
- **Mood:** "New build is very good compared to just a couple days ago." The design pass is landing; the Kennels is the visible exception.
