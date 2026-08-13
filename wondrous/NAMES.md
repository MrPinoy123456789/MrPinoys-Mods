# Item names — locked

Noun phrases, in Ellie's voice, saying what the thing is. These are settled; treat
changes as a deliberate decision rather than a tweak.

| id | Name | Flavour line |
|---|---|---|
| `flying_boots` | Up Up And Bye Boots | *byeeeee* |
| `pocket_workbench` | Pocket Crafter | *whos on the crafts* |
| `pocket_enderchest` | Messy Chest | *dont mind the mess* |
| `pocket_anvil` | Fixing Sweetie | *lord have mercy, again??* |
| `pocket_disenchanter` | Soul Grinder | *give it here, ill save the good part* |
| `pocket_smelter` | Melty Pocket | *ugh fine ill recycle your junk* |
| `boomerang_pet_ball` | Boomerang Pet Ball | *go fetch! ...both of you* |
| `chuck_it_wand` | Chuck It Wand | *put yourself away* |
| `tidy_up_stick` | Tidy Up Stick | *make it neat* |
| `big_lazy_hoe` | Big Lazy Hoe | *grow faster* |
| `growy_can` | Growy Can | *drink up babes* |
| `lazy_sprinkler` | Lazy Sprinkler | *i got it, go do something else* |
| `smashy_mortar` | Smashy Mortar | *ugh fine, ill break it smaller* |
| `restock_ring` | Never Empty Charm | *i packed spares* |
| `owl_eye_goggles` | Owl Eye Goggles | *whos out there* |
| `fishy_necklace` | Fishy Necklace | *glub glub* |
| `toasty_scarf` | Toasty Scarf | *toasty* |
| `floaty_feet` | Floaty Feet | *float on* |
| `zoomies_boots` | Zoomies Boots | *zoom zoom* |
| `crafting_station` | Left It Out Crafter | *leave it there* |
| `link_wand` | Put It There Wand | *toss* |
| `carry_glove` | Piggyback Glove | *come here you* |

Every one ends on the noun it is — boots, crafter, chest, grinder, pocket, ball —
so it reads as a thing in your hand rather than an instruction. "Fixing Sweetie" is
the one that leans on voice over category; the anvil texture and the flavour line
carry it.

Ids are unchanged and stay that way. Quest JSON and `/wondrous give` reference them.

## Retired

`pocket_grindstone` (Take-Backsies Stone), `pocket_stonecutter` (Chop Chop Cutter)
and `pocket_loom` (Cutie Loom) were cut — the blocks they replace are ones you use
rarely and never far from base, so the pocket versions padded the shop without
saving anyone a trip. **The ids stay retired rather than reused.** Nothing in the
suite referenced them at the time of the cut, but re-pointing a dead id at a
different item is how an old stack in someone's ender chest turns into the wrong
thing.

## If a name changes later

Name and lore are baked into a stack when it's created, so items already handed out
keep their old name until replaced. They keep working — the tag is the id, not the
name — but a player holding an old pair sees the old text. Re-issue with
`/wondrous give` if that matters.
