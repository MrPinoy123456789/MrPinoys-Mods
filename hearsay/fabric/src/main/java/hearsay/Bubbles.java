package hearsay;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
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
    private static final int BASE_DWELL_TICKS = 40;
    private static final int DWELL_PER_CHAR = 2;
    private static final int MAX_SPEECH_LENGTH = 60;
    private static final String BILLBOARD_CENTER = "center";

    private final Map<UUID, Bubble> speech = new HashMap<>();
    private int tick;

    public Bubbles() {}

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
        tag.putString("text", "{\"text\":\"" + escapeJson(text) + "\"}");
        tag.putString("billboard", BILLBOARD_CENTER);
        tag.putFloat("view_range", viewRange);
        tag.putBoolean("see_through", true);
        tag.putBoolean("shadow", true);
        tag.putBoolean("default_background", true);
        tag.putString("alignment", "center");

        CompoundTag bright = new CompoundTag();
        bright.putInt("block", 15);
        bright.putInt("sky", 15);
        tag.put("brightness", bright);

        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(speaker.getX()));
        pos.add(DoubleTag.valueOf(speaker.getY() + speaker.getBbHeight()));
        pos.add(DoubleTag.valueOf(speaker.getZ()));
        tag.put("Pos", pos);

        ProblemReporter reporter = ProblemReporter.DISCARDING;
        ValueInput in = TagValueInput.create(reporter, level.registryAccess(), tag);
        display.load(in);
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

    private void sweep() {
        speech.values().removeIf(b -> {
            if (b.display == null || b.display.isRemoved()
                    || (b.expiry > 0 && tick >= b.expiry)) {
                b.discard();
                if (!b.queue.isEmpty() && b.speaker != null
                        && b.speaker.level() instanceof ServerLevel level) {
                    String next = b.queue.pollFirst();
                    Bubble replacement = create(level, b.speaker, next, SPEECH_VIEW_RANGE);
                    replacement.expiry = tick + BASE_DWELL_TICKS + next.length() * DWELL_PER_CHAR;
                    replacement.queue = b.queue;
                    speech.put(b.speaker.getUUID(), replacement);
                }
                return true;
            }
            return false;
        });
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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
