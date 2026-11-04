# The Realm Chronicle, second edition

Design for upgrading Charter's Chronicle, the Chronicle pages of the Charter book, and
the feed from which Lemon Berry, with the owner approving every line, tells the Chronicle on Discord.
Written 2026-11-04 from the owner's brief, the 2026-11-04 Discord community study
("Realm Chronicle Research"), and the code on master at f0685d35. C1 to C3 of section 6
were built the same day on `feature/chronicle-design`; C4 to C6 are design only.

## 1. What the Chronicle is for

The Chronicle is the server's memory and its stage. Every kingdom act with a true answer
(who joined, who was crowned, who declared war, who took an outpost) is written once, in
prose, where players read it: a lectern in the keep, a page in the Charter book, and a
Discord channel. The community study says what the second edition must be:

- **The voice is a town crier who cannot keep a straight face.** Formal openings, plain
  punchlines, the players' own words. No swearing: minors play. On Discord the crier is
  Lemon Berry (5.3); in the book the lines stay plain so a server can reword them.
- **Short.** They do not read. One line per happening, the name first. Ten lines is a
  long Herald.
- **Their names, not ours.** Fishies, commoner, mercs, ruins, House Aurelia, the Dwarven
  Empire, the High Fairy King. Never the admin kingdom's name; nobody uses it.
- **Recurring headlines.** A defection is always "The Betrayal". The players already
  tell these stories; the Chronicle gives them a fixed title so a new one lands as a
  sequel.
- **Never.** Affairs and wedding gossip (one plain line at most), real life, ages,
  nationality, jokes at small kingdoms, moderation reasons in public, anything a player
  typed that was not meant for the whole server.
- **The game owns the state; Discord carries the voice** (DESIGN.md, settled
  2026-10-27). Nothing comes back from Discord into the game.

## 2. What exists today

| Piece | Where | State |
|---|---|---|
| History | `core/.../history/History.java`, append only, listeners | Built. Every feature records its own event records (OrgEvents, GovEvents, SiegeEvents, LandEvents, ContractEvents, TreasuryEvents, RankEvents, ModerationEvents, NpcEvents, GrantEvents, StoreEvents) |
| Templates | `core/.../chronicle/DefaultTemplates.java`, overlaid by `config/charter/templates.json` | Built. One prose line per event key, `{field}` holes, `{field?text}` conditionals, `{n#noun}` plurals. An empty template silences an event |
| Renderer | `core/.../chronicle/ChronicleRenderer.java` | Built. Hides admin events, routine events (asks, rejections, unneeded food) and anything that names a raider band. Folds a founding into one line and same-day joins into one line |
| Calendar | `core/.../chronicle/Calendar.java` | Built. Four seasons (Bargains, Bloodmoons, Ragnarok, Eclipse) of four weeks; "day 3, the second week of Eclipse" |
| Books | `fabric/.../chronicle/ChronicleBooks.java`, `Chronicles.java`, `RealmLecterns.java` | Built. Any lectern in a keep's core chunk holds that kingdom's Chronicle; operator-placed Realm lecterns hold the realm's. 50-page volumes, newest first, refreshed within seconds of a new entry |
| Charter book | `fabric/.../book/MemberPages.chronicle`, `NewcomerPages` | Built. A Chronicle button opens the kingdom volume as a written book; newcomers get a Realm Chronicle button. Nothing else about history is on any page |
| Discord bot | `fabric/.../discord/*` | Built for one job: link accounts, show join requests, DM the members who can answer them, throttled by `NoticeGate`. Gateway with no intents; REST client has `directMessage` and command registration only. It has never posted to a channel |
| Knob | `Knobs.chronicleShowAdmin` | Built |

