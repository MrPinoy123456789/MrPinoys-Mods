package smalltalk.social;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * One player's standing with one resident: a 0-100 score, when they last
 * interacted (in game ticks, for decay), when they last gave a gift (for
 * the once-per-day cap in SPEC.md section 6.2), and up to three memories
 * (SPEC.md sections 7-8).
 */
public record FamiliarityEntry(int score, long lastInteractionTick, long lastGiftTick, List<MemoryEntry> memories) {

    public static final int MAX_MEMORIES = 3;
    public static final long NEVER = -1L;

    public static final Codec<FamiliarityEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("score").forGetter(FamiliarityEntry::score),
            Codec.LONG.fieldOf("lastInteractionTick").forGetter(FamiliarityEntry::lastInteractionTick),
            Codec.LONG.optionalFieldOf("lastGiftTick", NEVER).forGetter(FamiliarityEntry::lastGiftTick),
            MemoryEntry.CODEC.listOf().fieldOf("memories").forGetter(FamiliarityEntry::memories)
    ).apply(instance, FamiliarityEntry::new));

    public static FamiliarityEntry initial(long tick) {
        return new FamiliarityEntry(0, tick, NEVER, List.of());
    }

    public FamiliarityTier tier() {
        return FamiliarityTier.of(score);
    }

    public FamiliarityEntry withMemory(String tag, long tick) {
        List<MemoryEntry> updated = new java.util.ArrayList<>(memories);
        updated.add(new MemoryEntry(tag, tick));
        while (updated.size() > MAX_MEMORIES) {
            updated.remove(0);
        }
        return new FamiliarityEntry(score, lastInteractionTick, lastGiftTick, List.copyOf(updated));
    }

    public FamiliarityEntry withGiftTick(long tick) {
        return new FamiliarityEntry(score, lastInteractionTick, tick, memories);
    }
}
