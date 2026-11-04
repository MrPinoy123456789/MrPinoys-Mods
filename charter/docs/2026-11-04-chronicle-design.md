# The Realm Chronicle, second edition

Design for upgrading Charter's Chronicle, the Chronicle pages of the Charter book, and
the Discord bot (Gord, "Dis Gord" to the owner) so that it keeps the Chronicle in Discord.
Written 2026-11-04 from the owner's brief, the 2026-11-04 Discord community study
("Realm Chronicle Research"), and the code on master at f0685d35. Design only; nothing
here is built.

## 1. What the Chronicle is for

The Chronicle is the server's memory and its stage. Every kingdom act with a true answer
(who joined, who was crowned, who declared war, who took an outpost) is written once, in
prose, where players read it: a lectern in the keep, a page in the Charter book, and a
Discord channel. The community study says what the second edition must be:

- **The voice is a town crier who cannot keep a straight face.** Formal openings, plain
  punchlines, the players' own words. No swearing: minors play.
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
  2026-10-27). Nothing comes back from Discord into the game. The bot keeps reading no
  channels.

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
  exists. Used in Discord, on the book's home page, and on the realm bulletin.
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

A small table in `templates.json` under the reserved key `_stories` gives the standing
titles. The renderer appends the title to the headline and line when the event matches.

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

Titles are plain text in the file so the owner can rename them to whatever the players
start saying. "Former King" is from the players ("Former king is tuff"); it is kept
clean. Nothing from section 11 of the study, and none of the Human mock tyranny lines,
ships as a default.

### 3.4 New events worth telling

All are derived in core from state the engine already has, recorded through the same
`History.record`, so they get templates, tiers, Discord and the book for free.

- `MilestoneEvents.MembersReached(at, org, count)` at 5, 10, 20 members. The study's
  sorest pain is empty kingdoms; this makes growth a public event and gives the Herald
  its "who is hiring" line. Fired once per threshold per kingdom per Age.
- `MilestoneEvents.FirstOf(at, org, what)` for the first war declared, first outpost
  taken, first outpost held to the end of a war. One each per Age.
- `SiegeEvents.FirstBlood` is not needed: it is a story title on WarDeclared.
- `ModerationEvents` already record Kicked, Muted, Suspended with a `reason`. The
  reason is a Lord's free text: it stays in history and the kingdom lectern, and is
  dropped from the headline, the realm book and Discord. The judge's name is kept; the
  design (DESIGN.md section 15.5) wants discipline on the record.

### 3.5 The calendar on a headline

The book keeps "On day 3, the second week of Eclipse". Headlines carry no date: Discord
stamps the message and the book page groups by day. The Herald opens with the week:
"The second week of Eclipse." Seasons stay as they are; the roadmap's Ages, when built,
add "Age 1" to the volume title and freeze a volume at Age end (section 7).

### 3.6 Safety: what never reaches a crowd

- Player-typed text that reaches Discord is limited to kingdom names, rank names and
  office names, all of which already pass the engine's name rules. Reasons, vote
  `about` texts and contract terms never go to Discord; they stay in the kingdom book.
- Discord markdown is escaped in every name; `allowed_mentions` is set to the
  configured kingdom roles only, so a kingdom named "@everyone" pings nobody.
- No Sovereign amounts from a player's own vault, ever. Treasury movements name the
  kingdom and the amount in Sovereigns, rounded, Marks only inside the book.
- The bot keeps zero gateway intents. It never reads a message, so there is nothing
  for a player to inject.

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

- Keep lecterns and Realm lecterns stay as they are.
- **Realm bulletin**: an operator may mark up to four signs near a Realm lectern with
  the admin tools menu. Each shows one realm headline, newest at the first sign, four
  lines of fifteen characters, wrapped on words, blank when the headline does not fit.
  Signs render on Bedrock and need no display entity. This is the one piece here the
  owner may cut; it is optional and small.

## 5. Gord keeps the Chronicle in Discord

### 5.1 Config, `config/charter/discord.json`

New fields, all blank by default so nothing changes for a server that has not asked:

```
"chronicleChannelId": "",            realm-tier headlines, live
"heraldChannelId": "",               the weekly Herald (blank: same as chronicle)
"kingdomChannels": { "<kingdom name>": "<channel id>" },   kingdom-tier headlines
"kingdomRoles":    { "<kingdom name>": "<role id>" },      pinged on a war against them
"heraldDay": "SUNDAY", "heraldHourUtc": 21,
"postBatchSeconds": 10, "postsPerMinute": 6, "catchUpMax": 20
```

The bot needs Send Messages in those channels and Mention Roles for the pings. It needs
no new intents.

### 5.2 Live feed

- `DiscordRest.postMessage(channelId, message)` is the one new REST call.
- A `ChroniclePoster` listens on `History` like the join request code does, keeps the
  entries for `postBatchSeconds`, renders them with the same `ChronicleRenderer` (so
  folding happens: one message for three joins), and posts realm-tier headlines to the
  realm channel and kingdom-tier ones to the kingdom's channel if configured. Admin and
  quiet entries never post.
- Rate: at most `postsPerMinute`; past that, lines are held and sent as one message
  "and 4 more, in the book" with the headlines joined. This reuses the quiet-window idea
  of `NoticeGate`, in a pure class with time passed in.
- Pings: WarDeclared posts in the realm channel with the defender's role mentioned,
  once per war; nothing else pings.
