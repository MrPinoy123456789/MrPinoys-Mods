package pocketdungeons;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/**
 * The zone rules hook's check for {@code dungeonIntegrationTest}, which boots
 * a real dedicated server with the bundled datapack. Lives in this package so
 * it can read the package-private manifests; the entrypoint in
 * {@code pocketdungeons.gametest} only calls {@link #run}.
 *
 * <p>Builds a content snapshot from the live resource manager (without
 * publishing it) and checks that no bundled theme was rejected over its
 * {@code rules} block and that the Endless Mine's escalation arrives from its
 * own theme file, the path {@code ZoneRulesTest} can only approximate from
 * the classpath.
 */
public final class ZoneRulesIntegrationCheck {

    private ZoneRulesIntegrationCheck() {}

    /** @return one line per failure; empty when the check passes */
    public static List<String> run(MinecraftServer server) {
        List<String> failures = new ArrayList<>();
        try {
            ContentSnapshot snapshot = ContentSnapshot.build(server);
            for (String rejection : snapshot.themes().rejections()) {
                if (rejection.contains("rules")) {
                    failures.add("a bundled theme was rejected over its rules block: " + rejection);
                }
            }
            ThemeManifest.Entry mine = snapshot.themes().byId(EndlessMineRules.MINE_THEME_ID);
            if (mine == null || mine.meta().rules == null || !"materials".equals(mine.meta().rules.lootRole())) {
                failures.add("the Endless Mine theme should carry loot_role materials in its rules block");
            }
            ThemeManifest.Entry deep = snapshot.themes().byId("pocketdungeons:endless_mine_deep");
            if (deep == null || deep.meta().rules == null || !"materials".equals(deep.meta().rules.lootRole())) {
                failures.add("the Deep Mine theme should carry loot_role materials in its rules block");
            }
        } catch (RuntimeException e) {
            failures.add("building a content snapshot for the zone rules check threw: " + e);
        }
        return failures;
    }
}
