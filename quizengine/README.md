# Quiz Engine

Server-side trivia and voting rounds for Minecraft Fabric 26.2. Vanilla clients
need nothing installed.

## Layout

    engine/   Pure Java. Round lifecycle and scoring. No Minecraft on its classpath.
    fabric/   The mod. Scheduling, chat presentation, JSON persistence.

The split is enforced by the build, not by discipline: a stray Minecraft import in
`engine/` fails compilation. That is what keeps the engine reusable by any other
orchestrator later.

## Run the engine tests (no Gradle, no network)

    javac -d build $(find engine/src -name '*.java')
    java -cp build quizengine.EngineTest

Expect `68 passed, 0 failed`. JDK 21 is enough for this standalone run;
building the mod needs JDK 25.

## Build the mod

Requires **JDK 25** — Minecraft 26.2 targets Java 25, not 21. The Gradle wrapper
is included, so Gradle itself does not need to be installed.

    ./gradlew build

Versions in `gradle.properties` came from the FabricMC example mod's 26.2 branch.
Re-check at https://fabricmc.net/develop when updating; it is the only file that
needs touching.

The jar lands in `fabric/build/libs/`. Drop it in your server's mods folder
alongside Fabric API. The engine is shaded in, so it's a single file.

Note also that Minecraft 26.2 is unobfuscated, so there is no `mappings`
dependency and Yarn is not used. All Minecraft references use Mojang's official
names (`ServerPlayer`, `Component`, `Commands`), not the Yarn names in older
tutorials.

## Try it

    /quiz start trivia
    /quiz answer 3
    /quiz advance        settle immediately instead of waiting out the timer
    /quiz top

    /quiz start quiplash
    /quiz submit Wet Regrets     players write their own answers
    /quiz advance                close submissions
    /quiz advance                open voting, ballot is the written answers
    /quiz vote 1                 or click one
    /quiz advance                settle

Config generates itself at `config/quizengine/` on first boot: `trivia.json`,
`prompts.json`, `scoring.json`, `timings.json`. Two sample questions and two
sample prompts are seeded so there is something to play immediately.

Quiplash submissions default to a two-hour window. Set `quiplashSubmitSeconds`
low in `timings.json` while testing.

## Known loose ends

- `Orchestrator.greet()` handles late joiners and **is** wired to `ServerPlayConnectionEvents.JOIN` in `QuizMod.java`; a player joining during `SUBMITTING` sees the prompt.
- Admin commands are console-only. 26.2 replaced integer permission levels with
  `PermissionSet`/`Permission`; restoring op access needs those constants.
- SGUI is commented out in `fabric/build.gradle.kts`. Confirm a 26.2 build exists
  on the Nucleoid maven before Milestone 4.
- Skipped rounds pay nobody, including participation. One line in
  `Engine.settleQuiplash` if you want that changed.
