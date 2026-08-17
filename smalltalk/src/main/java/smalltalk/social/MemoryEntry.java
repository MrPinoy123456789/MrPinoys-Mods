package smalltalk.social;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One remembered event for a villager-player pair: a tag plus a timestamp --
 * {@code gave_gift:cake}, {@code saw_you_die}, {@code you_were_away:12d}
 * (SPEC.md section 8). Up to three are kept per pair; the oldest is dropped
 * when a fourth arrives.
 */
public record MemoryEntry(String tag, long tick) {

    public static final Codec<MemoryEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("tag").forGetter(MemoryEntry::tag),
            Codec.LONG.fieldOf("tick").forGetter(MemoryEntry::tick)
    ).apply(instance, MemoryEntry::new));
}
