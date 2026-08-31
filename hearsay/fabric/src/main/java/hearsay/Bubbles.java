package hearsay;

import com.mojang.serialization.DynamicOps;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TextDisplay speech bubbles above a speaking villager.
 *
 * <p>Copied from wayfarers and retuned for Hearsay's shorter village lines.
 */
public final class Bubbles {

    private static final float SPEECH_VIEW_RANGE = 0.5f;
    private static final int BASE_DWELL_TICKS = 80;
    private static final int DWELL_PER_CHAR = 4;
    private static final int MAX_SPEECH_LENGTH = 60;
    private static final String BILLBOARD_CENTER = "center";
    /** Bubble text colour; swap to {@link ChatFormatting#YELLOW} to taste. */
    private static final ChatFormatting TEXT_COLOR = ChatFormatting.WHITE;
    /**
     * Must stay false. MC-277982: a TextDisplay with {@code see_through=true}
     * renders through a layer that mishandles lighting, so the glyphs are dragged
     * toward black whatever the component's colour says — which is exactly the
     * "bubbles are black" bug. The trade is that bubbles no longer show through
     * blocks, which for speech above a villager is no loss.
     */
    private static final boolean SEE_THROUGH = false;
    /** Marks bubbles so leftover/orphaned ones can be found and killed with
     *  {@code /kill @e[type=minecraft:text_display,tag=hearsay_bubble]}. */
    public static final String TAG = "hearsay_bubble";

    private final Map<UUID, Bubble> speech = new HashMap<>();
    private int tick;

    public Bubbles() {}

