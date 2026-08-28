package thingy;

import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import thingy.api.Give;
import thingy.api.VirtualTag;

import java.util.HashMap;
import java.util.Map;

/**
 * Smashy Mortar: hold it in the main hand, hold a crushable item in the off-hand,
 * right-click. Returns the crushed product and applies a short cooldown.
 */
public final class Mortar {

    public static final String ID = "smashy_mortar";
    private static final int COOLDOWN = 20;

    private Mortar() {}

    private record Output(Item item, int count) {}

    private static final Map<Item, Output> CRUSH = new HashMap<>();

    static {
        CRUSH.put(Items.RAW_IRON, new Output(Items.IRON_NUGGET, 6));
        CRUSH.put(Items.RAW_GOLD, new Output(Items.GOLD_NUGGET, 6));
        CRUSH.put(Items.RAW_COPPER, new Output(Items.COPPER_NUGGET, 6));
        CRUSH.put(Items.GRAVEL, new Output(Items.FLINT, 1));
        CRUSH.put(Items.BONE, new Output(Items.BONE_MEAL, 6));
        CRUSH.put(Items.BLACKSTONE, new Output(Items.GOLD_NUGGET, 1));
    }

    public static void register(ItemRegistry registry) {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            return tryCrush(serverPlayer, hand);
        });
    }

    private static InteractionResult tryCrush(ServerPlayer player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }

        ItemStack held = player.getMainHandItem();
        if (!VirtualTag.is(held, "wondrous", ID)) {
            return InteractionResult.PASS;
        }

        ItemStack input = player.getOffhandItem();
        if (input.isEmpty()) {
            Chime.say(player, "nothing to crush");
            Chime.reject(player);
            return InteractionResult.SUCCESS_SERVER;
        }

        Item inputItem = input.getItem();
        Output output = CRUSH.get(inputItem);
        if (output == null) {
            Chime.say(player, "wont crush");
            Chime.reject(player);
            return InteractionResult.SUCCESS_SERVER;
        }

        if (inputItem == output.item) {
            // Safety guard: no self-conversion infinite loop.
            Chime.say(player, "wont crush");
            Chime.reject(player);
            return InteractionResult.SUCCESS_SERVER;
        }

        if (inputItem == Items.BLACKSTONE) {
            if (player.getRandom().nextFloat() >= 0.25f) {
                Chime.say(player, "no nuggets");
                Chime.reject(player);
                return InteractionResult.SUCCESS_SERVER;
            }
        }

        input.shrink(1);
        player.getInventory().setChanged();

        Give.giveOrDrop(player, new ItemStack(output.item, output.count()));

        player.getCooldowns().addCooldown(held, COOLDOWN);
        Chime.say(player, "crushed");
        Chime.crush(player);
        return InteractionResult.SUCCESS_SERVER;
    }
}
