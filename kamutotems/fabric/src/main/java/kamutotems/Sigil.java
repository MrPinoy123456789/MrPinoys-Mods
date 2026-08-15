package kamutotems;

import kamutotems.core.BossRoll;
import kamutotems.core.Kamu;
import kamutotems.core.KamuCatalog;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A sigil is an item the shop sells. It starts unrolled, is rolled by a server
 * sweep, and is consumed to summon a boss whose kamu were printed on its lore.
 */
public final class Sigil {

    private static final String KEY = "kamutotems";

    private static KamuCatalog catalog;
    private static long worldSeed;

    private Sigil() {}

    public static void init(KamuCatalog c, long seed) {
        catalog = c;
        worldSeed = seed;
    }

    public static boolean isSigil(ItemStack stack) {
        return tier(stack) > 0;
    }

    public static boolean isRolled(ItemStack stack) {
        CompoundTag tag = tag(stack);
        if (tag == null) {
            return false;
        }
        CompoundTag inner = tag.getCompoundOrEmpty(KEY);
        if (!inner.contains("rolled") || !inner.getBooleanOr("rolled", false)) {
            return false;
        }
        return inner.contains("seed") && inner.contains("counter") && inner.contains("sigil");
    }

    public static boolean needsRoll(ItemStack stack) {
        CompoundTag tag = tag(stack);
        if (tag == null) {
            return false;
        }
        CompoundTag inner = tag.getCompoundOrEmpty(KEY);
        int tier = inner.getIntOr("sigil", 0);
        boolean rolled = inner.getBooleanOr("rolled", false);
        return tier > 0 && !rolled;
    }

    public static int tier(ItemStack stack) {
        CompoundTag tag = tag(stack);
        if (tag == null) {
            return 0;
        }
        return tag.getCompoundOrEmpty(KEY).getIntOr("sigil", 0);
    }

    public static long seed(ItemStack stack) {
        CompoundTag tag = tag(stack);
        if (tag == null) {
            return 0;
        }
        return tag.getCompoundOrEmpty(KEY).getLongOr("seed", 0);
    }

    public static int counter(ItemStack stack) {
        CompoundTag tag = tag(stack);
        if (tag == null) {
            return 0;
        }
        return tag.getCompoundOrEmpty(KEY).getIntOr("counter", 0);
    }

    public static BossRoll rollFrom(ItemStack stack, KamuCatalog cat) {
        int t = tier(stack);
        long s = seed(stack);
        if (t <= 0) {
            return null;
        }
        return BossRoll.forSeed(s, t, cat);
    }

