package foundry.mc;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.BlockBoundAttachment;
import eu.pb4.polymer.virtualentity.api.elements.BlockDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.InteractionElement;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.VirtualElement;
import foundry.core.FurnitureDef;
import foundry.core.Ident;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Furniture runtime. A placed piece is three things that must stay in step: a real block (barrier for solid, an
 * invisible level 0 light block for walk-through), a record in {@link FurnitureStore}, and a virtual holder with a
 * display and an interaction element. The record is authoritative; the holder is a cache rebuilt on chunk load, and
 * the block is checked against the record every time. No Mixins; no per-piece Java.
 */
public final class Furniture {
    private Furniture() {}

    public static void init() {
        // Never touch the level from inside the chunk load callback: level.getBlockState waits for this very chunk and
        // deadlocks the server thread (watchdog crash). Run after the load finishes, and only if still loaded.
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, isNew) ->
                queueRespawn(level, chunk.getPos().x(), chunk.getPos().z()));
        ServerTickEvents.END_SERVER_TICK.register(server -> drainQueue());
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (level instanceof ServerLevel sl && FurnitureStore.of(sl).get(pos) != null) {
                FurnitureStore.of(sl).remove(pos);
            }
        });
    }

    private record Pending(ServerLevel level, int chunkX, int chunkZ, int attempts) {}

    private static final java.util.ArrayDeque<Pending> PENDING = new java.util.ArrayDeque<>();
    private static final int MAX_ATTEMPTS = 200;

    /**
     * Asks for the holders of one chunk to be rebuilt once it is really available. The chunk is often not published yet
     * while its load callback runs, so the request waits in a queue that is retried every tick for up to ten seconds.
     */
    public static void queueRespawn(ServerLevel level, int chunkX, int chunkZ) {
        if (!FurnitureStore.of(level).inChunk(chunkX, chunkZ).isEmpty()) PENDING.add(new Pending(level, chunkX, chunkZ, 0));
    }

    private static void drainQueue() {
        for (int n = PENDING.size(); n > 0; n--) {
            Pending p = PENDING.poll();
            LevelChunk chunk = p.level().getChunkSource().getChunkNow(p.chunkX(), p.chunkZ());
            if (chunk != null) {
                respawn(p.level(), chunk);
            } else if (p.attempts() < MAX_ATTEMPTS) {
                PENDING.add(new Pending(p.level(), p.chunkX(), p.chunkZ(), p.attempts() + 1));
            } else {
                FoundryMod.LOG.warn("Foundry: chunk {},{} never became available; its furniture was not rebuilt. "
                        + "Run /foundry furniture repair once it is loaded.", p.chunkX(), p.chunkZ());
            }
        }
    }

    /** Rebuilds missing holders in every loaded chunk of every level. Returns how many holders were created. */
    public static int repairAll(net.minecraft.server.MinecraftServer server) {
        int created = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (FurnitureStore.Entry e : FurnitureStore.of(level).all()) {
                if (!level.getChunkSource().hasChunk(e.pos().getX() >> 4, e.pos().getZ() >> 4)) continue;
                FurnitureDef def = FoundryMod.loaded().furniture().get(Ident.parse(e.definition()));
                if (def == null) continue;
                if (level.getBlockState(e.pos()).is(blockFor(def).getBlock()) && spawn(level, e.pos(), def, e.facing())) created++;
            }
        }
        return created;
    }

    /** The furniture the given placing item creates, or null. */
    public static FurnitureDef forItem(Ident itemId) {
        for (FurnitureDef d : FoundryMod.loaded().furniture().values()) {
            if (d.placedBy().equals(itemId)) return d;
        }
        return null;
    }

    public static BlockState blockFor(FurnitureDef def) {
        return def.solid()
                ? Blocks.BARRIER.defaultBlockState()
                : Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 0);
    }

    /** Places furniture from {@code useOn}. Returns true when placed (the caller then succeeds the interaction). */
    public static boolean place(UseOnContext ctx, FurnitureDef def) {
        if (!(ctx.getLevel() instanceof ServerLevel level) || !(ctx.getPlayer() instanceof ServerPlayer player)) return false;
        BlockPos pos = ctx.getClickedPos();
        if (!level.getBlockState(pos).canBeReplaced()) pos = pos.relative(ctx.getClickedFace());
        if (!level.getBlockState(pos).canBeReplaced() || !level.isInWorldBounds(pos)) return false;
        Direction facing = ctx.getHorizontalDirection().getOpposite();
        FurnitureStore store = FurnitureStore.of(level);
        if (store.get(pos) != null) return false;

        level.setBlockAndUpdate(pos, blockFor(def));
        store.put(new FurnitureStore.Entry(pos, def.id().toString(), facing));
        spawn(level, pos, def, facing);
        level.playSound(null, pos, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0f, 1.0f);
        if (!player.isCreative()) ctx.getItemInHand().shrink(1);
        return true;
    }

    /** Builds the holder for a record. Does nothing when one is already attached there (no duplicates). */
    public static boolean spawn(ServerLevel level, BlockPos pos, FurnitureDef def, Direction facing) {
        if (BlockBoundAttachment.get(level, pos) != null) return false;
        ElementHolder holder = new ElementHolder();
        float s = (float) def.scale();
        Quaternionf rotation = new Quaternionf().rotationY((float) Math.toRadians(-facing.toYRot()));
        Vector3f lift = new Vector3f(0f, (float) def.offsetY(), 0f);

        if (def.block() != null) {
            var block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(def.block().toString()));
            BlockDisplayElement display = new BlockDisplayElement(block.defaultBlockState());
            // A block display's origin is a corner: rotate about its centre, then centre that on the block.
            Vector3f half = new Vector3f(s / 2f, s / 2f, s / 2f).rotate(rotation);
            display.setScale(new Vector3f(s, s, s));
            display.setLeftRotation(rotation);
            display.setTranslation(new Vector3f(lift).sub(half));
            holder.addElement(display);
        } else {
            var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(def.item().toString()));
            ItemDisplayElement display = new ItemDisplayElement(new ItemStack(item));
            display.setItemDisplayContext(ItemDisplayContext.FIXED);
            display.setScale(new Vector3f(s, s, s));
            display.setLeftRotation(rotation);
            display.setTranslation(lift);
            holder.addElement(display);
        }

        InteractionElement click = new InteractionElement(new VirtualElement.InteractionHandler() {
            @Override
            public void interact(ServerPlayer player, InteractionHand hand, Vec3 pos, boolean sneaking) {
                // Foundry adds no click behaviour yet; vanilla block interaction rules apply (nothing happens).
            }

            @Override
            public void attack(ServerPlayer player) {
                Furniture.breakAt(level, pos, player);
            }
        });
        click.setSize((float) def.width(), (float) def.height());
        click.setOffset(new Vec3(0, -0.5, 0));
        holder.addElement(click);
        // BlockBoundAttachment.of(...) only attaches for Polymer blocks (it returns null for a vanilla barrier), so use
        // the public constructor, which attaches to any block and still tears down when the block state changes.
        new BlockBoundAttachment(holder, level.getChunkAt(pos), blockFor(def), pos, Vec3.atCenterOf(pos), false);
        return true;
    }

    /** Punching the piece breaks it, since a barrier cannot be mined. Drops the placing item outside creative. */
    public static void breakAt(ServerLevel level, BlockPos pos, ServerPlayer player) {
        breakAt(level, pos, player, !player.isCreative());
    }

    /** As {@link #breakAt(ServerLevel, BlockPos, ServerPlayer)} with the drop decided by the caller (tests use this). */
    public static void breakAt(ServerLevel level, BlockPos pos, ServerPlayer player, boolean drop) {
        FurnitureStore store = FurnitureStore.of(level);
        FurnitureStore.Entry entry = store.get(pos);
        if (entry == null) return;
        FurnitureDef def = FoundryMod.loaded().furniture().get(Ident.parse(entry.definition()));
        store.remove(pos);
        level.removeBlock(pos, false);
        level.playSound(null, pos, SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 1.0f, 1.0f);
        if (def != null && drop) {
            var item = FoundryItems.get(def.placedBy());
            if (item != null) {
                level.addFreshEntity(new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                        new ItemStack(item)));
            }
        }
    }

    /**
     * Chunk load: rebuild holders from records. A record whose block no longer matches (removed while unloaded or by a
     * command) is deleted, so records never describe air. Calling twice cannot stack holders.
     */
    public static void respawn(ServerLevel level, LevelChunk chunk) {
        FurnitureStore store = FurnitureStore.of(level);
        for (FurnitureStore.Entry e : store.inChunk(chunk.getPos().x(), chunk.getPos().z())) {
            FurnitureDef def = FoundryMod.loaded().furniture().get(Ident.parse(e.definition()));
            if (def == null) {
                FoundryMod.LOG.warn("Foundry: furniture {} at {} has no definition any more; leaving it as a plain block. "
                        + "Restore the definition file to bring it back.", e.definition(), e.pos());
                continue;
            }
            if (!level.getBlockState(e.pos()).is(blockFor(def).getBlock())) {
                FoundryMod.LOG.info("Foundry: furniture {} at {} lost its block while unloaded; record removed.",
                        e.definition(), e.pos());
                store.remove(e.pos());
                continue;
            }
            spawn(level, e.pos(), def, e.facing());
        }
    }
}
