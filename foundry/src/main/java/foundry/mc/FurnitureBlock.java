package foundry.mc;

import eu.pb4.polymer.core.api.block.SimplePolymerBlock;
import eu.pb4.polymer.virtualentity.api.BlockWithElementHolder;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.elements.BlockDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import foundry.core.FurnitureDef;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.storage.loot.LootParams;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * One registered block per furniture definition. It is a real block: it has hardness, drops its placing item through
 * the normal drop path, is placed and saved like any block, and keeps a cardinal {@code FACING}. Clients see a vanilla
 * stand-in block (barrier when solid, structure void when walk-through) with a display entity drawn over it; Polymer
 * creates that display from {@link #createElementHolder} when the block is placed or its chunk loads, so there is no
 * record to keep and nothing to rebuild by hand. See docs/FURNITURE_DECISION.md.
 */
public final class FurnitureBlock extends SimplePolymerBlock implements BlockWithElementHolder {
    public static final net.minecraft.world.level.block.state.properties.EnumProperty<Direction> FACING =
            HorizontalDirectionalBlock.FACING;

    private final FurnitureDef def;

    public FurnitureBlock(BlockBehaviour.Properties properties, FurnitureDef def) {
        super(properties, def.solid() ? Blocks.BARRIER : Blocks.STRUCTURE_VOID);
        this.def = def;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public FurnitureDef definition() {
        return def;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
        return (def.solid() ? Blocks.BARRIER : Blocks.STRUCTURE_VOID).defaultBlockState();
    }

    /** The stand-in looks different from this block's real hardness, so let Polymer drive mining on the server. */
    @Override
    public boolean handleMiningOnServer(ItemStack tool, BlockState state, BlockPos pos, ServerPlayer player) {
        return true;
    }

    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        var item = FoundryItems.get(def.placedBy());
        return item == null ? List.of() : List.of(new ItemStack(item));
    }

    @Override
    public ElementHolder createElementHolder(ServerLevel level, BlockPos pos, BlockState state) {
        ElementHolder holder = new ElementHolder();
        Direction facing = state.getValue(FACING);
        float s = (float) def.scale();
        Quaternionf rotation = new Quaternionf().rotationY((float) Math.toRadians(-facing.toYRot()));
        Vector3f lift = new Vector3f(0f, (float) def.offsetY(), 0f);

        if (def.block() != null) {
            var block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(def.block().toString()));
            BlockDisplayElement display = new BlockDisplayElement(block.defaultBlockState());
            // A block display's origin is a corner: rotate about its centre, then centre that on this block.
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
        return holder;
    }
}
