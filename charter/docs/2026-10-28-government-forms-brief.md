# Government forms and breaking away

Status: approved by the owner on 2026-10-28, not built. This changes what S1 and
commit 25d7e4ef of `2026-10-27-charter-book-implementation.md` built, and adds one
new action. Read the ground rules in section 0 of that plan first; they all apply
(branch `feature/charter-build`, commits authored as MrPinoy123456789 with the
`Co-Authored-By: Claude Sonnet 5.5` line, never push, never deploy, no dashes as
punctuation, register every new test class, write problems under Builder's notes).

## Why

Members who can always vote to change the form make a Lordship meaningless. The
owner's model: a government is a recursive tree. The only way to unseat a ruler you
do not control is to break away, found your own keep, and go to war with the old
faction like any other rival. Nothing new is needed for the war itself.

## The rules

### R1. Only the deciders change their own form

- A Commune's deciders are its members: changing its form is a vote of the members.
- A Lordship's decider is its Lord, acting alone: he may make himself a council (any
  one may act, or deciding together) or abdicate into a Commune at once.
- A council changes its own form by a majority of its seats (`proposeToSeats`), as
  built. Dropping to one seat opens the election among the former holders, as built
  in 47a02fd4.
- Members of a Lordship or council have NO vote on the form. Remove the rule added in
  25d7e4ef that a vote of the members may still make any change. `SetSeats` is
  reserved (members' vote) only when the office has 0 seats now; a Lord going to 0
  seats is his own act, not reserved.

### R2. No back doors

A members' vote must not be able to remove a Lord by another route. For the decision
office, these follow R1 (deciders only; in a Commune that means the members, as
today): `Unseat`, `Vacate`, `Appoint`, `Expel` of a holder of that office,
`RevokeGrant` of its root grants, and `Rename`. A Lord may vacate himself (abdicate);
an empty office then reads as members by vote, as built. A council holder is removed
by a majority of the seats. List every action that `reserved(...)` returns true for
and decide each against this rule; report the table under Builder's notes. The Join
page line "changing or ending the charter" promises an action that does not exist:
remove it (see R5).

### R3. Sieges do not change

Winning a siege still means raze or occupy (DESIGN 10.8). A castle changes hands only
by occupying it and a treaty that cedes it. Do not add a permanent claim outcome.

### R4. Breaking away

Two cases, one "Break away" button in the book (R5).

**A. A sub-faction secedes as a whole.**
1. A vote of the sub-faction's members, whatever its form. It passes when Yes is more
   than half of ALL members who are eligible to vote (the 3 day `voteEligibilityDays`
   rule). Not voting and abstaining count as No. Do not filter by activity: dead
   accounts are a separate problem (DESIGN dormancy), not handled here. This is the
   one exception to R1 and is the only vote members of a Lordship get.
2. When it passes the sub-faction becomes a new top-level organization with all its
   members, its sub-factions and any keeps it owns (DESIGN 16.1: the keeps go with it).
   Contracts and grants with the old parent end and are recorded.
3. If it owns no keep it must place a Nexus within `breakawayHours` (new knob, 24)
   outside every keep's protection (reuse `LandGraph.keepProblemForNew`), paying
   `keepPriceDiamonds` from its OWN treasury. Otherwise the break lapses and nothing
   changes (write a Chronicle line).
4. The founding and secession cooldown applies (`foundSecedeCooldownDays`).
5. A player belongs to exactly one organization. A member who disagrees cannot stay
   in the parent; their only option is `Leave`, after which they have no faction. Say
   so on the vote page before they vote.

**B. A group leaves a faction that has no sub-factions.** At least 2 players (DESIGN
16.1). One member starts "Break away"; others who want to come press the same button
within `breakawayHours`. No majority vote. The starter places the named Nexus (the
existing founding by placement path, B6) outside every keep's protection and pays the
diamonds; those who joined move into the new Lordship. A lapse changes nothing.

Check and report: whether anything bars a just-seceded org from planting a camp
against its former parent at once, and that the war banner's 256 cobblestone must
come from the new org's own treasury.

### R5. The book says what is true (the most important part)

- Join page: state plainly that members cannot vote the ruler out; they can leave or
  break away. Remove "changing or ending the charter".
- Freedoms page for a member says the same and shows one break away button. Its label
  is a joke that still reads as plain English: "I didn't vote for you" (Monty Python),
  with the line under it "Break away from this faction." The page it opens is titled
  "Break away". The owner may swap the joke later, so keep it in one lang key. The
  button opens a short page listing who comes along, the cost (diamonds, or none if
  the group owns a keep), the time limit, and the cooldown, then a confirm dialog.
- "Who decides" page: a decider sees only the changes their office may make (a short
  choice list, 2 to 5 seats, not one button per combination). A non-decider sees one
  line saying who can change it and how.
- Keep every page short. Text in lang files; `RegistryTest` requires the `motion.*`
  lines for any new motion.

## Build order

1. R1 and R2: change `SetSeats` reserved, route the back doors, remove the members'
   override; update the tests added in 25d7e4ef (SeatsTest and the book tests) and the
   Commune cases.
2. R5 text for the pages that already exist.
3. R4 A in core: a `Secede` action and a vote kind, `breakawayHours` knob, the
   Chronicle lines, tests (passes, fails on abstain, lapses, owns a keep, cooldown).
4. R4 B and the "Break away" pages; a gametest that breaks away and founds a keep.
5. DESIGN section 16.1 and the section 23 rows (already updated by the owner session),
   Builder's notes, then stop and report the commit and counts.

## Out of scope (decided)

A real split of a sub-faction into two, a permanent castle claim outcome, an unrest
valve (D3), and handling dead accounts.
