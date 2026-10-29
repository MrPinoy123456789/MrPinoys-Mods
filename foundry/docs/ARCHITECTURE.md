# Foundry architecture

## Principle

Configuration defines content; reusable Java defines behaviour. The runtime holds no per-item or per-furniture code.
A new sword is a JSON file. A new kind of behaviour is one registered mechanic type (task F3).

## Layers

```
config/foundry/content/**.json         authoritative, human-readable, versioned (schema_version)
        |
        v
foundry.core.ContentLoader             pure JDK: read, parse, validate, report. Never throws on bad content
        |   ContentSet (immutable)      items + problems, each problem has a content id, field, message, fix
        v
foundry.mc.FoundryMod                  startup: load, log problems, register valid definitions
        |
        +--> FoundryItems / FoundryItem   one Polymer item class for every definition
        +--> Polymer resource pack        Polymer builds the pack; Foundry contributes assets (task F1)
        +--> FoundryDatapack              generated vanilla recipe JSON under config/foundry/datapack,
                                          served as an always-enabled builtin pack (DECISIONS.md D8)
        +--> FoundryCommands              help, status, validate, give, pack status/build
```

Rule: `foundry.core` may not import `net.minecraft`, `eu.pb4` or `net.fabricmc`. Environment facts it needs (does a
vanilla item exist, is an asset present) are passed in as predicates. That is what keeps the validator testable with
plain `main()` tests and reusable by a future editor.

## What can change at runtime

| Content | Reload without restart | Why |
|---|---|---|
| Item registration (new id, changed base item, durability, stack size) | No | Registry entries cannot be removed or re-registered safely while the server runs |
| Item display name, lore, glint, texture, model | No | Baked into registered properties and the built pack; `/foundry reload` lists them as restart needed |
| Recipes | Yes | Written as vanilla JSON into the generated datapack; `/foundry reload` runs a data reload after the swap |
| Mechanics (parameters, cooldown, chance, actions) | Yes | Read from the live ContentSet at trigger time; `/foundry reload` swaps it |
| Resource pack contents | Yes, players must re-download | Pack is rebuilt and re-sent |

`/foundry validate` re-reads files and reports; it never registers. `/foundry reload` swaps the candidate set in only when it has no errors and lists what needs a restart. If it finds new items it says a restart is
needed. A failed load never replaces a working ContentSet.

## Stable identity

Ids are `namespace:path`, lowercase. Items already in inventories store the registry id, so renaming an id orphans
them. Renames are an explicit operation, `/foundry rename <old> <new>`, never an edit: it rewrites the definition
files and every quoted reference, backs up each touched file, and refuses collisions. A vanished id is recorded in
`config/foundry/orphans.json` with the file it came from; see DECISIONS.md D5. Note the decode experiment there: a
held stack of a removed id is dropped by the strict codec, so the ledger cannot bring it back.

## Not yet designed

Furniture entity model, custom blocks, armor rendering, glyph fonts. Each needs a decision record before code
(DECISIONS.md D3, D4). Do not start them without it.
