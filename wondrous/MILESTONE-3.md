# Milestone 3 — wiring the siblings

Two edits to Wondrous first, then dailyquests.

## 1. Wondrous: move `giveOrDrop` into the API

Drop `WondrousGive.java` into `api/src/main/java/wondrous/api/`.

Then in `fabric/src/main/java/wondrous/WondrousCommands.java`, replace the body of
`giveOrDrop` with a delegate so there's one implementation:

```java
    public static void giveOrDrop(ServerPlayer player, ItemStack stack) {
        wondrous.api.WondrousGive.giveOrDrop(player, stack);
    }
```

The `ChatFormatting` import in that file may go unused afterwards — harmless either
way, javac won't complain.

## 2. Wondrous: publish

```bash
cd /a/wondrous
./gradlew build
./gradlew :api:publishToMavenLocal
```

Confirm it landed:

```bash
ls ~/.m2/repository/wondrous/wondrous-api/0.1.0/
```

You want `wondrous-api-0.1.0.jar` and a `.pom`. If `~/.m2` doesn't exist at all,
the publish task didn't run — check for `:api:publishToMavenLocal` in the task list
rather than a silent skip.

**Re-publish after any change to the `api` module.** The mod jar and the published
api jar are separate artifacts; dailyquests compiles against the second and runs
against the first, and nothing warns you when they drift.

## 3. dailyquests: build wiring

In `fabric/build.gradle.kts`:

```kotlin
repositories {
    maven("https://maven.nucleoid.xyz/") { name = "Nucleoid" }
    mavenLocal()
}

dependencies {
    // ... existing ...

    // Compile-only: the wondrous jar supplies these classes at runtime. When it
    // isn't installed, WondrousItems.get() returns empty and the quest still runs.
    modCompileOnly("wondrous:wondrous-api:0.1.0")
}
```

`modCompileOnly`, **not** `modImplementation`. The wrong one here makes dailyquests
refuse to start without Wondrous installed, which defeats the whole design.

In `fabric.mod.json`, alongside `depends`:

```json
  "suggests": {
    "wondrous": "*"
  }
```

## 4. dailyquests: the reward helper

New file, `src/main/java/dailyquests/WondrousReward.java`:

```java
package dailyquests;

import net.minecraft.server.level.ServerPlayer;
import wondrous.api.WondrousGive;
import wondrous.api.WondrousItems;

/**
 * Hands out a Wondrous Item, if that mod is installed.
 *
 * <p>Every reference to wondrous.api lives in this one class, so if the dependency
 * ever needs removing, one file goes.
 */
public final class WondrousReward {

    private WondrousReward() {}

    /** True if Wondrous Items is installed and initialised. */
    public static boolean available() {
        return WondrousItems.get().isPresent();
    }

    /**
     * Grants the item to the player. Returns false and logs if the mod is missing
     * or the id is unknown -- never throws, never fails the turn-in.
     */
    public static boolean grant(ServerPlayer player, String id, String questId) {
        var items = WondrousItems.get();
        if (items.isEmpty()) {
            DailyQuestsMod.LOG.warn(
                    "Quest {} wants wondrous item '{}' but Wondrous Items is not installed",
                    questId, id);
            return false;
        }

        var item = items.get().byId(id);
        if (item.isEmpty()) {
            DailyQuestsMod.LOG.warn("Quest {} names unknown wondrous item '{}'", questId, id);
            return false;
        }

        WondrousGive.giveOrDrop(player, item.get().createStack());
        return true;
    }
}
```

Note the class loads fine when Wondrous is absent — `WondrousItems.get()` is a
static on an interface whose classes are simply not present, so the JVM only fails
if something *calls* into it, and `get()` is written to return empty. If you see a
`NoClassDefFoundError` here it means `modCompileOnly` was written as
`modImplementation` somewhere.

## 5. dailyquests: the two edits I can't write blind

These touch `Quests.java` and `TurnIn.java`, which I don't have. Both are small:

**`Quests.Quest`** gains one optional field, `wondrousReward`, defaulting to null.
Wherever the quest record is defined and wherever its JSON is parsed.

**`TurnIn`**, at the point a turn-in succeeds and before it returns:

```java
        if (quest.wondrousReward() != null) {
            WondrousReward.grant(player, quest.wondrousReward(), quest.id());
        }
```

Paste `Quests.java` and `TurnIn.java` and I'll write both properly rather than
guessing at the record shape.

## 6. Test gate

Run the server **twice**:

**With `wondrous-0.1.0.jar` in `mods/`:**
- `/daily` shows the riddle
- turn in a quest carrying `"wondrousReward": "pocket_workbench"`
- the item arrives, right-clicks open, name reads "On The Crafts"

**With the wondrous jar removed:**
- dailyquests still starts — no crash, no missing-dependency refusal
- `/daily` still works
- the same turn-in succeeds, streak still increments
- the log carries one warning naming the quest and the missing item

The second run is the whole justification for `modCompileOnly`. Verify it, don't
assume it.
