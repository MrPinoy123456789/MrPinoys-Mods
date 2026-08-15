package kamutotems;

import kamutotems.core.BossRoll;
import kamutotems.core.KamuCatalog;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

/**
 * A boss stone is any vanilla item carrying {@code custom_data} with a tier.
 * Another mod can hand this out as a reward that skips the quest and goes
 * straight to a boss fight.
 *
 * <p>Shape:
 * <pre>
 *   minecraft:custom_data = { "kamutotems": { "boss_stone": 2 } }
 * </pre>
 *
 * <p>Right-click to exchange the stone for a rolled sigil of that tier. The
 * stone is consumed. If the tier is invalid or the roll cannot be made, the
 * stone stays in hand.
 */
public final class BossStone {

    private static final String KEY = "kamutotems";
    private static final String STONE_KEY = "boss_stone";

    private BossStone() {}

    public static boolean isBossStone(ItemStack stack) {
        return tier(stack) > 0;
    }

    public static int tier(ItemStack stack) {
        CompoundTag root = tag(stack);
        if (root == null) {
            return 0;
        }
        CompoundTag inner = root.getCompoundOrEmpty(KEY);
        return inner.getIntOr(STONE_KEY, 0);
    }

    public static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)
                || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }
        ItemStack held = player.getItemInHand(hand);
        int tier = tier(held);
        if (tier <= 0) {
            return InteractionResult.PASS;
        }
        if (tier < 1 || tier > 4) {
            serverPlayer.sendSystemMessage(Component.literal("That stone is not attuned to a trial.")
                    .withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }

        KamuCatalog catalog = KamuData.catalog();
        if (catalog == null) {
            serverPlayer.sendSystemMessage(Component.literal("The spirits are not listening right now.")
                    .withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }

        long seed = serverLevel.getRandom().nextLong();
        BossRoll roll = BossRoll.forSeed(seed, tier, catalog);
        if (roll == null) {
            KamuTotemsMod.LOG.error("BossStone could not roll a sigil for tier {}", tier);
            serverPlayer.sendSystemMessage(Component.literal("The stone refuses to reveal its trial.")
                    .withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }

        ItemStack sigil = Sigil.makeRolledSigil(tier, roll, seed, 0, catalog);
        if (sigil == null || sigil.isEmpty()) {
            KamuTotemsMod.LOG.error("BossStone could not mint a sigil for tier {}", tier);
            serverPlayer.sendSystemMessage(Component.literal("The stone could not form a sigil.")
                    .withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }

        if (!serverPlayer.getInventory().add(sigil)) {
            serverPlayer.drop(sigil, false);
            KamuTotemsMod.LOG.info("Boss stone reward for {} would not fit; dropped at feet",
                    serverPlayer.getName().getString());
        }

        serverPlayer.sendSystemMessage(Component.literal("A Sealed Sigil takes shape in your hands.")
                .withStyle(ChatFormatting.DARK_PURPLE));

        held.shrink(1);
        if (held.isEmpty()) {
            player.setItemInHand(hand, ItemStack.EMPTY);
        }
        return InteractionResult.SUCCESS;
    }

    private static CompoundTag tag(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return null;
        }
        return data.copyTag();
    }
}