Three gaps the study makes urgent: there is no notion of which lines matter to the whole
server versus one kingdom; the lines are long and even ("On day 3, the second week of
Eclipse, X joined Y."); and the bot cannot post anywhere a crowd reads.

## 3. The upgraded Chronicle (core and config)

### 3.1 Two layers per event: the headline and the line

Every event key gets two texts instead of one.

- **Headline.** Up to about 60 characters, the name first, the crier's title where one
  exists. Used on the book's home page and as the scribe's cue for Discord.
  Example: `**Wing** left the Fairies for House Aurelia. The Betrayal.`
- **Line.** The sentence for the books, as now, but shorter. Example: `On day 3 of
  Eclipse, Wing left Everdelle for House Aurelia. The Fairies call it The Betrayal.`

`templates.json` stays one file. A key maps either to a string (the line, as today, so
every existing server file still loads) or to an object `{ "headline": ..., "line": ...,
"tier": ... }`. `DefaultTemplates` ships both for every event. A key with no headline
uses its line clipped at a sentence end. The coverage test that already checks every
event record has a template now also checks every told event has a headline under 80
characters.

### 3.2 Three tiers

Each key carries a tier. The tier decides where a line goes; it never decides whether
history records it.

| Tier | Meaning | Goes to |
|---|---|---|
| `realm` | The whole server would turn its head | Realm lecterns, realm Discord channel, Herald, book home page |
| `kingdom` | The kingdom's own business | Keep lecterns, kingdom book page, that kingdom's Discord channel if it has one |
| `quiet` | Recorded, never told | Admin view only (the current `routine` set, vault deposits, rank reorderings, grants) |

Default tiers, from the study's "events to log":

- **realm:** Founded, Dissolved, Seceded, GroupBrokeAway, Joined with `from` set (a
  defection), OfficeFilled and OfficeVacated on the deciders' office (a crown changes
  hands), WarDeclared, WarOpened, OutpostCaptured, WarLapsed, KeepLapsed, Executed,
  Escaped, PrisonersFreed, Breached, Renamed, Activated, and the new milestone events of
  3.4.
- **kingdom:** Joined without `from`, Left, Expelled, Admitted, Invited, OfficeCreated,
  SeatsChanged, votes, treasury movements, Claimed, Unclaimed, CampPlanted, CampActive,
  KeepRunningLow, Arrested, Fined, Pardoned, Released, Imposed, ranks given and renamed,
  Kicked, Muted and Suspended (without the reason text), NexusMoved, KeepMoved.
- **quiet:** Asked, Rejected, RequestLapsed, FoodDeposited outside a siege,
  VaultDeposited, RankMoved, RankPowerGiven, RankPowerTaken, Granted, Revoked,
  LedgerGranted, LedgerRevoked, LecternMoved, Unmuted, Unsuspended, DepositedMarks and
  WithdrawnMarks under 1 Sovereign (64 Marks).

Admin-flagged entries keep their current rule (hidden unless `chronicleShowAdmin`) and
are never posted to Discord at all, whatever their tier. Raider band events stay hidden
in the story; the Horde of the roadmap gets its own realm-tier events when it is built.

### 3.3 Named stories

A fixed table in core gives the standing titles. The renderer appends the title to the
book line when the event matches, and the feed (5.1) carries it as a hint for the
scribe, who owns the titles on Discord and may coin more from the players' own words.

| Title | Fires on |
|---|---|
| The Betrayal | Joined with `from` set, from one player kingdom to another |
| The Crowning | OfficeFilled on the deciders' office with a previous holder |
| Former King | OfficeVacated on the deciders' office by choice (why is not a lapse) |
| First Blood | The first WarDeclared of an Age |
| Held the Line | WarLapsed where the defender had fewer members than the attacker |
| The Long Siege | OutpostCaptured more than 3 days after WarOpened |
| A Light in the Window | A kingdom reaching 5 members (3.4) |
| Full House | A kingdom reaching 10 members |

"Former King" is from the players ("Former king is tuff"); it is kept clean. Nothing from section 11 of the study, and none of the Human mock tyranny lines,
ships as a default.

### 3.4 New events worth telling

All are derived in core from state the engine already has, recorded through the same
`History.record`, so they get templates, tiers, Discord and the book for free.

- `MilestoneEvents.MembersReached(at, org, count)` at 5, 10, 20 members. The study's
  sorest pain is empty kingdoms; this makes growth a public event and gives the Herald
  its "who is hiring" line. Fired once per threshold per kingdom per Age.
- `MilestoneEvents.FirstOf(at, org, what)` for the first war declared, first outpost
  taken, first outpost held to the end of a war. One each per Age.
- `ModerationEvents` already record Kicked, Muted, Suspended with a `reason`. The
  reason is a Lord's free text: it stays in history and the kingdom lectern, and is
  dropped from the headline, the realm book and Discord. The judge's name is kept; the
  design (DESIGN.md section 15.5) wants discipline on the record.

### 3.5 The calendar on a headline

The book keeps "On day 3, the second week of Eclipse". Headlines carry no date: Discord
stamps the message and the book page groups by day. The Herald opens with the week:
"The second week of Eclipse." Seasons stay as they are; the roadmap's Ages, when built,
add "Age 1" to the volume title and freeze a volume at Age end (section 6, C6).

### 3.6 Safety: what never reaches a crowd

- Player-typed text that reaches Discord is limited to kingdom names, rank names and
  office names, all of which already pass the engine's name rules. Reasons, vote
  `about` texts and contract terms never go to Discord; they stay in the kingdom book.
