# Deploy and prepare the playtest (prompt for Devin or any outside engineer)

Fill in COMMIT and TEST_COUNT. Paste everything below the line.

---

You are deploying the latest Pocket Dungeons build to the TEST server and getting it ready for a play test. Do not change game code.

REPO AND BUILD
- Repo: https://github.com/MrPinoy123456789/MrPinoys-Mods (the mod is in the pocketdungeons/ folder). Use branch master at commit COMMIT. Java 25, Gradle. On Linux use ./gradlew (tools/ship.sh is Windows only). Read tools/playtest/README.md and .claude/skills/deploy-test-server/SKILL.md first; they are the rules.
- House rule: no em dashes and no spaced double hyphens in anything you write.
- Commit only the brief in step 6, authored as MrPinoy123456789 (git config user.name MrPinoy123456789; user.email 334378049+MrPinoy123456789@users.noreply.github.com). Never push to master; push a branch devin/playtest-brief if you want it reviewed.

HARD SAFETY RULES
1. The only server you may touch is the Kinetic test server 714a0605. NEVER touch 365f9682. The owner will give you ../tools/playtest/kinetic.env (panel URL, KINETIC_SERVER_ID=714a0605, API key) as a secret. It is gitignored; never commit or print the key. Every start, stop and upload prints which server it acts on: read that line, and if it is not 714a0605, stop and tell the owner.
2. Run "node ../tools/playtest/pdserver.mjs status" first. If any player is online, STOP and ask the owner before restarting.
3. Build both jars fresh in the same command chain as the deploy: ./gradlew dist --console=plain from pocketdungeons, then ../lemon/gradlew -p ../lemon build dist --console=plain. Confirm exactly one MrPinoys_Pocket_Dungeons-*.jar and one MrPinoys_Lemon-*.jar in dist/, each newer than the checkout. Ask before deleting stale artifacts. Deploy never builds and uploads both jars.
4. Always deploy with --restart. Never kill the server process.
5. Before deploying, back up: download or copy the current jar from the server's /mods to a dated backup name, and if the panel offers a world or data backup, take one. If this build changes save data, a rollback path matters. Write the rollback steps in your report.

STEPS
1. Verify the tree: ./gradlew test runGameTest --console=plain must end "All TEST_COUNT required tests passed" and BUILD SUCCESSFUL. If not, stop and report; do not deploy a red build.
2. Back up (rule 5), check status (rule 2).
3. Deploy: node ../tools/playtest/pdserver.mjs deploy --restart. Wait for Ready.
4. Verify: status says UP; read the log tail; look for "Loaded N cube recipes", "All N core loot tables verified present", and no ERROR, Exception, Failed to load or codec lines. Then node ../tools/playtest/pdserver.mjs cmd dungeon admin validate must answer "Pack validation passed: no findings." Confirm the server's existing pocketdungeons.json loads with any new knobs defaulting.
5. Smoke check with the admin commands in the README (without players online you can only check that commands answer): cmd list, cmd dungeon admin validate. Do not create or delete player data.
6. Write pocketdungeons/docs/playtests/next-playtest-brief.md: the commit and jar name, what is new (read the latest sections in docs/DECISIONS.md), a table mapping each new feature to its live check in docs/playtests/LIVE_CHECKS.md, the quickest route to see each one (which dungeon, which compass, which admin command sets compass or haul; read the README and tools/lemon/GUIDE.md), the known risks and tuning questions, and how the play session will be recorded (.claude/skills/playtest/SKILL.md). Keep it short and concrete.

REPORT BACK
One message: commit sha, jar name and size, the server id the tool printed, backup location and rollback steps, status output, validate result, any log warnings (verbatim), anything you could not check, and the path of the brief. If anything fails, report the exact output and stop; do not try other hosting routes or hotfix code.
