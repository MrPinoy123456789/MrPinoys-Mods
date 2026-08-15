package wayfarers;

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
 * TextDisplay speech and lure bubbles for wayfarer encounters.
 *
 * <p>{@code Display$TextDisplay} has no public setters, so each bubble is built
 * via a {@link CompoundTag} wrapped in {@link TagValueInput} and
 * {@link Entity#load(ValueInput)}, the same way cobblebending spawns a
 * {@code BlockDisplay} (PLAN.md §M2).
 */
public final class Bubbles {

    private static final float SPEECH_VIEW_RANGE = 0.25f;
    private static final float LURE_VIEW_RANGE = 1.0f;
    private static final int BASE_DWELL_TICKS = 40;
    private static final int DWELL_PER_CHAR = 2;
    private static final int MAX_SPEECH_LENGTH = 60;
    private static final int MAX_LURE_LENGTH = 24;
    private static final String BILLBOARD_CENTER = "center";
    /** Bubble text colour; swap to {@link ChatFormatting#YELLOW} to taste. */
    private static final ChatFormatting TEXT_COLOR = ChatFormatting.WHITE;
    /**
     * Must stay false. MC-277982: a TextDisplay with {@code see_through=true}
     * renders through a layer that mishandles lighting, so the glyphs are dragged
     * toward black whatever the component's colour says. Verified in hearsay with
     * an A/B probe — flipping this flag alone turned red text black.
     */
    private static final boolean SEE_THROUGH = false;
    /** Marks bubbles so leftover/orphaned ones can be found and killed with
     *  {@code /kill @e[type=minecraft:text_display,tag=wayfarers_bubble]}. */
    public static final String TAG = "wayfarers_bubble";

    private final Map<UUID, Bubble> speech = new HashMap<>();
    private final Map<UUID, Bubble> lure = new HashMap<>();
    private int tick;

    public Bubbles() {}

    public void tick() {
        tick++;
        sweep(speech);
        sweep(lure);
    }

    /** Show a speech bubble above the speaker. Replaces the current one. */
    public void say(ServerLevel level, Entity speaker, String line) {
        if (speaker == null || line == null || line.isBlank()) {
            return;
        }

        // A speech line replaces any standing lure.
        clearLure(speaker.getUUID());
        clearSpeech(speaker.getUUID());

        List<String> segments = splitLine(line.trim(), MAX_SPEECH_LENGTH);
        if (segments.isEmpty()) {
            return;
        }

        Bubble bubble = create(level, speaker, segments.get(0), SPEECH_VIEW_RANGE, true, true);
        bubble.expiry = tick + BASE_DWELL_TICKS + segments.get(0).length() * DWELL_PER_CHAR;
        for (int i = 1; i < segments.size(); i++) {
            bubble.queue.add(segments.get(i));
        }
        speech.put(speaker.getUUID(), bubble);
    }

    /** Show a standing lure bubble that stays until speech or clear. */
    public void lure(ServerLevel level, Entity speaker, String line) {
        if (speaker == null || line == null || line.isBlank()) {
            return;
        }
        String text = line.trim();
        if (text.length() > MAX_LURE_LENGTH) {
            text = text.substring(0, MAX_LURE_LENGTH);
        }

        // Never stack lure on top of an active speech bubble.
        if (speech.containsKey(speaker.getUUID())) {
            return;
        }

        clearLure(speaker.getUUID());
        Bubble bubble = create(level, speaker, text, LURE_VIEW_RANGE, true, false);
        bubble.expiry = -1;
        lure.put(speaker.getUUID(), bubble);
    }

    /** Remove both speech and lure bubbles for a speaker. */
    public void clear(UUID speakerId) {
        clearSpeech(speakerId);
        clearLure(speakerId);
    }

    private void clearSpeech(UUID id) {
        Bubble removed = speech.remove(id);
        if (removed != null) {
            removed.discard();
        }
    }

    private void clearLure(UUID id) {
        Bubble removed = lure.remove(id);
        if (removed != null) {
            removed.discard();
        }
    }

    private Bubble create(ServerLevel level, Entity speaker, String text,
                          float viewRange, boolean billboard, boolean shadow) {
        Display.TextDisplay display = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (display == null) {
            return new Bubble(null, -1);
        }

        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:text_display");
        tag.put("text", encodeText(level, text));
        if (billboard) {
            tag.putString("billboard", BILLBOARD_CENTER);
        }
        tag.putFloat("view_range", viewRange);
        tag.putBoolean("see_through", SEE_THROUGH);
        tag.putBoolean("shadow", shadow);
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
                     new ProblemReporter.ScopedCollector(display.problemPath(), WayfarersMod.LOG)) {
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

    private void sweep(Map<UUID, Bubble> map) {
        map.values().removeIf(b -> {
            // A bubble rides its speaker, so a dead speaker leaves the display
            // dismounted and stranded — and a lure never expires on its own.
            boolean speakerGone = b.speaker == null || b.speaker.isRemoved() || !b.speaker.isAlive();
            if (b.display == null || b.display.isRemoved() || speakerGone
                    || (b.expiry > 0 && tick >= b.expiry)) {
                b.discard();
                if (!speakerGone && !b.queue.isEmpty()
                        && b.speaker.level() instanceof ServerLevel level) {
                    String next = b.queue.pollFirst();
                    Bubble replacement = create(level, b.speaker, next, SPEECH_VIEW_RANGE, true, true);
                    replacement.expiry = tick + BASE_DWELL_TICKS + next.length() * DWELL_PER_CHAR;
                    replacement.queue = b.queue;
                    map.put(b.speaker.getUUID(), replacement);
                }
                return true;
            }
            return false;
        });
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
                .resultOrPartial(err -> WayfarersMod.LOG.warn("Wayfarers could not encode bubble text: {}", err))
                .orElseGet(() -> StringTag.valueOf(text));
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
