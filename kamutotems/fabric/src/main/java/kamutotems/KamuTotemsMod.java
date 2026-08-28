package kamutotems;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.CompoundTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import thingy.api.VirtualTag;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Entrypoint. Loads config, then registers the three hosts.
 *
 * <p>Each host owns its own events and its own command subtree and exposes
 * exactly {@code register()} and {@code registerCommands(...)}. Nothing else
 * crosses a host boundary, which is what let three of these be written in
 * parallel (PLAN.md section 3.8).
 */
public final class KamuTotemsMod implements ModInitializer {

    public static final String MOD_ID = "kamutotems";
    public static final Logger LOG = LoggerFactory.getLogger("Kamu Totems");

    @Override
    public void onInitialize() {
        // One outer custom_data key, "kamutotems", shared by four item kinds
        // (Totem, QuestScroll, BossStone, Sigil), each identified by its own
        // inner field rather than a common id field (Thingy PLAN.md Phase 5).
        // Predicates mirror what Totem.is / QuestScroll.isScroll /
        // BossStone.isBossStone / Sigil.isSigil checked directly before this
        // migration, unchanged.
        Map<String, Predicate<CompoundTag>> markers = new LinkedHashMap<>();
        markers.put("totem", inner -> inner.getBooleanOr("totem", false));
        markers.put("quest_scroll", inner -> !inner.getStringOr("quest_scroll", "").isBlank());
        markers.put("boss_stone", inner -> inner.getIntOr("boss_stone", 0) > 0);
        markers.put("sigil", inner -> inner.getIntOr("sigil", 0) > 0);
        VirtualTag.register(KamuTag.KEY, VirtualTag.multiMarker(markers));

        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        KamuTotemsConfig.load(configDir);
        Persist.init(configDir);
        // Catalog fails CLOSED, reactions fail OPEN -- see KamuData.
        KamuData.load(configDir);

        TotemHost.register();
        BossHost.register();

        // The quest host replaces `dailyquests`, and BOTH register /daily.
        // Brigadier MERGES a duplicate root rather than rejecting it, so with
        // both present /daily, /daily turnin and /daily top would silently
        // resolve to whichever mod loaded second -- no crash, no log line, just
        // the wrong quest system answering.
        //
        // The guard is a runtime check for that mod, not a switched-off default:
        // a host that ships disabled is a host nobody tests, and the collision
        // does not exist in a dev server or on any install that has already
        // retired `dailyquests`.
        //
        // TEMPORARY. Delete this whole block, and the config key, once
        // `dailyquests` is retired for good (SPEC section 20 phase 3). It is the
        // only place in this mod that names another mod, and it earns that only
        // because it is a conflict guard rather than a feature.
        boolean questEnabled = KamuTotemsConfig.b("quest", "enabled", true);
        if (questEnabled && FabricLoader.getInstance().isModLoaded("dailyquests")) {
            questEnabled = false;
            LOG.warn("The 'dailyquests' mod is installed, so the Kamu Totems quest host is "
                    + "standing down -- both register /daily and running both would silently "
                    + "give players two streaks and an unpredictable /daily. Remove "
                    + "'dailyquests' to switch the daily loop over.");
        }

        final boolean questActive = questEnabled;
        if (questActive) {
            QuestHost.register();
            LOG.info("Quest host enabled: 3 daily segments, streak with weekly grace.");
        } else {
            LOG.info("Quest host is off. The daily loop is served by 'dailyquests'.");
        }

        // The station and the assigned-quest API are unconditional.
        //
        // Assigned quests deliberately do NOT ride on `questActive`. That flag
        // exists solely to avoid a /daily command collision with the older
        // `dailyquests` mod; assigned quests register no colliding command and
        // are a different track entirely (PLAN_V2 section 1). Gating them on the
        // daily chain would mean installing `dailyquests` silently killed the
        // outward API other mods depend on -- which is exactly the kind of
        // action-at-a-distance this suite's decoupling exists to prevent.
        Station.register();
        KamuForge.register();
        AssignedQuestHost.register();

        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> {
            TotemHost.registerCommands(dispatcher);
            BossHost.registerCommands(dispatcher);
            Station.registerCommands(dispatcher);
            AssignedQuestHost.registerCommands(dispatcher);
            if (questActive) {
                QuestHost.registerCommands(dispatcher);
            }
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                LOG.info("Kamu Totems ready: 15 kamu, 6 reactions, 5 hosts."));

        LOG.info("Kamu Totems initialised.");
    }
}
