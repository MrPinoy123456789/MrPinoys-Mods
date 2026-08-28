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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import thingy.api.VirtualTag;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
        return VirtualTag.is(stack, KEY, "sigil");
    }

    public static boolean isRolled(ItemStack stack) {
        CompoundTag tag = KamuTag.root(stack);
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
        CompoundTag tag = KamuTag.root(stack);
        if (tag == null) {
            return false;
        }
        CompoundTag inner = tag.getCompoundOrEmpty(KEY);
        int tier = inner.getIntOr("sigil", 0);
        boolean rolled = inner.getBooleanOr("rolled", false);
        return tier > 0 && !rolled;
    }

    public static int tier(ItemStack stack) {
        CompoundTag tag = KamuTag.root(stack);
        if (tag == null) {
            return 0;
        }
        return tag.getCompoundOrEmpty(KEY).getIntOr("sigil", 0);
    }

    public static long seed(ItemStack stack) {
        CompoundTag tag = KamuTag.root(stack);
        if (tag == null) {
            return 0;
        }
        return tag.getCompoundOrEmpty(KEY).getLongOr("seed", 0);
    }

    public static int counter(ItemStack stack) {
        CompoundTag tag = KamuTag.root(stack);
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
                    ItemStack rolled = new ItemStack(resolveEgg(seed), 1);
                    roll(rolled, player, roll, seed, next, catalog);
                    player.getInventory().setItem(i, rolled);
                    changed = true;
                } else if (isRolled(stack) && stack.getItem() == Items.ECHO_SHARD) {
                    player.getInventory().setItem(i, migrate(stack, catalog));
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
        EntityType<?> type = typeOf(stack);
        if (type != null) {
            lore.add(type.getDescription().copy().withStyle(ChatFormatting.WHITE));
        }
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

    /**
     * Pick a spawn-egg item from the configured mob_pool using the roll seed.
     * Falls back to a zombie egg if the selected entry has no egg.
     */
    private static Item resolveEgg(long seed) {
        List<String> pool = KamuTotemsConfig.list("boss", "mob_pool", List.of("minecraft:zombie"));
        String id = pool.get(new Random(seed).nextInt(pool.size()));
        Identifier eggId = Identifier.parse(id + "_spawn_egg");
        return BuiltInRegistries.ITEM.getOptional(eggId)
                .filter(item -> item instanceof SpawnEggItem)
                .orElse(Items.ZOMBIE_SPAWN_EGG);
    }

    /**
     * Pick a random spawn-egg item from the configured mob_pool.
     */
    private static Item resolveEgg() {
        return resolveEgg(ThreadLocalRandom.current().nextLong());
    }

    /**
     * Resolve the EntityType to use for a fresh (no-item) summon from the pool.
     */
    public static EntityType<?> resolveEntityType(long seed) {
        List<String> pool = KamuTotemsConfig.list("boss", "mob_pool", List.of("minecraft:zombie"));
        String id = pool.get(new Random(seed).nextInt(pool.size()));
        return BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(id))
                .orElse(EntityTypes.ZOMBIE);
    }

    /**
     * Read the EntityType bound to an actual spawn-egg stack.
     */
    public static EntityType<?> typeOf(ItemStack stack) {
        if (!(stack.getItem() instanceof SpawnEggItem)) {
            return null;
        }
        // SpawnEggItem.getType(ItemStack) verified against the merged jar for 26.2.
        return SpawnEggItem.getType(stack);
    }

    /**
     * Convert an in-the-wild ECHO_SHARD sigil to a real spawn-egg base,
     * preserving the roll it already carries.
     */
    public static ItemStack migrate(ItemStack old, KamuCatalog cat) {
        int t = tier(old);
        long s = seed(old);
        int c = counter(old);
        BossRoll roll = BossRoll.forSeed(s, t, cat);
        if (roll == null) {
            return old;
        }
        ItemStack fresh = new ItemStack(resolveEgg(s), 1);
        roll(fresh, null, roll, s, c, cat);
        Component name = old.get(DataComponents.ITEM_NAME);
        if (name != null) {
            fresh.set(DataComponents.ITEM_NAME, name);
        }
        return fresh;
    }

    public static ItemStack makeRolledSigil(int tier, BossRoll roll, long seed, int counter, KamuCatalog cat) {
        ItemStack stack = new ItemStack(resolveEgg(), 1);
        roll(stack, null, roll, seed, counter, cat);
        return stack;
    }

    public static boolean isRandomSigil(ItemStack stack) {
        return randomTier(stack) > 0;
    }

    public static int randomTier(ItemStack stack) {
        CompoundTag tag = KamuTag.root(stack);
        if (tag == null) {
            return 0;
        }
        CompoundTag inner = tag.getCompoundOrEmpty(KEY);
        return inner.getIntOr("random_sigil", 0);
    }

    /**
     * A sealed "mystery egg" that reveals its bound mob only when right-clicked.
     * Useful for shops: the player knows the trial tier but not which mob they
     * will get until they open it.
     */
    public static ItemStack makeRandomSigil(int tier) {
        ItemStack stack = new ItemStack(Items.EGG, 1);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag inner = new CompoundTag();
            inner.putInt("random_sigil", tier);
            tag.put(KEY, inner);
        });
        stack.set(DataComponents.ITEM_NAME,
                Component.literal("Mystery Sigil Egg — Trial " + roman(tier))
                        .withStyle(ChatFormatting.LIGHT_PURPLE));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Right-click to reveal a bound Trial " + roman(tier) + " sigil.")
                        .withStyle(ChatFormatting.GRAY))));
        stack.set(DataComponents.MAX_STACK_SIZE, 1);
        return stack;
    }

    public static InteractionResult onUseRandom(Player player, Level level, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel sl)) {
            return isRandomSigil(stack) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        int tier = randomTier(stack);
        if (tier <= 0) {
            return InteractionResult.PASS;
        }
        BossRoll roll = BossRoll.forSeed(ThreadLocalRandom.current().nextLong(), tier, catalog);
        if (roll == null) {
            sp.sendSystemMessage(Component.literal("No affixes are available for that trial.")
                    .withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }
        ItemStack sigil = makeRolledSigil(tier, roll, ThreadLocalRandom.current().nextLong(), 0, catalog);
        stack.shrink(1);
        player.setItemInHand(hand, stack.isEmpty() ? ItemStack.EMPTY : stack);
        if (!sp.getInventory().add(sigil)) {
            sp.drop(sigil, false);
        }
        sp.sendSystemMessage(Component.literal("The egg cracks open, revealing a Trial " + roman(tier) + " sigil.")
                .withStyle(ChatFormatting.DARK_PURPLE));
        return InteractionResult.SUCCESS;
    }

    public static InteractionResult onUseRandomBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        return onUseRandom(player, level, hand);
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
        ItemStack stack = new ItemStack(resolveEgg(), 1);
        roll(stack, null, roll, BossHost.worldSeed(), 0, catalog);
        stack.set(DataComponents.ITEM_NAME,
                Component.literal("Sigil of the First Trial")
                        .withStyle(ChatFormatting.GOLD));
        return stack;
    }

    public static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
        return onUseItem(player, level, hand, null);
    }

    public static InteractionResult onUseItem(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel sl)) {
            return isSigil(stack) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        if (isRolled(stack) && stack.getItem() == Items.ECHO_SHARD) {
            stack = migrate(stack, catalog);
            player.setItemInHand(hand, stack);
        }

        if (!(stack.getItem() instanceof SpawnEggItem) || !isRolled(stack)) {
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

        EntityType<?> type = typeOf(stack);
        Vec3 pos;
        if (hit != null) {
            BlockPos spawnPos = hit.getBlockPos().relative(hit.getDirection());
            pos = Vec3.atBottomCenterOf(spawnPos);
        } else {
            pos = sp.position().add(sp.getLookAngle().scale(2.0));
        }
        Boss boss = Boss.spawn(sl, pos, sp.getYRot(), sp, tier, roll, true, counter(stack), seed(stack), type, catalog);
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


    static String roman(int tier) {
        return switch (tier) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            default -> String.valueOf(tier);
        };
    }
}