- No Sovereign amounts from a player's own vault, ever. Treasury movements name the
  kingdom and the amount in Sovereigns, rounded, Marks only inside the book.
- Gord keeps zero gateway intents and never posts. Every Discord line is drafted from
  a file that holds only vetted names and plain lines, and the owner approves it first.

## 4. The Charter book

Both the Java dialog pages and the Bedrock native forms (the 2026-11-01 plan) are built
from the same page model, so these are page additions, not two builds.

### 4.1 Home page: three headlines

Under the player's situation, a block "In the realm" with the three newest realm-tier
headlines, each one line, and a button "The Chronicle". A newcomer's home shows the
same block above Join. This is the whole of the "latest news" feature; it costs one
render of the last few entries per open.

### 4.2 The Chronicle page

Replaces the current single button that opens the written book.

- **Today** first: headlines from the last 24 hours, realm and own kingdom together,
  newest first, at most 8.
- Three buttons: **My kingdom** (the kingdom volume, as now), **The realm** (the
  realm volume), **Tales of me** (the entries that name the player, a small vanity
  page the study suggests players will love and screenshot).
- A Lord sees a fourth, **Discipline**, the moderation entries with reasons, only for
  their own kingdom.
- Every volume still opens as a written book (`BookReading.open`), which Geyser renders
  for Bedrock. Pages stay under the 230 character target.

### 4.3 The Legend page

One page per kingdom, generated from history, reached from the Faction page and from a
newcomer's view of a kingdom: founded on (calendar phrase), its Lord and since when,
members now and the biggest it has been, wars declared, won, lost and held, outposts
held now, standing titles earned (A Light in the Window, Full House). A newcomer picking
a kingdom gets a reason, and a small kingdom reads as a story rather than a number.

### 4.4 In the world

Keep lecterns and Realm lecterns stay as they are. The book is the record a player is
pointed at when a Discord line is disputed.

## 5. Lemon Berry tells the Chronicle on Discord; the owner approves every line

Decided 2026-11-04: nothing posts to Discord on its own, Gord included. The owner asks a
Claude Code session for entries, reads them, approves or edits, and only then are they
posted, under the name of Lemon Berry (5.3). The game stays the record; the owner is
the editor; Claude Code is the scribe; Lemon Berry is the voice. Charter's whole job
toward Discord is one file.

### 5.1 The feed

Charter writes `<world>/charter/chronicle-feed.jsonl`, append only, one JSON object per
told entry, written by the same tick that refreshes the lecterns:

```
{"at": 1762290000000, "day": "day 3, the second week of Eclipse", "tier": "realm",
 "kind": "OrgEvents.Joined", "story": "The Betrayal",
 "line": "On day 3, the second week of Eclipse, Wing left Everdelle for House Aurelia.",
 "headline": "Wing left the Fairies for House Aurelia.",
 "orgs": ["Everdelle", "Aurelia"], "players": ["Wing"], "n": 418}
```

- `n` is the history index, so the scribe keeps a cursor and never drafts a line twice.
- Only told entries: admin-flagged, quiet-tier and raider-band entries are not written.
  Folded happenings (three joins in a day) are written as the one folded line.
- Nothing a player typed is in the file except kingdom, rank and office names, which the
  engine already vets. Reasons, vote texts and contract terms are never written.
- The file rolls over per Age (section 6, C6) and is never edited. A rename of a kingdom
  does not rewrite old lines; the Chronicle remembers whose it was.

The session fetches the file from the Kinetic panel the way `tools/playtest` already
fetches logs and journals. No new network code in Charter.

### 5.2 The scribe's workflow (a Claude Code skill, `chronicle-herald`)

1. The owner says "chronicle" or "herald". The skill fetches the feed, reads the cursor
   in `tools/chronicle/cursor.json`, and takes the new lines.
2. It drafts in Lemon Berry's voice (5.3): for "chronicle", one headline per realm-tier
   entry, name in bold, no date; for "herald", at most ten lines in the order crowns,
   wars, comings and goings, firsts, who is hiring, opened by the week's calendar phrase.
3. It shows the draft and stops. The owner approves, edits, or strikes lines. Nothing is
   posted without that yes in chat.
4. On approval it posts to the configured channel through the Discord bot tools the
   workspace already has (the Kingdoms Of Fantasy bot, `tools/discord/kof.mjs` gains a
   post command; it is read only today), then advances the cursor and appends the posted
   text to `tools/chronicle/posted/<date>.md` so the repo holds what was said.