    /**
     * Kill any bubble entities left in the world from a previous run.
     *
     * <p>Bubble {@link Display.TextDisplay TextDisplays} are saved entities, so
     * they survive a server restart. The in-memory {@link #speech} map does not,
     * which means reloaded bubbles are orphans: nothing tracks them, their
     * expiry is forgotten, and the next {@link #say} spawns a fresh bubble on
     * top of them. That is the "two overlapping lines above one head" bug.
     * Running this once on server start clears the slate.
     */
    public void purgeOrphans(MinecraftServer server) {
        int killed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof Display.TextDisplay && e.entityTags().contains(TAG)) {
                    e.discard();
                    killed++;
                }
            }
        }
        if (killed > 0) {
            HearsayMod.LOG.info("Hearsay discarded {} orphaned speech bubble(s) on server start", killed);
        }
    }

    /** Advances bubble dwell timers and replaces expired segments or removes orphans. */
    public void tick() {
        tick++;
        sweep();
    }

    /** Show a speech bubble above the speaker. Replaces the current one. */
    public void say(ServerLevel level, Entity speaker, String line) {
        if (speaker == null || line == null || line.isBlank()) {
            return;
        }

        clear(speaker.getUUID());
        // Also discard any stale bubble entities riding the speaker that the
        // in-memory map does not know about. After a server restart the map is
        // empty but the saved TextDisplay entities reload from disk and keep
        // riding their villagers; without this, the new bubble stacks on top
        // of the orphan, producing two overlapping lines above one head.
        for (Entity passenger : speaker.getPassengers()) {
            if (passenger instanceof Display.TextDisplay
                    && passenger.entityTags().contains(TAG)) {
                passenger.discard();
            }
        }

        List<String> segments = splitLine(line.trim(), MAX_SPEECH_LENGTH);
        if (segments.isEmpty()) {
            return;
        }

        Bubble bubble = create(level, speaker, segments.get(0), SPEECH_VIEW_RANGE);
        bubble.expiry = tick + BASE_DWELL_TICKS + segments.get(0).length() * DWELL_PER_CHAR;
        for (int i = 1; i < segments.size(); i++) {
            bubble.queue.add(segments.get(i));
        }
        speech.put(speaker.getUUID(), bubble);
    }

    /** Remove a speaker's bubble. */
    public void clear(UUID speakerId) {
        Bubble removed = speech.remove(speakerId);
        if (removed != null) {
            removed.discard();
        }
    }

    private Bubble create(ServerLevel level, Entity speaker, String text, float viewRange) {
        Display.TextDisplay display = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (display == null) {
            return new Bubble(null, -1);
        }

        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:text_display");
        tag.put("text", encodeText(level, text));
        tag.putString("billboard", BILLBOARD_CENTER);
        tag.putFloat("view_range", viewRange);
        tag.putBoolean("see_through", SEE_THROUGH);
        tag.putBoolean("shadow", true);
        tag.putBoolean("default_background", true);
        tag.putString("alignment", "center");
        tag.putByte("text_opacity", (byte) 255);

        ListTag tags = new ListTag();
        tags.add(StringTag.valueOf(TAG));
        tag.put("Tags", tags);

        CompoundTag bright = new CompoundTag();
        bright.putInt("block", 15);
        bright.putInt("sky", 15);
        tag.put("brightness", bright);

        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(speaker.getX()));
        pos.add(DoubleTag.valueOf(speaker.getY() + speaker.getBbHeight()));
        pos.add(DoubleTag.valueOf(speaker.getZ()));
        tag.put("Pos", pos);

        // Report load problems rather than discarding them; silently swallowing
        // these is what let the colour bug hide for so long.
        try (ProblemReporter.ScopedCollector reporter =
                     new ProblemReporter.ScopedCollector(display.problemPath(), HearsayMod.LOG)) {
            ValueInput in = TagValueInput.create(reporter, level.registryAccess(), tag);
            display.load(in);
        }
        level.addFreshEntity(display);
        if (!display.isPassenger()) {
            display.startRiding(speaker, true, false);
        }
        Bubble bubble = new Bubble(display, -1);
        bubble.speaker = speaker;
        return bubble;
    }

    /**
     * Build the {@code text} tag with the same codec {@code TextDisplay} reads it
     * back with, so the encoded shape is correct by construction.
     *
     * <p>Since 1.21.5 this field is an NBT object, not a JSON string — writing a
     * string here renders the raw JSON verbatim above the speaker. Encoding a real
     * {@link Component} keeps us on whatever shape the codec expects.
     */
    private static Tag encodeText(ServerLevel level, String text) {
        Component message = Component.literal(text).withStyle(TEXT_COLOR);
        DynamicOps<Tag> ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        return ComponentSerialization.CODEC.encodeStart(ops, message)
                .resultOrPartial(err -> HearsayMod.LOG.warn("Hearsay could not encode bubble text: {}", err))
                .orElseGet(() -> StringTag.valueOf(text));
    }

    private static List<String> splitLine(String line, int max) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int len = line.length();
        while (start < len) {
            int end = Math.min(start + max, len);
            if (end < len) {
                int space = line.lastIndexOf(' ', end);
                if (space > start) {
                    end = space;
                } else {
                    end = start + max;
                }
            }
            while (start < len && line.charAt(start) == ' ') start++;
            String part = line.substring(start, end).trim();
            if (!part.isEmpty()) out.add(part);
            start = end;
            while (start < len && line.charAt(start) == ' ') start++;
        }
        return out.isEmpty() ? List.of(line) : out;
    }

    private void sweep() {
        Map<UUID, Bubble> replacements = new HashMap<>();
        speech.entrySet().removeIf(entry -> {
            Bubble b = entry.getValue();
            // A bubble rides its speaker, so a dead speaker leaves the display
            // dismounted and stranded — the "dialogue that survives the villager".
            boolean speakerGone = b.speaker == null || b.speaker.isRemoved() || !b.speaker.isAlive();
            if (b.display == null || b.display.isRemoved() || speakerGone
                    || (b.expiry > 0 && tick >= b.expiry)) {
                b.discard();
                if (!speakerGone && !b.queue.isEmpty()
                        && b.speaker.level() instanceof ServerLevel level) {
                    String next = b.queue.pollFirst();
                    Bubble replacement = create(level, b.speaker, next, SPEECH_VIEW_RANGE);
                    replacement.expiry = tick + BASE_DWELL_TICKS + next.length() * DWELL_PER_CHAR;
                    replacement.queue = b.queue;
                    replacements.put(entry.getKey(), replacement);
                }
                return true;
            }
            return false;
        });
        speech.putAll(replacements);
    }

    private static final class Bubble {
        final Display.TextDisplay display;
        int expiry;
        Entity speaker;
        ArrayDeque<String> queue = new ArrayDeque<>();

        Bubble(Display.TextDisplay display, int expiry) {
            this.display = display;
            this.expiry = expiry;
        }

        void discard() {
            if (display != null && !display.isRemoved()) {
                display.discard();
            }
        }
    }
}
