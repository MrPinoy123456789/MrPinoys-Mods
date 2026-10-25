package charter.mc.granary;

import charter.id.CoreId;
import charter.mc.CharterMod;
import charter.mc.granary.GranaryBook.Pos;
import com.mojang.serialization.DynamicOps;
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
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The text display above a granary or camp store (PLAN.md §7.1). A display is a
 * saved entity, so after a restart the old one is still there; before spawning a
 * new one this adopts a tagged display already standing at the spot.
 */
final class GranaryDisplays {

    static final String TAG = "charter_granary";

    private final Map<CoreId, Display.TextDisplay> shown = new HashMap<>();
    private final Map<CoreId, String> last = new HashMap<>();

    /** Shows the text above the position, creating or updating the display only when it changed. */
    void show(ServerLevel level, CoreId core, Pos pos, String text) {
        Display.TextDisplay display = shown.get(core);
        if (display != null && display.isRemoved()) {
            shown.remove(core);
            last.remove(core);
            display = null;
        }
        if (display != null && text.equals(last.get(core))) return;
        double x = pos.x() + 0.5;
        double y = pos.y() + 1.6;
        double z = pos.z() + 0.5;
        if (display == null) {
            display = adopt(level, x, y, z);
            if (display == null) {
                display = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
                if (display == null) return;
                load(level, display, text, x, y, z);
                level.addFreshEntity(display);
            }
        }
        load(level, display, text, x, y, z);
        shown.put(core, display);
        last.put(core, text);
    }

    void clear(CoreId core) {
        Display.TextDisplay display = shown.remove(core);
        last.remove(core);
        if (display != null && !display.isRemoved()) display.discard();
    }

    /** Forgets every display without touching the entities (the server is stopping; they are saved). */
    void forgetAll() {
        shown.clear();
        last.clear();
    }

    Display.TextDisplay entity(CoreId core) {
        return shown.get(core);
    }

    String text(CoreId core) {
        return last.get(core);
    }

    /** A tagged display already at the spot, left by an earlier run; any extras are removed. */
    private static Display.TextDisplay adopt(ServerLevel level, double x, double y, double z) {
        AABB box = new AABB(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1);
        List<Display.TextDisplay> found = level.getEntitiesOfClass(Display.TextDisplay.class, box, e -> e.entityTags().contains(TAG));
        for (int i = 1; i < found.size(); i++) found.get(i).discard();
        return found.isEmpty() ? null : found.getFirst();
    }

    private static void load(ServerLevel level, Display.TextDisplay display, String text, double x, double y, double z) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:text_display");
        tag.put("text", encode(level, text));
        tag.putString("billboard", "center");
        tag.putFloat("view_range", 0.5f);
        tag.putBoolean("see_through", false);
        tag.putBoolean("shadow", true);
        tag.putBoolean("default_background", true);
        tag.putString("alignment", "center");
        tag.putByte("text_opacity", (byte) 255);
        ListTag tags = new ListTag();
        tags.add(StringTag.valueOf(TAG));
        tag.put("Tags", tags);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(y));
        pos.add(DoubleTag.valueOf(z));
        tag.put("Pos", pos);
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(display.problemPath(), CharterMod.LOG)) {
            ValueInput in = TagValueInput.create(reporter, level.registryAccess(), tag);
            display.load(in);
        }
    }

    private static Tag encode(ServerLevel level, String text) {
        Component message = Component.literal(text);
        DynamicOps<Tag> ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        return ComponentSerialization.CODEC.encodeStart(ops, message)
                .resultOrPartial(err -> CharterMod.LOG.warn("Charter could not encode display text: {}", err))
                .orElseGet(() -> StringTag.valueOf(text));
    }
}
