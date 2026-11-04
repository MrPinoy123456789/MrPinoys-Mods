# Audit baseline (2026-11-04)

Code audited: `master` at 478cce26, read from the `master-merge` worktree. Reports only; no code, config, branch or server was changed. Coin values in these reports are Gold Sovereigns (1 Sovereign = 64 Marks; one gold ingot strikes 10 Sovereigns).

## Test suites, one Gradle build at a time, heap 1200m (Pocket Dungeons 2200m), temp on A:

| Suite | Result |
|---|---|
| Charter core (`:core:coreTest`) | 2681 passed, 0 failed |
| Charter fabric (`:fabric:fabricTest`) | 569 passed, 0 failed |
| Charter gametests (`runGameTest`, 45 classes) | all 244 required tests passed, 98 s |
| Charter Currency core (`:core:coreTest`) | 416 passed, 0 failed |
| Charter Currency gametests | none exist |
| Fortune core (`:core:coreTest`) | 134 passed, 0 failed |
| Fortune gametests | all 9 required tests passed |
| Foundry core (`coreTest`) | 597 checks OK |
| Foundry gametests | all 116 required tests passed |
| Excellent Enchants gametests | all 18 required tests passed |
| Instant Restock gametests | all 3 required tests passed |
| GoldGate | no tests (one loot table) |
| Lemon (`lemonSpeechTest`, `isolationTest`) | both passed |
| Pocket Dungeons (`bash tools/ship.sh --quick --no-dist`) | SUITE GREEN, all 458 required tests passed, 5 min 1 s |
| Node (`node --test tools/playtest/*.test.mjs`) | 3 of 3 passed |

Everything passes. The 2026-11-02 handoff's open item "the full PD suite on merged master has not been rerun" is now closed by this run, and the deep_quarry Blackout NullPointerException it lists as unfixed is fixed on master (commit 8e19e296, covered by `WildRunGameTest.everyOfferingStampsUnderBlackout`). Whether the jar on the server carries that fix is unverified.

## Facts established during the run

- Builds are not reproducible offline: Charter and Fortune depend on `org.geysermc.floodgate:api:2.2.4-SNAPSHOT` and Foundry on `eu.pb4:trinkets:4.2.1+26.3`; `--offline` fails at configuration until the network fetch has run once. A SNAPSHOT can change under a rebuild.
- Local `master` (478cce26) is 45 commits ahead of `origin/master` (38d01c35). The source of what is built is not backed up on GitHub.
- 81 non-merge commits landed on master after the last deploy the handoff records (86f63d56, 2026-11-02): Fortune, the Bank and Mint station, Currency analytics, the 10 Sovereign ingot, melee weapon enchanting, the Oathblade katana rework, Human slide, Merfolk water passives, rank 1 recipes and more. Which are live on d212ecae is unverified; the deploy script's version check reads only Charter's number.
- 107 local branches and 96 worktrees. Still ahead of master: `feature/wild-lodestones-and-currency` (8, an old integration branch), `feature/charter-world-hints` (4), `feature/hints-and-primer` (3), `feature/chronicle-design` (3), `feature/fortune-daily-spin-build` (1).
- Every gametest class present in Charter, Fortune, Foundry, Excellent Enchants, Instant Restock, Pocket Dungeons and Lemon is registered in its gametest `fabric.mod.json`. The registration trap in the project memory does not apply today.
- The machine itself is a risk: 27 `hs_err_pid*.log` files in the workspace root all say the JVM ran out of memory; 16 GB RAM with a Minecraft client and one gametest JVM leaves about 500 MB free. Build one thing at a time.

## Method

Seven reviewers read the code in parallel (Charter core, Charter fabric, economy, Foundry and small mods, Pocket Dungeons wild mode and Lemon, operations and cross-mod, gameplay and Bedrock UX), each citing file and line. The coordinator ran the suites, spot checked every high and critical finding at its cited line (all held), and wrote the deliverables in this folder: `SUMMARY.md`, `FINDINGS.md`, `DO_NOW.md`, `ROADMAP.md`, `UX_BEDROCK_JAVA.md`, `ECONOMY.md`, `BLIND_SPOTS.md`, `TEST_GAPS.md`. Findings nobody could confirm from code or a test run are marked UNVERIFIED.
