package pocketdungeons;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.ChunkAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.HolderAttachment;
import eu.pb4.polymer.virtualentity.api.elements.BlockDisplayElement;
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
 * Makes a wild marker (a bedrock block, see {@link WildLodestones}) look like a jigsaw block to Java clients: one
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

    private WildMarkerDisplays() {}

    /** Floodgate gives Bedrock players a UUID whose high half is zero; Java accounts never have one. */
    static boolean isBedrock(java.util.UUID id) {
        return id.getMostSignificantBits() == 0L;
    }

    private static final class Holder extends ElementHolder {
        private final ServerLevel level;
        private final BlockPos pos;
        private final Key key;

        Holder(ServerLevel level, BlockPos pos, Key key) {
            this.level = level;
            this.pos = pos;
            this.key = key;
            BlockDisplayElement display = new BlockDisplayElement(LOOKALIKE);
            display.setScale(new Vector3f(1F + GROW, 1F + GROW, 1F + GROW));
            display.setTranslation(new Vector3f(-GROW / 2F, -GROW / 2F, -GROW / 2F));
            addElement(display);
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
            if (level.getGameTime() % 20L == 0L && !level.getBlockState(pos).is(Blocks.BEDROCK)) {
                level.getServer().execute(() -> detach(level, pos));
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