5. Kingdom-tier lines are drafted only when the owner asks for a kingdom by name.

The voice rules the skill carries are Lemon Berry's (5.3) and the community study's: the standing titles (The
Betrayal, The Crowning, Held the Line, A Light in the Window, Full House), the players'
own words for things, and the never-list (swearing, affairs or wedding gossip beyond one
plain line, real life, ages, nationality, jokes at small kingdoms, moderation reasons,
anything from section 11 of the study, quoting a player whose words are not already
public). A ping of a kingdom's role is drafted only for a war declared on it, and the
owner sees it before it goes.

### 5.3 Lemon Berry, the voice

Every Chronicle line and every Herald on Discord is posted as Lemon Berry. She is a
character, not a bot: the Discord account or webhook carries her name and face, Claude
Code writes her lines, the owner approves them. She replaces the plain town crier of
section 1; the crier's rules (short, names first, their words, the never-list) still
hold underneath her.

**Who she is.** A sassy Goldberry. Tolkien's Goldberry is the River-daughter: serene,
laughing, hospitable, unbothered by the great affairs of kings, at home with rain and
reeds and water. Lemon Berry keeps all of that and adds tartness: lemon and berry, sharp
and sweet in one mouth. She has watched rivers outlast every kingdom on their banks, so
she finds crowns charming and a little funny. She is fond of everyone and impressed by
nobody.

**How she talks.**

- Warm first, sharp second. The sting is always in the last clause, never the first:
  "House Aurelia has a new villager hall. One builder, three days, no one else online.
  She noticed."
- Weather and water. The realm's seasons (Bargains, Bloodmoons, Ragnarok, Eclipse) are
  her weather; she opens a Herald with it. Rivers, tides, rain, reeds, mud are her
  metaphors; she never reaches for swords or fire.
- She treats kings as guests at her table. "The Emperor of Azrak, four dwarves strong,
  has sent word that the Empire is hiring. She has set out more chairs."
- She loves the fishies and denies it. "She has no favourites. The Merfolk have a new
  Queen. She is not smiling, that is just her face."
- Third person about herself, sparingly, as a running joke. Otherwise plain sentences.
- Film and pop culture allowed where the owner's rule allows it (buttons, flavour, never
  errors): one reference a Herald at most, and it must stand without the reference being
  caught.
- She does not use the players' slang. She hears it. "A player described the new keep as
  having aura. She has looked it up. She agrees."
- Standing titles are hers to announce: "The Betrayal, part two." She can coin a title
  from words the players already use publicly, never from private channels.

**What she never does.** Swears. Takes a side in a war. Mocks a small kingdom (she
roasts the big ones and sets out chairs for the small ones). Repeats a moderation
reason, a real-life detail, an age, a nationality, or anything from section 11 of the
community study. Quotes a player whose words are not already public. Announces an
affair or a wedding beyond one plain, kind line. Acts in the game or claims a fact the
feed does not hold.

**Six lines in her voice**, from the kinds of entries the feed will carry:

1. Defection: "**Wing** has left the Fairies for House Aurelia. The Betrayal, the
   Fairies are calling it. She has seen rivers change course too. They never apologise
   either."
2. Coronation: "**Lizzie** is Queen of Azurius. The former King says it is tuff. She has
   checked; it is."
3. War declared: "**Faeloria** has declared war on House Aurelia and named the eastern
   outpost. Three days of notice. She suggests Aurelia uses them."
4. War held: "**Azrak** held the line at the quarry, four dwarves against nine. Held the
   Line. She has put the kettle on for them."
5. Milestone: "**Aurelia** is ten strong. Full House. She counted twice, because last
   week it was two people and a villager hall."
6. Quiet week Herald: "Eclipse, the second week. Nothing happened. She found it restful.
   You will not."

**Herald shape in her hands.** Weather line, then crowns, wars, comings and goings,
firsts, who is hiring, then one line of her own. Ten lines at most. Names in bold.

**Guardrails for the scribe.** The skill writes the draft as Lemon Berry from the feed
only. The owner reads every line before it posts. If a draft is funny at the expense of
one of the sore spots, the owner strikes it and the skill learns nothing from it; the
never-list is fixed text in the skill, not a preference it adjusts.

### 5.4 What this removes from the Java side

The Herald generator, the Discord poster, the rate cap, the catch-up cursor, the role
mention plumbing and the realm bulletin signs are all out. Story titles are editorial
judgment, so the skill owns them; the `story` field in the feed is only a hint from the
fixed table in 3.3. Gord stays what it is today: account links and join requests.

## 6. Build order and effort

