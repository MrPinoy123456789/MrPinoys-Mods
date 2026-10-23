package pocketdungeons;

import com.mojang.math.Transformation;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import pocketdungeons.mixin.DisplayAccessor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * A glyph (decision 2026-10-21, spec 3): something you can see and something that
 * happens when you right-click it. A glyph is one {@code minecraft:interaction}
 * anchor (the square click box) carrying the {@link Data} attachment, plus any
 * display entities that share the glyph's group tag.
 *
 * <p>{@link #register()} installs the single dispatcher: every right-click on an
 * entity tagged {@link #TAG} that carries glyph data runs the named handler from
 * {@link GlyphHandlers}. Owners such as the terminal or the astrolabe tag their
 * own entities too, for bulk cleanup the way {@code HallRoom.TAG} works.
 */
public final class Glyph {

    /** Every entity a glyph spawns carries this tag. */
    public static final String TAG = PocketDungeonsMod.MOD_ID + "_glyph";
    /** The anchor's visuals share {@code TAG + "_" + anchor uuid}; the anchor finds them by it. */
    public static final String GROUP_PREFIX = TAG + "_";
    /** The attachment key under which the interaction anchor persists its data. */
    public static final Identifier DATA_ID =
            Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, "glyph_data");

    /** A player may only fire a glyph once in this many ticks (double-fire guard). */
    private static final int COOLDOWN_TICKS = 4;
    /** The refuse shake: three translations of the icon, this many ticks each. */
    private static final int SHAKE_TICKS = 2;
    private static final float SHAKE_X = 0.06f;

    /** What the interaction anchor persists: the named handler and its saved data. */
    public record Data(Identifier handler, CompoundTag data) {
        public static final Codec<Data> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Identifier.CODEC.fieldOf("handler").forGetter(Data::handler),
                CompoundTag.CODEC.optionalFieldOf("data", new CompoundTag()).forGetter(Data::data))
                .apply(inst, Data::new));
    }

    /** The persistent attachment on the anchor; survives save and reload. */
    public static final AttachmentType<Data> DATA =
            AttachmentRegistry.createPersistent(DATA_ID, Data.CODEC);

    private static final Map<UUID, Long> lastUse = new HashMap<>();
    /** Pending refuse shakes: the icon, the offsets left, the tick the next lands. */
    private static final List<Shake> shakes = new ArrayList<>();
    /** The base scale each spawned icon's transformation was built with, for the shake. */
    private static final Map<Entity, Float> iconScale = new WeakHashMap<>();

    private record Shake(Entity icon, float scale, int step, long nextTick) {}

    private final Entity anchor;
    private final Data glyphData;

    private Glyph(Entity anchor, Data glyphData) {
        this.anchor = anchor;
        this.glyphData = glyphData;
    }

    // ---- spawn, find, remove ------------------------------------------------------------

    /**
     * Spawns the click box at {@code at} carrying {@code handler} and {@code data}, with the
     * {@link #TAG} plus any owner tags. Returns the glyph so the caller can attach visuals.
     */
    public static Glyph spawn(ServerLevel level, Vec3 at, float width, float height,
                              Identifier handler, CompoundTag data, List<String> ownerTags) {
        Entity box = EntityTypes.INTERACTION.create(level, EntitySpawnReason.COMMAND);
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:interaction");
        tag.putFloat("width", width);
        tag.putFloat("height", height);
        tag.putBoolean("response", true);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(at.x));
        pos.add(DoubleTag.valueOf(at.y));
        pos.add(DoubleTag.valueOf(at.z));
        tag.put("Pos", pos);
        ListTag rot = new ListTag();
        rot.add(FloatTag.valueOf(0.0f));
        rot.add(FloatTag.valueOf(0.0f));
        tag.put("Rotation", rot);
        ListTag tags = new ListTag();
        tags.add(StringTag.valueOf(TAG));
        for (String extra : ownerTags) {
            tags.add(StringTag.valueOf(extra));
        }
        tag.put("Tags", tags);
        try (ProblemReporter.ScopedCollector reporter =
                     new ProblemReporter.ScopedCollector(box.problemPath(), PocketDungeonsMod.LOG)) {
            box.load(TagValueInput.create(reporter, level.registryAccess(), tag));
        }
        box.setAttached(DATA, new Data(handler, data.copy()));
        level.addFreshEntity(box);
        return new Glyph(box, box.getAttached(DATA));
    }

    /** The glyph on {@code entity}, or null when it is not a glyph anchor with data. */
    public static Glyph of(Entity entity) {
        if (entity == null || !entity.entityTags().contains(TAG)) {
            return null;
        }
        Data data = entity.getAttached(DATA);
        return data == null ? null : new Glyph(entity, data);
    }

    /** The glyph owning {@code entity}, whether the entity is its anchor or one of its visuals. */
    public static Glyph ownerOf(ServerLevel level, Entity entity) {
        if (entity == null || !entity.entityTags().contains(TAG)) {
            return null;
        }
        Glyph direct = of(entity);
        if (direct != null) {
            return direct;
        }
        for (String tag : entity.entityTags()) {
            if (tag.startsWith(GROUP_PREFIX)) {
                UUID id;
                try {
                    id = UUID.fromString(tag.substring(GROUP_PREFIX.length()));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                Entity anchor = level.getEntity(id);
                Glyph glyph = of(anchor);
                if (glyph != null) {
                    return glyph;
                }
            }
        }
        return null;
    }

    /**
     * Adds a display {@code entity} to this glyph's group, at {@code pos}. The entity must
     * already be loaded from its tag; the group tag and {@link #TAG} are added here.
     * {@code scale} is the base scale of its transformation, kept for the refuse shake.
     */
    public void addVisual(ServerLevel level, Entity entity, float scale) {
        if (entity == null) {
            return;
        }
        entity.addTag(TAG);
        entity.addTag(groupTag());
        if (entity instanceof Display.ItemDisplay) {
            iconScale.put(entity, scale);
        }
        level.addFreshEntity(entity);
    }

    /** Every entity in the glyph's group, anchor included, near the anchor. */
    public List<Entity> visuals(ServerLevel level) {
        return level.getEntitiesOfClass(Entity.class,
                new AABB(anchor.blockPosition()).inflate(4.0),
                e -> e.entityTags().contains(groupTag()));
    }

    /** Discards the anchor and every visual in the group. */
    public void remove(ServerLevel level) {
        for (Entity e : visuals(level)) {
            e.discard();
        }
        anchor.discard();
    }

    /** The anchor's click box. */
    public Entity anchor() {
        return anchor;
    }

    /** The handler id this glyph runs on click. */
    public Identifier handler() {
        return glyphData.handler();
    }

    /** The saved handler data. */
    public CompoundTag data() {
        return glyphData.data();
    }

    /** The group tag shared by the anchor and its visuals. */
    public String groupTag() {
        return GROUP_PREFIX + anchor.getUUID();
    }

    /** The anchor's position, for sounds and particles at the glyph. */
    public Vec3 position() {
        return anchor.position();
    }

    // ---- the standard item glyph look (spec 3.6) ----------------------------------------

    /**
     * The standard look: a floating icon with its label under it, on a 0.8 by 1.6 click box.
     * Used by {@code require_item} and the admin command.
     */
    public static Glyph itemGlyph(ServerLevel level, Vec3 at, ItemStack icon, Component label,
                                  Identifier handler, CompoundTag data, List<String> ownerTags) {
        Glyph glyph = spawn(level, at, 0.8f, 1.6f, handler, data, ownerTags);

        CompoundTag itemTag = new CompoundTag();
        itemTag.putString("id", "minecraft:item_display");
        itemTag.put("item", ItemStack.CODEC.encodeStart(
                level.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),
                icon).result().orElse(new CompoundTag()));
        itemTag.putString("billboard", "center");
        itemTag.put("transformation", DungeonScreen.scaleTransformation(0.6f));
        brightness(itemTag);
        glyph.addVisual(level, loadDisplay(level, EntityTypes.ITEM_DISPLAY.create(level,
                EntitySpawnReason.COMMAND), itemTag, at.add(0.0, 1.3, 0.0)), 0.6f);

        CompoundTag textTag = new CompoundTag();
        textTag.putString("id", "minecraft:text_display");
        textTag.put("text", DungeonScreen.encodeText(level, label));
        textTag.putString("billboard", "center");
        textTag.putBoolean("shadow", true);
        textTag.put("transformation", DungeonScreen.scaleTransformation(0.8f));
        brightness(textTag);
        glyph.addVisual(level, loadDisplay(level, EntityTypes.TEXT_DISPLAY.create(level,
                EntitySpawnReason.COMMAND), textTag, at.add(0.0, 0.95, 0.0)), 0.8f);

        return glyph;
    }

    private static void brightness(CompoundTag tag) {
        CompoundTag bright = new CompoundTag();
        bright.putInt("block", 15);
        bright.putInt("sky", 15);
        tag.put("brightness", bright);
    }

    private static Entity loadDisplay(ServerLevel level, Entity entity, CompoundTag tag, Vec3 pos) {
        if (entity == null) {
            return null;
        }
        ListTag p = new ListTag();
        p.add(DoubleTag.valueOf(pos.x));
        p.add(DoubleTag.valueOf(pos.y));
        p.add(DoubleTag.valueOf(pos.z));
        tag.put("Pos", p);
        ListTag rot = new ListTag();
        rot.add(FloatTag.valueOf(0.0f));
        rot.add(FloatTag.valueOf(0.0f));
        tag.put("Rotation", rot);
        try (ProblemReporter.ScopedCollector reporter =
                     new ProblemReporter.ScopedCollector(entity.problemPath(), PocketDungeonsMod.LOG)) {
            entity.load(TagValueInput.create(reporter, level.registryAccess(), tag));
        }
        return entity;
    }

    // ---- the refuse shake (spec 3.6) -----------------------------------------------------

    /** The refuse feedback: the icon slides +0.06, -0.06 and back, two ticks each. */
    public void shake(ServerLevel level) {
        Entity icon = null;
        for (Entity e : visuals(level)) {
            if (e instanceof Display.ItemDisplay) {
                icon = e;
                break;
            }
        }
        if (icon != null) {
            shakes.add(new Shake(icon, iconScale.getOrDefault(icon, 0.6f), 0,
                    level.getGameTime() + 1));
        }
    }

    private static void tickShakes(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        for (Iterator<Shake> it = shakes.iterator(); it.hasNext(); ) {
            Shake s = it.next();
            if (!s.icon().isAlive() || now < s.nextTick()) {
                if (!s.icon().isAlive()) {
                    it.remove();
                }
                continue;
            }
            float x = switch (s.step()) {
                case 0 -> SHAKE_X;
                case 1 -> -SHAKE_X;
                default -> 0.0f;
            };
            DisplayAccessor display = (DisplayAccessor) (Object) s.icon();
            display.pocketdungeons$setTransformation(new Transformation(
                    new Vector3f(x, 0.0f, 0.0f), null, new Vector3f(s.scale()), null));
            display.pocketdungeons$setTransformationInterpolationDuration(SHAKE_TICKS);
            display.pocketdungeons$setTransformationInterpolationDelay(0);
            if (s.step() >= 2) {
                it.remove();
            } else {
                shakes.set(shakes.indexOf(s), new Shake(s.icon(), s.scale(), s.step() + 1,
                        now + SHAKE_TICKS));
            }
        }
    }

    /** A burst of end rod at the anchor, the accept feedback of {@code require_item}. */
    public void sparkle(ServerLevel level) {
        Vec3 at = position().add(0.0, 0.8, 0.0);
        level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 12, 0.3, 0.4, 0.3, 0.02);
    }

    /** A sound at the glyph for nearby players. */
    public void playSound(ServerLevel level, SoundEvent sound) {
        level.playSound(null, anchor.blockPosition(), sound, SoundSource.BLOCKS, 1.0f, 1.0f);
    }

    // ---- the single dispatcher ------------------------------------------------------------

    /** Registers the right-click dispatch and the shake ticker, called once at mod init. */
    public static void register() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            Glyph glyph = of(entity);
            if (glyph == null) {
                return InteractionResult.PASS;
            }
            if (level.isClientSide() || hand != InteractionHand.MAIN_HAND
                    || !(player instanceof ServerPlayer sp)) {
                return InteractionResult.SUCCESS;
            }
            ServerLevel serverLevel = (ServerLevel) level;
            long now = serverLevel.getGameTime();
            Long last = lastUse.get(sp.getUUID());
            if (last != null && now - last < COOLDOWN_TICKS) {
                return InteractionResult.SUCCESS_SERVER;
            }
            lastUse.put(sp.getUUID(), now);
            GlyphContext ctx = new GlyphContext(sp, serverLevel, sp.getMainHandItem(),
                    sp.isShiftKeyDown(), InstanceRegistry.byMember.get(sp.getUUID()),
                    glyph, glyph.data().copy());
            GlyphHandlers.dispatch(ctx);
            return InteractionResult.SUCCESS_SERVER;
        });
        ServerTickEvents.END_SERVER_TICK.register(Glyph::tickShakes);
    }
}