- Durable cursor: `charter/discord-chronicle.json` stores the history index last posted.
  On start the poster posts what it missed, up to `catchUpMax` lines, then one line
  "and N more happened while the crier slept". No message is ever edited or deleted:
  a correction is a new line, which keeps the Chronicle honest and the code simple.

### 5.3 The weekly Herald

Posted at `heraldDay` `heraldHourUtc` (default Sunday 21:00 UTC, the study's busiest
hour across the timezone split) by the same tick that refreshes lecterns. Built from the
last seven days of history by a `Herald` class in core (testable with a fixed clock).
Ten lines at most, in this order, each section skipped when empty:

1. The week: "The second week of Eclipse."
2. Crowns: coronations, abdications, kingdoms founded or dissolved.
3. Wars: declared, opened, taken, held; one line each, the latest first.
4. Comings and goings: defections first (The Betrayal), then the count of joiners per
   kingdom in one line.
5. Firsts: the milestone events of 3.4.
6. Who is hiring: the two smallest kingdoms by members, named plainly without a joke
   ("Azrak holds the line with 4. Aurelia with 7."). Mercenaries are counted too.
7. One closing line, from a small list in `templates.json` under `_herald`, chosen by
   week number, so the owner can edit the jokes.

If the week has nothing, the Herald is one line: "A quiet week. The crier slept." Posted
once; the cursor file records the week so a restart does not post it twice. An operator
can force it with `/charter admin herald now` and preview it in chat with
`/charter admin herald preview` before the first live one.

### 5.4 What stays out, on purpose

- Acting from Discord (votes, declarations) stays unplanned.
- Lemon does not write the Herald. The deterministic Herald ships first; a Lemon
  flourish line can be added later behind a knob, with the plain version as fallback.
- Embeds, images and threads: plain text with bold names only. Everything a Bedrock
  player sees in the book must match what Discord says.

## 6. Build order and effort

| Step | What | Effort | Risk |
|---|---|---|---|
| C1 | Headlines, tiers and `_stories` in `TemplateSet` and `DefaultTemplates`; voice rewrite of every default line; coverage tests | M | Low. The template file stays backward compatible |
| C2 | Milestone events (3.4), reason stripping, Sovereign rounding | S | Low |
| C3 | `DiscordRest.postMessage`, `ChroniclePoster` with batch, rate and cursor; config fields | M | Medium: Discord rate limits and a restart replaying history. The cursor file and `catchUpMax` cover it |
| C4 | `Herald` in core with a fixed-clock test; the tick, the admin commands | M | Low |
| C5 | Book pages: home headlines, Chronicle page, Tales of me, Discipline, Legend | M | Medium: Bedrock forms need a real client to judge; follow the 2026-11-01 plan's test account rule |
| C6 | Realm bulletin signs | S | Low; optional |
| C7 | Ages: volume per Age, frozen at Age end, milestones reset | L | Later, with the roadmap's Ages decision |

C1 and C2 are core-only and can be built and tested without a server. C3 and C4 need a
test Discord channel on the Kingdoms Of Fantasy server; the bot token is already in
place. Deploy follows the owner's rule: only the master session deploys d212ecae.

## 7. Tests

- Core: every event record has a line, a headline under 80 characters and a tier
  (extends the existing coverage test). Stories fire on the right events and not on
  look-alikes (a Joined with `from` set to a raider band is not The Betrayal). The
  renderer strips moderation reasons from headlines. Herald output for a scripted week
  matches a golden file; an empty week gives the one-line Herald.
- Discord: `ChroniclePoster` with a fake clock and a recording REST: batching folds
  three joins into one post, the rate cap holds lines and sends one summary, the cursor
  survives a restart without a duplicate, admin entries never post, a kingdom named with
  markdown or a mention arrives escaped.
- Fabric gametests: the home page shows the newest realm headline after a war
  declaration; Tales of me lists an entry that names the player and not one that does
  not; the Legend page counts a won war. Register each in
  `src/gametest/resources/fabric.mod.json` and check the test count rises.

## 8. Questions for the owner before C3

1. Channel ids: one public Chronicle channel, and do the five kingdom channels get their
   own kingdom-tier feed from day one, or only the public feed first?
2. Roles: may the bot ping a kingdom's role when war is declared on it? It is the one
   ping in the design.
3. Herald time: Sunday 21:00 UTC as the study suggests, or the night of the first war
   window?
4. "Former King" as a standing title uses a player's phrase. Keep it, or stay with
   titles the Chronicle coins itself?
5. The realm bulletin signs: build, or cut?

## 9. Risks

- **The voice tires.** A fixed set of titles read a hundred times stops being funny.
  Mitigation: the owner edits `templates.json` on the live server without a rebuild; the
  closing lines rotate by week.
- **A crowded channel.** Ten players doing kingdom admin can produce thirty kingdom-tier
  lines an hour. Mitigation: kingdom tier goes to kingdom channels or nowhere; the realm
  channel only ever sees realm tier; the rate cap folds the rest.
- **Someone games the Chronicle.** Joining and leaving to farm Betrayal headlines.
  Mitigation: a player's second defection in a week is `kingdom` tier and titled "The
  Wanderer" instead; the race-change rule already makes it costly.
- **Discord outage or token revoked.** Nothing in the game depends on the post
  succeeding; the cursor only advances on a 2xx, so lines are posted when the bot is
  back, capped by `catchUpMax`.
