package foundry.mc;

import foundry.core.FurnitureDef;
import foundry.core.Ident;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Registration and placement for furniture blocks. Placement follows {@code BlockItem}: same replaceable check,
 * collision check, state for placement, placed sound and consumption rules, so a piece behaves like any block.
 * Registered blocks cannot change while the server runs; new or changed pieces need a restart.
 */
public final class Furniture {
    private static final Map<Ident, FurnitureBlock> BLOCKS = new ConcurrentHashMap<>();

    private Furniture() {}

    public static void register(FurnitureDef def) {
        Identifier id = Identifier.parse(def.id().toString());
        BlockBehaviour.Properties props = BlockBehaviour.Properties.of()
                .setId(ResourceKey.create(Registries.BLOCK, id))
                .strength((float) def.hardness())
                .sound(SoundType.WOOD)
                .noOcclusion();
        if (!def.solid()) props.noCollision();
        BLOCKS.put(def.id(), Registry.register(BuiltInRegistries.BLOCK, id, new FurnitureBlock(props, def)));
    }

    public static FurnitureBlock block(Ident id) {
        return BLOCKS.get(id);
    }

    public static Map<Ident, FurnitureBlock> all() {
        return Map.copyOf(BLOCKS);
    }

    /** The furniture the given placing item creates, or null. */
    public static FurnitureDef forItem(Ident itemId) {
        for (FurnitureDef d : FoundryMod.loaded().furniture().values()) {
            if (d.placedBy().equals(itemId) && BLOCKS.containsKey(d.id())) return d;
        }
        return null;
    }

    /** Places furniture from {@code useOn}. Returns true when placed (the caller then succeeds the interaction). */
    public static boolean place(UseOnContext use, FurnitureDef def) {
        FurnitureBlock block = BLOCKS.get(def.id());
        if (block == null || !(use.getLevel() instanceof ServerLevel level) || !(use.getPlayer() instanceof ServerPlayer player)) {
            return false;
        }
        BlockPlaceContext context = new BlockPlaceContext(use);
        if (!context.canPlace()) return false;
        BlockPos pos = context.getClickedPos();
        BlockState state = block.getStateForPlacement(context);
        if (state == null || !state.canSurvive(level, pos) || !level.isInWorldBounds(pos)) return false;
        if (!level.isUnobstructed(state, pos, CollisionContext.of(player))) return false;
        if (!level.setBlock(pos, state, 11)) return false;
        block.setPlacedBy(level, pos, state, player, use.getItemInHand());
        level.gameEvent(GameEvent.BLOCK_PLACE, pos, GameEvent.Context.of(player, state));
        SoundType sound = state.getSoundType();
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0f) / 2.0f,
                sound.getPitch() * 0.8f);
        if (!player.isCreative()) use.getItemInHand().shrink(1);
        return true;
    }
}
