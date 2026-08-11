package spiritwolves;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.Monster;

import java.util.HashMap;
import java.util.Map;

/**
 * Souls and levels (SPEC.md section 18.1). Every kill by a summoned spirit
 * wolf feeds it souls; total souls (never stored, always summed from kills)
 * determine the wolf's level, which gates verb slots and attunable tiers.
 * Registered as a {@link WolfKill} listener.
 */
public final class Souls {

    /** Cumulative souls required for each level, index 0 unused (level is 1-indexed). */
    private static final long[] LEVEL_THRESHOLDS = { 0, 0, 50, 150, 400, 1000 };

    private static final String[] LEVEL_UP_GRANT = {
            null,
            null,
            "verb tier II attunable",
            "a second verb slot opens",
            "verb tier III attunable",
            "a third verb slot opens",
    };

    private static final String[] LEVEL_UP_JOURNAL = {
            null,
            null,
            "Tasted its first fifty souls.",
            "A hundred and fifty souls strong.",
            "Four hundred souls strong.",
            "A thousand souls. An elder spirit.",
    };

    private static final Map<EntityType<?>, Integer> VALUES = new HashMap<>();

    static {
        put(1, EntityTypes.ZOMBIE, EntityTypes.SKELETON, EntityTypes.SPIDER, EntityTypes.CREEPER,
                EntityTypes.DROWNED, EntityTypes.HUSK, EntityTypes.STRAY, EntityTypes.SLIME,
                EntityTypes.SILVERFISH, EntityTypes.PHANTOM);
        put(3, EntityTypes.ENDERMAN, EntityTypes.BLAZE, EntityTypes.MAGMA_CUBE, EntityTypes.WITCH,
                EntityTypes.PIGLIN_BRUTE, EntityTypes.VINDICATOR, EntityTypes.GUARDIAN,
                EntityTypes.SHULKER, EntityTypes.BREEZE);
        put(5, EntityTypes.WITHER_SKELETON, EntityTypes.RAVAGER, EntityTypes.EVOKER,
                EntityTypes.HOGLIN, EntityTypes.GHAST);
        put(10, EntityTypes.ELDER_GUARDIAN);
        put(25, EntityTypes.WARDEN);
        put(50, EntityTypes.WITHER, EntityTypes.ENDER_DRAGON);
    }

    private Souls() {}

    private static void put(int souls, EntityType<?>... types) {
        for (EntityType<?> type : types) {
            VALUES.put(type, souls);
        }
    }

    /** Soul value for a kill of this entity type. Players always grant 0. */
    static int valueOf(Entity entity) {
        Integer explicit = VALUES.get(entity.getType());
        if (explicit != null) {
            return explicit;
        }
        return entity instanceof Monster ? 1 : 0;
    }

    /** The wolf's level, derived from total souls -- never stored separately. */
    static int levelFor(long souls) {
        int level = 1;
        for (int i = 1; i < LEVEL_THRESHOLDS.length; i++) {
            if (souls >= LEVEL_THRESHOLDS[i]) {
                level = i;
            }
        }
        return level;
    }

    /** Verb slots granted at this level (SPEC.md section 18.1). */
    static int slotsFor(int level) {
        if (level >= 5) {
            return 3;
        }
        return level >= 3 ? 2 : 1;
    }

    /** Highest verb tier attunable at this level. */
    static int maxAttunableTierFor(int level) {
        if (level >= 4) {
            return 3;
        }
        return level >= 2 ? 2 : 1;
    }

    /** {@link WolfKill} listener: awards souls and announces level-ups. */
    static void onKill(Wolf wolf, ServerPlayer owner, WolfRecord record, Entity killed) {
        int value = valueOf(killed);
        if (value <= 0) {
            return;
        }

        int levelBefore = levelFor(record.souls);
        record.souls += value;
        int levelAfter = levelFor(record.souls);
        PlayerWolfRegistry.markDirty(owner.getUUID());

        if (levelAfter > levelBefore) {
            for (int level = levelBefore + 1; level <= levelAfter; level++) {
                announceLevelUp(owner, record, level);
            }
        }
    }

    private static void announceLevelUp(ServerPlayer owner, WolfRecord record, int level) {
        String name = record.wolfName != null ? record.wolfName : "Your wolf";
        String grant = level < LEVEL_UP_GRANT.length ? LEVEL_UP_GRANT[level] : null;
        if (grant != null) {
            owner.sendSystemMessage(Component.literal(
                            name + " has grown. Level " + level + " -- " + grant + ".")
                    .withStyle(ChatFormatting.GOLD));
        }
        String journalLine = level < LEVEL_UP_JOURNAL.length ? LEVEL_UP_JOURNAL[level] : null;
        if (journalLine != null) {
            record.addJournalEntry("level" + level, journalLine);
        }
        Chime.levelUp(owner);
    }
}
