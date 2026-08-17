package smalltalk.social;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.npc.villager.Villager;
import smalltalk.SmallTalkConfig;
import smalltalk.SmallTalkMod;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

/**
 * Familiarity lives on the villager entity itself, keyed by player UUID --
 * same trick as Rehome's follower attachment, no world save data of its own
 * (SPEC.md section 7). Decay is computed lazily, on read, rather than ticked;
 * there is no passive loop anywhere in this mod (SPEC.md section 0).
 */
public final class FamiliarityAttachment {

    /** One in-game day, the vanilla daylight cycle length. */
    private static final long TICKS_PER_DAY = 24000L;
    private static final long TICKS_PER_WEEK = TICKS_PER_DAY * 7;

    private static final Codec<UUID> UUID_KEY_CODEC = Codec.STRING.xmap(UUID::fromString, UUID::toString);

    public static final AttachmentType<Map<UUID, FamiliarityEntry>> FAMILIARITY = AttachmentRegistry.createPersistent(
            Identifier.fromNamespaceAndPath(SmallTalkMod.MOD_ID, "familiarity"),
            Codec.unboundedMap(UUID_KEY_CODEC, FamiliarityEntry.CODEC));

    private FamiliarityAttachment() {}

    public static Map<UUID, FamiliarityEntry> allOf(Villager villager) {
        Map<UUID, FamiliarityEntry> attached = villager.getAttached(FAMILIARITY);
        return attached == null ? Map.of() : attached;
    }

    /** Decay-adjusted standing, without writing anything back. */
    public static FamiliarityEntry current(Villager villager, UUID player, long currentTick) {
        FamiliarityEntry stored = allOf(villager).get(player);
        return stored == null ? FamiliarityEntry.initial(currentTick) : decayed(stored, currentTick);
    }

    public static boolean sameDay(long a, long b) {
        return Math.floorDiv(a, TICKS_PER_DAY) == Math.floorDiv(b, TICKS_PER_DAY);
    }

    /** Whether the once-per-day gift cooldown (SPEC.md section 6.2, {@code giftCooldownTicks}) is still active. */
    public static boolean giftOnCooldown(FamiliarityEntry entry, long currentTick) {
        return entry.lastGiftTick() != FamiliarityEntry.NEVER
                && currentTick - entry.lastGiftTick() < SmallTalkConfig.giftCooldownTicks();
    }

    /**
     * Applies decay, then {@code delta}, clamped to [0, 100]; optionally
     * records a memory; always advances the decay anchor to {@code currentTick}.
     * Evicts the least-recently-interacted player if this pushes past
     * {@code maxTrackedPlayersPerVillager}.
     */
    public static FamiliarityEntry recordInteraction(Villager villager, UUID player, long currentTick,
                                                       int delta, String memoryTag) {
        return recordInteraction(villager, player, currentTick, delta, memoryTag, false);
    }

    /** Same as {@link #recordInteraction}, but also stamps the gift cooldown (SPEC.md section 6.2). */
    public static FamiliarityEntry recordGift(Villager villager, UUID player, long currentTick,
                                                int delta, String memoryTag) {
        return recordInteraction(villager, player, currentTick, delta, memoryTag, true);
    }

    private static FamiliarityEntry recordInteraction(Villager villager, UUID player, long currentTick,
                                                        int delta, String memoryTag, boolean isGift) {
        Map<UUID, FamiliarityEntry> map = new LinkedHashMap<>(allOf(villager));
        FamiliarityEntry existing = map.get(player);
        FamiliarityEntry base = existing == null ? FamiliarityEntry.initial(currentTick) : decayed(existing, currentTick);

        int newScore = Math.clamp(base.score() + delta, 0, 100);
        long giftTick = isGift ? currentTick : base.lastGiftTick();
        FamiliarityEntry updated = new FamiliarityEntry(newScore, currentTick, giftTick, base.memories());
        if (memoryTag != null) {
            updated = updated.withMemory(memoryTag, currentTick);
        }

        map.put(player, updated);
        evictOldest(map, SmallTalkConfig.maxTrackedPlayersPerVillager());
        villager.setAttached(FAMILIARITY, map);
        return updated;
    }

    private static FamiliarityEntry decayed(FamiliarityEntry entry, long currentTick) {
        long elapsedTicks = currentTick - entry.lastInteractionTick();
        if (elapsedTicks <= 0) {
            return entry;
        }
        long weeks = elapsedTicks / TICKS_PER_WEEK;
        if (weeks <= 0) {
            return entry;
        }
        int floor = entry.tier().floor();
        int decayed = Math.max(floor, entry.score() - (int) (weeks * SmallTalkConfig.familiarityDecayPerWeek()));
        if (decayed == entry.score()) {
            return entry;
        }
        return new FamiliarityEntry(decayed, entry.lastInteractionTick(), entry.lastGiftTick(), entry.memories());
    }

    private static void evictOldest(Map<UUID, FamiliarityEntry> map, int cap) {
        while (map.size() > cap) {
            Entry<UUID, FamiliarityEntry> oldest = null;
            for (Entry<UUID, FamiliarityEntry> e : map.entrySet()) {
                if (oldest == null || e.getValue().lastInteractionTick() < oldest.getValue().lastInteractionTick()) {
                    oldest = e;
                }
            }
            if (oldest == null) {
                return;
            }
            map.remove(oldest.getKey());
        }
    }
}