| Step | What | Effort | Risk |
|---|---|---|---|
| C1 | Headlines and tiers in `TemplateSet` and `DefaultTemplates`; voice rewrite of every default line; coverage tests | M | Low. The template file stays backward compatible |
| C2 | Milestone events (3.4), reason stripping, Sovereign rounding | S | Low |
| C3 | The feed file (5.1) with a pure writer class and a fixed-clock test | S | Low |
| C4 | The `chronicle-herald` skill with Lemon Berry's voice, the fetch and post commands in `tools/`, the cursor and posted log | S | Low: every post has the owner's yes in front of it |
| C5 | Book pages: home headlines, Chronicle page, Tales of me, Discipline, Legend | M | Medium: Bedrock forms need a real client to judge; follow the 2026-11-01 plan's test account rule |
| C6 | Ages: volume and feed per Age, frozen at Age end, milestones reset | L | Later, with the roadmap's Ages decision |

C1 to C3 are core and file work, testable without a server. Deploy follows the owner's
rule: only the master session deploys d212ecae.

**Built 2026-11-04 (C1 to C3).** `Template` carries a headline and a `Tier`;
`TemplateSet.overlay` fills a server's plain-string line with the built in headline and
tier, so every existing `templates.json` still loads. `ChronicleRenderer.tell` gives each
told happening its line, headline, tier, story title and the names involved, with the
same folding as the books; `tier` makes a defection, a crowning and an abdication the
realm's, and a treasury movement under one Sovereign quiet. `Stories` is the fixed title
table. `MilestoneEvents` (MembersReached at 5, 10, 20; FirstOf war, outpost taken,
outpost held) are derived by `Milestones.after` and recorded by the engine as the
rules' own, so an operator's hand still counts. Marks render as Sovereigns through
`{n#coin}`. `ChronicleFeed` writes the JSON objects; `ChronicleFeedFile` appends them to
`<world>/charter/chronicle-feed.jsonl` from the lectern tick, resumes from the last
line's `n`, and starts from now on a server that gains the feed with history behind it
(knob `chronicleFeed`, default on). `templates.json` now writes objects with `line`,
`headline` and `tier`. Tests: `ChronicleEditionTest` (core) and two new checks in
`ChronicleBookTest` (fabric). Not built: the Joined headline is derived from the line
("Gil joined Ashfall after leaving Riverside. The Betrayal.") rather than the design's
"left X for Y" wording, since one template cannot say both forms; "The Long Siege" and
the member-count condition on "Held the Line" were dropped as needing state the renderer
does not have.

## 7. Tests

- Core: every event record has a line, a headline under 80 characters and a tier
  (extends the existing coverage test). The renderer strips moderation reasons from
  headlines. The feed writer emits exactly the told entries, in order, with `n` rising,
  and writes nothing for an admin, quiet or raider entry; a kingdom named with markdown
  or a mention arrives as plain text; a folded day of joins is one object.
- Tools: a Node test for the cursor (new lines only, idempotent on a rerun) and for the
  post command's dry run.
- Fabric gametests: the home page shows the newest realm headline after a war
  declaration; Tales of me lists an entry that names the player and not one that does
  not; the Legend page counts a won war; the feed file gains one line per told event.
  Register each in `src/gametest/resources/fabric.mod.json` and check the test count
  rises.

## 8. Questions for the owner before C4

1. Which channel takes the public Chronicle, and do kingdom channels get lines at all?
   Does Lemon Berry post through her own bot account or a webhook with her name?
2. May a drafted war line ping the defending kingdom's role, once you have approved it?
3. Herald on Sunday 21:00 UTC, or the night of the first war window?
4. Should posted entries also be written back into the game as a "Herald" page in the
   book, so Bedrock players who are not on Discord see the same words? (Small: a text
   file the server reads.)

## 9. Risks

- **A draft reaches for a sore spot.** The scribe can suggest a joke the study says is
  a sore spot. Mitigation: the owner reads every line before it posts, the skill carries
  the never-list, and the feed holds no free text for it to repeat.
- **A crowded channel.** Ten players doing kingdom admin can produce thirty kingdom-tier
  lines an hour. Mitigation: the realm channel only ever sees realm tier; kingdom tier
  goes to kingdom channels or nowhere.
- **Someone games the Chronicle.** Joining and leaving to farm Betrayal headlines.
  Mitigation: a player's second defection in a week is `kingdom` tier and titled "The
  Wanderer" instead; the race-change rule already makes it costly.
- **Nobody posts for a week.** Nothing in the game depends on it; the feed is append
  only and the next session resumes from the cursor. The book was always complete.
