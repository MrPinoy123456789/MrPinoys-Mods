# Gold Gate

Server-side Fabric mod, no code. It replaces one vanilla loot table:
`data/minecraft/loot_table/entities/zombified_piglin.json`.

Vanilla already gates the rare gold ingot behind `killed_by_player`. This adds
the same condition to the gold nugget pool, so a zombified piglin that dies to
lava, a fall, suffocation or any other unattended source drops rotten flesh and
nothing else. Somebody has to be there to kill it.

- Tamed wolves count: 26.3 credits the wolf's owner as the killer, which is what
  `killed_by_player` reads.
- Piglin bartering, bastion chests and nether gold ore are untouched.
- Manual kill chambers still work. Penalise unattended or macroed farms by hand.

## Check after a Minecraft update

The shipped table is the vanilla one plus a single `condition` on the nugget pool.
After changing `minecraft_version`, extract the new vanilla table from the merged
jar and diff it; a stale copy would hide any vanilla change.

```bash
./gradlew.bat build
```

The jar lands in `../dist/`.
