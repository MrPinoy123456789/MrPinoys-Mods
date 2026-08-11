# Item names — locked

Noun phrases, in Ellie's voice, saying what the thing is. These are settled; treat
changes as a deliberate decision rather than a tweak.

| id | Name | Flavour line |
|---|---|---|
| `flying_boots` | Up Up And Bye Boots | *byeeeee* |
| `pocket_workbench` | Pocket Crafter | *whos on the crafts* |
| `pocket_enderchest` | Messy Chest | *dont mind the mess* |
| `pocket_anvil` | Fixing Sweetie | *lord have mercy, again??* |
| `pocket_grindstone` | Take-Backsies Stone | *nvm i changed my mind* |
| `pocket_stonecutter` | Chop Chop Cutter | *chop chop perioood* |
| `pocket_loom` | Cutie Loom | *im so sick of rainbow everything* |
| `pocket_disenchanter` | Soul Grinder | *give it here, ill save the good part* |
| `pocket_smelter` | Melty Pocket | *ugh fine ill recycle your junk* |
| `boomerang_pet_ball` | Boomerang Pet Ball | *go fetch! ...both of you* |

Every one ends on the noun it is — boots, crafter, chest, stone, cutter, loom — so
it reads as a thing in your hand rather than an instruction. "Fixing Sweetie" is
the one that leans on voice over category; the anvil texture and the flavour line
carry it.

Ids are unchanged and stay that way. Quest JSON and `/wondrous give` reference them.

## If a name changes later

Name and lore are baked into a stack when it's created, so items already handed out
keep their old name until replaced. They keep working — the tag is the id, not the
name — but a player holding an old pair sees the old text. Re-issue with
`/wondrous give` if that matters.
