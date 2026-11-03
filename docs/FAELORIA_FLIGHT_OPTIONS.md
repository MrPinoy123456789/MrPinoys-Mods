# Faeloria flight: options (2026-11-02)

**Decision (2026-11-02): the owner chose a fourth option, Wing Dash, and it is built on `feature/fae-wing-dash`.** A
jump in the air beats the wings toward where the Fae looks (about 4 blocks of climb looking up, about 6 blocks of
travel looking level), with a slow fall between beats; 3 charges, all back on landing and one every 1.5 s in the air,
so a Fae who paces the beats stays up for good, at about half a Fairy's top speed. Bursts stop climbing 12 blocks over
the ground below, and a hit from a mob, player or projectile costs a charge and drops them for a second. Numbers are
`wing_dash` in `race_traits.json`; see docs/KINGDOM_SERVER.md. Options A to C below are kept for the record.

Open: whether Fae (like Fairies) flying over keep walls is fine for Charter wars.

The owner wants Faeloria (Fae) flight to be very different from the Everdelle Fairies, still feel like real flying,
and stay roughly balanced against them. Nothing here is built. Pick one (or ask for changes) and it gets built on a
feature branch.

## What each race has today

| | Everdelle Fairies | Faeloria Fae |
|---|---|---|
| Flight | Glide always (no chest item); takeoff leap (sneak and jump, about 7 blocks up, 5 s cooldown); 3 wingbeats while gliding (jump), refill on the ground or one per 10 s in the air; at most 1.2 blocks a tick | Leaf wing double jump: press jump again in the air, once per time off the ground, 2.25 s cooldown, a little forward carry |
| Falling | Featherlight: no fall damage | Half fall damage |
| Other | Glimmer sense (gemstones) | Night Vision, herb sense, Glamour on the staff |

The Fairy is a traveller: long, fast, flat distance. Whatever the Fae get should not compete on distance.

## Controls that work everywhere

Bedrock (controller and touch) and Java share only these: jump (press and hold), sneak, sprint, movement and where you
look. The server already reads a held jump from Bedrock players (the double jump uses it), so "hold jump" is safe; a
second key or a mouse button is not. Every option below uses jump and sneak only.

## Option A: Hover (recommended)

**Feel:** a dragonfly or hummingbird. After a jump (or the double jump), **hold jump** in the air and the leaf wings
blur: the Fae hangs in place, sinking very slowly, and can drift at a walk in any direction. Let go and they drop.

- **Limit:** a sap meter of 3 seconds of hover (shown on the action bar as leaves, the same place the Fairy beats show).
  It refills fully after 1 s on the ground, or slowly (1 s of hover per 8 s) while standing in leaves or on grass,
  moss or flowers.
- **Cannot climb:** hover only holds height (sinks 0.04 blocks a tick). Height still comes from the double jump, so the
  most a Fae can reach is about 3 blocks above the ground, held for 3 seconds.
- **Drift:** at most 0.15 blocks a tick sideways (walking pace), against the Fairy's 1.2.
- **Fair in a fight:** taking damage ends the hover at once, and Bramble Burst cast while hovering still takes its full
  second. Hovering over a gap to cast is the point; hovering out of reach of swords for ever is not possible (3 s).
- **Why it is real flight:** the Fae holds still in the air by beating its wings, which is the one thing a glider can
  never do.
- **Balance:** the Fairy covers distance; the Fae owns the vertical moment: crossing a ravine, stopping a fall dead,
  dodging a charge, reaching a ledge, picking a herb off a cliff. Half fall damage stays: a Fae who runs out of sap
  still lands softly.

## Option B: Canopy Bounds

**Feel:** a squirrel or a forest spirit. The double jump becomes up to **three air jumps** in a row, each a long
forward bound (about 5 blocks), but only **near plants**: a bound refills when the Fae touches leaves, grass, flowers,
vines or moss. In a forest a Fae can cross the treetops without landing; on stone or sand they have one jump, as today.

- **Controls:** jump again in the air, as now.
- **Limit:** 3 bounds, refilled by touching plants or landing; a 1 s cooldown between bounds.
- **Balance:** strong at home (Faeloria is woodland) and ordinary elsewhere, so it rewards the race's land rather than
  competing with the Fairy everywhere. Weakness: it is less "flying" than bounding, and in a desert it adds nothing.

## Option C: Petal Step

**Feel:** a blink. In the air, **sneak and jump** turns the Fae into a swirl of petals that rushes about 8 blocks the
way they look (through the air, never through blocks) and reforms, briefly hard to see.

- **Limit:** 8 s cooldown, one per time in the air.
- **Balance:** a strong escape and gap crosser with no hang time. Weakness: closer to a dash or teleport than to flight,
  and a sneak press while airborne is fiddly on touch.

## Recommendation

**Option A, Hover.** It is the clearest opposite of the Fairy (stillness against speed), it is true wing flight, it
uses one control every player already has (hold jump), and it keeps the Fae's existing double jump as the way up. It
also pairs with Glamour and herb sense: hang by a cliff, cast, pick the flower.

Numbers to tune after a playtest: sap 60 ticks, ground refill after 20 ticks, sink 0.04, drift 0.15, damage ends
the hover. All would be knobs in `race_traits.json` under a new `hover` entry for `race:faeloria`.

## Open questions

1. Should hover also refill in the air when the Fae passes through leaves (more forest play), or only on the ground?
2. Should a hovering Fae be shown to others with a sound and a ring of falling petals (easy to spot, fairer in a fight)?
3. Keep the double jump as it is, or shorten its cooldown a little since hover adds a reason to use it more?

Unverified until a Bedrock client tries it: that a held jump is reported every tick while airborne on touch screens.
