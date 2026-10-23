package pocketdungeons;

import com.mojang.serialization.JsonOps;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The glyph handler registry (spec 3.3): handlers are registered by id at mod
 * init and the {@link Glyph} dispatcher runs them. The five ready-made handlers
 * of spec 3.4 live here. An unknown id logs one warning per id and refuses; it
 * never throws.
 */
public final class GlyphHandlers {

    public static final Identifier REQUIRE_ITEM =
            Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "require_item");
    public static final Identifier CYCLE =
            Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "cycle");
    public static final Identifier TOGGLE =
            Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "toggle");
    public static final Identifier RUN_ONCE =
            Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "run_once");
    public static final Identifier INSPECT =
            Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "inspect");

    private static final Map<Identifier, GlyphHandler> handlers = new HashMap<>();
    private static final Set<Identifier> warnedUnknown = new HashSet<>();

    private GlyphHandlers() {}

    /** Registers {@code id}; later registrations replace earlier ones. */
    public static void register(Identifier id, GlyphHandler handler) {
        handlers.put(id, handler);
    }

    /** The five ready-made handlers, called once at mod init. */
    public static void registerDefaults() {
        register(REQUIRE_ITEM, GlyphHandlers::requireItem);
        register(CYCLE, GlyphHandlers::cycle);
        register(TOGGLE, GlyphHandlers::toggle);
        register(RUN_ONCE, GlyphHandlers::runOnce);
        register(INSPECT, GlyphHandlers::inspect);
    }

    /** Runs the glyph's handler and applies its result in the spec order. */
    static void dispatch(GlyphContext ctx) {
        apply(ctx, ctx.glyph().handler(), ctx.data());
    }

    /** Runs one handler and applies its {@link GlyphResult}. Shared by {@code then}. */
    private static void apply(GlyphContext ctx, Identifier id, CompoundTag data) {
        GlyphHandler handler = handlers.get(id);
        if (handler == null) {
            if (warnedUnknown.add(id)) {
                PocketDungeonsMod.LOG.warn("Glyph clicked with unknown handler {}", id);
            }
            Chime.refused(ctx.player());
            return;
        }
        GlyphResult result;
        try {
            result = handler.use(new GlyphContext(ctx.player(), ctx.level(), ctx.held(),
                    ctx.sneaking(), ctx.record(), ctx.glyph(), data));
        } catch (RuntimeException e) {
            PocketDungeonsMod.LOG.warn("Glyph handler {} threw", id, e);
            Chime.refused(ctx.player());
            return;
        }
        if (result == null || result == GlyphResult.PASS) {
            return;
        }
        if (result.consume > 0 && !ctx.held().isEmpty()) {
            ctx.held().shrink(result.consume);
        }
        if (result.refuse) {
            Chime.refused(ctx.player());
            ctx.glyph().shake(ctx.level());
        }
        if (result.overlay != null) {
            ctx.player().sendOverlayMessage(result.overlay);
        }
        if (result.sound != null) {
            ctx.glyph().playSound(ctx.level(), result.sound);
        }
        if (result.restyle != null) {
            result.restyle.accept(ctx.glyph());
        }
        if (result.vanish) {
            ctx.glyph().remove(ctx.level());
        }
        if (result.thenId != null) {
            apply(ctx, result.thenId, result.thenData);
        }
    }

    // ---- the ready-made handlers (spec 3.4) ----------------------------------------------

    /**
     * Held item matches {@code item} and the stack holds {@code count}: consume it, sound
     * the vault shutter, sparkle, vanish, run {@code then}. Else refuse with
     * "Needs a &lt;label&gt;" and shake the icon.
     */
    private static GlyphResult requireItem(GlyphContext ctx) {
        CompoundTag data = ctx.data();
        String itemId = data.getStringOr("item", "");
        int count = Math.max(1, data.getIntOr("count", 1));
        Identifier id = Identifier.tryParse(itemId);
        Item item = id == null ? null : BuiltInRegistries.ITEM.getValue(id);
        String label = data.getStringOr("label",
                item == null ? itemId : new ItemStack(item).getHoverName().getString());
        if (item == null || !ctx.held().is(item) || ctx.held().getCount() < count) {
            return GlyphResult.create().refuse()
                    .overlay(Component.literal("Needs a " + label).withStyle(ChatFormatting.YELLOW));
        }
        ctx.glyph().sparkle(ctx.level());
        return chain(GlyphResult.create().consume(count)
                        .sound(SoundEvents.VAULT_OPEN_SHUTTER).vanish(), data);
    }

    /**
     * Advances {@code record.glyphState[key]} to the next of {@code values}
     * (previous when sneaking), then runs {@code then}.
     */
    private static GlyphResult cycle(GlyphContext ctx) {
        CompoundTag data = ctx.data();
        String key = data.getStringOr("key", "");
        ListTag values = data.getListOrEmpty("values");
        if (key.isEmpty() || values.isEmpty() || ctx.record() == null) {
            return GlyphResult.PASS;
        }
        String[] options = new String[values.size()];
        for (int i = 0; i < values.size(); i++) {
            options[i] = values.get(i) instanceof StringTag s ? s.value() : "";
        }
        String current = ctx.record().glyphState.getOrDefault(key, "");
        int at = -1;
        for (int i = 0; i < options.length; i++) {
            if (options[i].equals(current)) {
                at = i;
                break;
            }
        }
        int next = Math.floorMod(at + (ctx.sneaking() ? -1 : 1), options.length);
        ctx.record().glyphState.put(key, options[next]);
        return chain(GlyphResult.create(), data);
    }

    /** Flips {@code record.glyphState[key]} between "on" and "off", then runs {@code then}. */
    private static GlyphResult toggle(GlyphContext ctx) {
        CompoundTag data = ctx.data();
        String key = data.getStringOr("key", "");
        if (key.isEmpty() || ctx.record() == null) {
            return GlyphResult.PASS;
        }
        boolean on = "on".equals(ctx.record().glyphState.getOrDefault(key, "off"));
        ctx.record().glyphState.put(key, on ? "off" : "on");
        return chain(GlyphResult.create(), data);
    }

    /** Runs {@code then}, then vanishes. */
    private static GlyphResult runOnce(GlyphContext ctx) {
        return chain(GlyphResult.create().vanish(), ctx.data());
    }

    /** Shows the serialized {@code text} component to the clicker; used by reward icons. */
    private static GlyphResult inspect(GlyphContext ctx) {
        String text = ctx.data().getStringOr("text", "");
        if (text.isEmpty()) {
            return GlyphResult.PASS;
        }
        Component message = decodeComponent(ctx, text);
        if (message == null) {
            message = Component.literal(text);
        }
        return GlyphResult.create().overlay(message);
    }

    /**
     * Adds the {@code then} / {@code then_data} chain to {@code result}, when the saved
     * data names another handler to run after this one.
     */
    private static GlyphResult chain(GlyphResult result, CompoundTag data) {
        String then = data.getStringOr("then", "");
        if (!then.isEmpty()) {
            Identifier id = Identifier.tryParse(then);
            if (id != null) {
                result.then(id, data.getCompoundOrEmpty("then_data").copy());
            }
        }
        return result;
    }

    /** The serialized component of {@code inspect}: JSON, decoded with registry access. */
    private static Component decodeComponent(GlyphContext ctx, String json) {
        try {
            return ComponentSerialization.CODEC.decode(
                            ctx.level().registryAccess().createSerializationContext(JsonOps.INSTANCE),
                            com.google.gson.JsonParser.parseString(json))
                    .result().map(p -> p.getFirst()).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
