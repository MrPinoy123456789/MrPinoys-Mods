package pocketdungeons;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.ChunkAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.HolderAttachment;
import eu.pb4.polymer.virtualentity.api.elements.BlockDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Brightness;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Quaternionf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.FrontAndTop;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * The look of a wild marker (owner, 2026-11-04): when Foundry's {@code kingdoms:lodestone_*} items are installed, a
 * carved plinth laid over the bedrock with a crystal floating, turning and bobbing above it, whose colour says the
 * state ({@link WildLook}: gold Ready, purple ominous, dim while cooling, pale green for an Exit). Both are item
 * displays in the FIXED context, the one Foundry draws with each item's 3D model; the models are built by
 * {@code foundry/tools/gen_lodestone_models.py}. The marker also gives off a few particles in its colour every
 * second, which Bedrock players see as well. Without the Foundry items it falls back to the old look below.
 *
 * <p>The old look makes a wild marker (a bedrock block, see {@link WildLodestones}) look like a jigsaw block to Java clients: one
 * virtual block display of {@code minecraft:jigsaw} laid exactly over the bedrock. The block underneath is what clicks
 * hit (a display has no hitbox), and what the client believes is there is bedrock, so a client has nothing to open an
 * editor on. Bedrock (Geyser) players are skipped, like the worn trinket displays: they just see bedrock.
 *
 * <p>This is the only class that touches Polymer, so the rest of the mod loads without it; {@link WildLodestones} calls
 * here only when {@code polymer-virtual-entity} is installed. A display is not saved: it is rebuilt when the chunk
 * loads (see {@link WildLodestones#ensureMarker}) and dropped with the chunk, or at once when the block stops being
 * bedrock.
 */
final class WildMarkerDisplays {

    private record Key(String dimension, long pos) {}

    private static final Map<Key, HolderAttachment> LIVE = SessionState.clearedAtStop(new HashMap<>());

    private static final BlockState LOOKALIKE =
            Blocks.JIGSAW.defaultBlockState().setValue(JigsawBlock.ORIENTATION, FrontAndTop.UP_NORTH);
    /** The display is a hair larger than the block so its faces do not fight the bedrock under them. */
    private static final float GROW = 0.002F;

    /** Where the crystal's centre floats above the block's lower corner (the plinth's top is at 1.0). */
    private static final float CRYSTAL_HEIGHT = 1.75F;
    /** How far the crystal bobs up and down, in blocks. */
    private static final float BOB = 0.08F;
    /** Ticks between the crystal's moves; the client interpolates over the same span. */
    private static final int STEP = 4;
    /** Degrees the crystal turns each step: a full turn in 6 seconds. */
    private static final float TURN = 12F;

    private WildMarkerDisplays() {}

    /** A Foundry item by id, or {@code null} when Foundry or its content is not installed. */
    private static Item foundryItem(String id) {
        Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(null);
        return item == null || item == Items.AIR ? null : item;
    }

    /** The particle a look gives off, or {@code null} for none (a dim crystal is quiet). */
    private static ParticleOptions particleOf(WildLook look) {
        return switch (look) {
            case READY -> ParticleTypes.ENCHANT;
            case OMINOUS -> ParticleTypes.WITCH;
            case EXIT -> ParticleTypes.END_ROD;
            case DIM -> null;
        };
    }

    /** Floodgate gives Bedrock players a UUID whose high half is zero; Java accounts never have one. */
    static boolean isBedrock(java.util.UUID id) {
        return id.getMostSignificantBits() == 0L;
    }

    private static final class Holder extends ElementHolder {
        private final ServerLevel level;
        private final BlockPos pos;
        private final Key key;
        /** The floating crystal, or {@code null} on the old jigsaw look. */
        private ItemDisplayElement crystal;
        private WildLook look;
        private int ticks;
        private float angle;

        Holder(ServerLevel level, BlockPos pos, Key key) {
            this.level = level;
            this.pos = pos;
            this.key = key;
            Item plinth = foundryItem(WildLook.PLINTH);
            if (plinth == null) {
                BlockDisplayElement display = new BlockDisplayElement(LOOKALIKE);
                display.setScale(new Vector3f(1F + GROW, 1F + GROW, 1F + GROW));
                display.setTranslation(new Vector3f(-GROW / 2F, -GROW / 2F, -GROW / 2F));
                addElement(display);
                return;
            }
            ItemDisplayElement stone = new ItemDisplayElement(new ItemStack(plinth));
            stone.setItemDisplayContext(ItemDisplayContext.FIXED);
            stone.setTranslation(new Vector3f(0.5F, 0.5F, 0.5F));
            addElement(stone);
            look = WildLodestones.lookAt(level, pos);
            Item gem = foundryItem((look == null ? WildLook.READY : look).crystal);
            if (gem != null) {
                crystal = new ItemDisplayElement(new ItemStack(gem));
                crystal.setItemDisplayContext(ItemDisplayContext.FIXED);
                crystal.setTranslation(new Vector3f(0.5F, CRYSTAL_HEIGHT, 0.5F));
                crystal.setInterpolationDuration(STEP);
                glow(look);
                addElement(crystal);
            }
        }

        /** Lit crystals glow at full light; a dim one takes the light around it. */
        private void glow(WildLook now) {
            crystal.setBrightness(now == WildLook.DIM ? null : new Brightness(15, 15));
        }

        /** Turns and bobs the crystal, and every second follows the state and gives off its particles. */
        private void animate() {
            if (ticks % STEP == 0) {
                angle = (angle + TURN) % 360F;
                float bob = (float) Math.sin(Math.toRadians(angle * 2F)) * BOB;
                crystal.setLeftRotation(new Quaternionf().rotateY((float) Math.toRadians(angle)));
                crystal.setTranslation(new Vector3f(0.5F, CRYSTAL_HEIGHT + bob, 0.5F));
                crystal.startInterpolationIfDirty();
            }
            if (ticks % 20 == 0) {
                WildLook now = WildLodestones.lookAt(level, pos);
                if (now != null && now != look) {
                    Item gem = foundryItem(now.crystal);
                    if (gem != null) {
                        crystal.setItem(new ItemStack(gem));
                        glow(now);
                    }
                    look = now;
                }
                ParticleOptions particle = look == null ? null : particleOf(look);
                if (particle != null) {
                    level.sendParticles(particle, pos.getX() + 0.5, pos.getY() + CRYSTAL_HEIGHT, pos.getZ() + 0.5,
                            6, 0.35, 0.35, 0.35, 0.02);
                }
            }
        }

        @Override
        public boolean startWatching(ServerGamePacketListenerImpl connection) {
            if (isBedrock(connection.getPlayer().getUUID())) {
                return false;
            }
            return super.startWatching(connection);
        }

        @Override
        protected void onTick() {
            ticks++;
            if (level.getGameTime() % 20L == 0L && !level.getBlockState(pos).is(Blocks.BEDROCK)) {
                level.getServer().execute(() -> detach(level, pos));
                return;
            }
            if (crystal != null) {
                animate();
            }
        }
    }

    /** Shows the lookalike over the bedrock at {@code pos}; does nothing if it already shows. */
    static void attach(ServerLevel level, BlockPos pos) {
        Key key = new Key(WildLodestones.dimensionKey(level), pos.asLong());
        if (LIVE.containsKey(key)) {
            return;
        }
        BlockPos fixed = pos.immutable();
        LIVE.put(key, ChunkAttachment.ofTicking(new Holder(level, fixed, key), level, Vec3.atLowerCornerOf(fixed)));
    }

    /** Removes the lookalike at {@code pos}, if any. */
    static void detach(ServerLevel level, BlockPos pos) {
        HolderAttachment attachment = LIVE.remove(new Key(WildLodestones.dimensionKey(level), pos.asLong()));
        if (attachment != null) {
            attachment.destroy();
        }
    }

    /** Forgets the displays of one chunk that is unloading (Polymer drops them with the chunk). */
    static void dropChunk(ServerLevel level, net.minecraft.world.level.ChunkPos chunk) {
        String dimension = WildLodestones.dimensionKey(level);
        LIVE.entrySet().removeIf(e -> {
            if (!e.getKey().dimension().equals(dimension)) {
                return false;
            }
            BlockPos pos = BlockPos.of(e.getKey().pos());
            if ((pos.getX() >> 4) != chunk.x() || (pos.getZ() >> 4) != chunk.z()) {
                return false;
            }
            e.getValue().destroy();
            return true;
        });
    }

    static boolean attached(ServerLevel level, BlockPos pos) {
        return LIVE.containsKey(new Key(WildLodestones.dimensionKey(level), pos.asLong()));
    }
}
