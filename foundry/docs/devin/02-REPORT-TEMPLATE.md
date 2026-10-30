# Report template

Copy to `docs/devin/reports/<task id>.md` on your branch.

## Task and branch
Task id, branch name, base branch and sha, branches you stacked on.

## Numbers
`coreTest` check count before and after. Gametest count before and after. `build` result. Quote real output lines.

## What changed
Files added, changed and removed, grouped by purpose. Mention any test you deleted or shrank and why (there should be none).

## Decisions you made
Anything the task did not dictate. If it touches the open decisions or the pinned versions, say you stopped instead.

## Verified and not verified
Two lists. Verified means a test or a run you performed. Anything needing a vanilla client, a live server or the
owner goes under "not verified", with the exact steps for the owner to check it.

## APIs used
Each Polymer or Minecraft call you relied on, with the `javap` or source evidence you used to confirm it exists in
26.2 and 0.17.5.

## Risks and expected merge conflicts
Files that other tasks also touch (`ItemDef`, `ItemParser`, `Validator`, `CoreSuite`, `FoundryMod`).

## Handoff
What the next task should know. No instructions to deploy anything.