    /**
     * Sweep all player inventories for unrolled sigils and roll them.
     */
    public static boolean sweep(MinecraftServer server, Map<UUID, Integer> counters) {
        boolean changed = false;
        if (server == null || catalog == null) {
            return false;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (needsRoll(stack)) {
                    int t = tier(stack);
                    int next = counters.getOrDefault(player.getUUID(), 0) + 1;
                    counters.put(player.getUUID(), next);
                    long seed = hash(player.getUUID(), next, worldSeed);
                    BossRoll roll = BossRoll.forSeed(seed, t, catalog);
                    roll(stack, player, roll, seed, next, catalog);
                    changed = true;
                }
            }
        }
        return changed;
    }

    public static void roll(ItemStack stack, ServerPlayer player, BossRoll roll,
                            long seed, int counter, KamuCatalog cat) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag inner = new CompoundTag();
            inner.putInt("sigil", roll.tier());
            inner.putBoolean("rolled", true);
            inner.putLong("seed", seed);
            inner.putInt("counter", counter);
            ListTag list = new ListTag();
            for (String id : roll.kamuIds()) {
                list.add(StringTag.valueOf(id));
            }
            inner.put("kamu", list);
            tag.put(KEY, inner);
        });

        List<Component> lore = new ArrayList<>();
        for (String id : roll.kamuIds()) {
            Kamu kamu = cat.get(id);
            if (kamu != null) {
                lore.add(Component.literal(kamu.displayName()).withStyle(ChatFormatting.GRAY));
            }
        }
        if (lore.isEmpty()) {
            lore.add(Component.literal("The spirits are unknown.").withStyle(ChatFormatting.GRAY));
        }

        stack.set(DataComponents.ITEM_NAME,
                Component.literal("Sealed Sigil — Trial " + roman(roll.tier()))
                        .withStyle(ChatFormatting.AQUA));
        stack.set(DataComponents.LORE, new ItemLore(lore));
        stack.set(DataComponents.MAX_STACK_SIZE, 1);
    }

    public static ItemStack makeRolledSigil(int tier, BossRoll roll, long seed, int counter, KamuCatalog cat) {
        ItemStack stack = new ItemStack(Items.ECHO_SHARD, 1);
        roll(stack, null, roll, seed, counter, cat);
        return stack;
    }

    /**
     * The Sigil of the First Trial: the reward for finishing the daily chain.
     *
     * <p>Unlike the bought sigils it arrives <b>already rolled and readable</b>,
     * because the free daily boss is public knowledge -- everyone faces the same
     * one on a given date (SPEC section 7.1). The paid tiers are the gamble;
     * this one is the scheduled, discussable event, so hiding its contents
     * would remove the only thing that makes it communal.
     */
    public static ItemStack makeFirstTrial(ServerPlayer player) {
        String dateKey = BossHost.todayKey();
        BossRoll roll = BossRoll.forDate(dateKey, BossHost.worldSeed(), catalog);
        if (roll == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(Items.ECHO_SHARD, 1);
        roll(stack, null, roll, BossHost.worldSeed(), 0, catalog);
        stack.set(DataComponents.ITEM_NAME,
                Component.literal("Sigil of the First Trial")
                        .withStyle(ChatFormatting.GOLD));
        return stack;
    }

    public static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
        if (!(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel sl)) {
            return InteractionResult.PASS;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!isRolled(stack)) {
            if (isSigil(stack)) {
                sp.sendSystemMessage(Component.literal("This sigil is still sealed.")
                        .withStyle(ChatFormatting.RED));
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        }

        if (BossHost.hasActive(sp.getUUID())) {
            sp.sendSystemMessage(Component.literal("You already have an active boss.")
                    .withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }

        int tier = tier(stack);
        BossRoll roll = rollFrom(stack, catalog);
        if (roll == null) {
            sp.sendSystemMessage(Component.literal("This sigil cannot be read; it will be re-rolled.")
                    .withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }

        int radius = KamuTotemsConfig.i("boss", "no_summon_radius", 24);
        BlockPos spawn = sl.getRespawnData().pos();
        double dist = player.position().distanceTo(Vec3.atCenterOf(spawn));
        if (dist < radius) {
            sp.sendSystemMessage(Component.literal("You are too close to spawn to summon here.")
                    .withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }

        // Uses the SoundEvents constant directly; no registry lookup needed.
        Chime.play(sp, SoundEvents.NOTE_BLOCK_BASS,
                0.25f, 0.8f);

        Boss boss = Boss.spawn(sp, tier, roll, true, counter(stack), seed(stack), catalog);
        if (boss == null) {
            sp.sendSystemMessage(Component.literal("The sigil failed to call a boss.")
                    .withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }
        BossHost.track(boss);

        stack.shrink(1);
        player.setItemInHand(hand, stack.isEmpty() ? ItemStack.EMPTY : stack);
        sp.sendSystemMessage(Component.literal("A bearer of "
                        + roll.kamuIds().size() + " kamu answers the sigil.")
                .withStyle(ChatFormatting.DARK_PURPLE));
        return InteractionResult.SUCCESS;
    }

    private static long hash(UUID player, int counter, long seed) {
        long h = player.getMostSignificantBits();
        h = h * 31 + player.getLeastSignificantBits();
        h = h * 31 + counter;
        h = h * 31 + seed;
        return h;
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

    private static String roman(int tier) {
        return switch (tier) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            default -> String.valueOf(tier);
        };
    }
}
