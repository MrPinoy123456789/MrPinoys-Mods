package smalltalk.task;

import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import smalltalk.social.FamiliarityAttachment;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SPEC.md section 12.4: builds, validates, and completes Fetch tasks --
 * "bring me a cooked salmon," an item predicate and a count, nothing else.
 */
public final class FetchTaskLogic {

    /** SPEC.md section 12.6: modest, never scaling, never emeralds. */
    private static final int REWARD_COUNT = 1;
    private static final int REWARD_FAMILIARITY = 6;

    /**
     * Profession -&gt; reward pool (SPEC.md section 12.4: "a static
     * per-profession list in FetchTaskLogic is enough"). Villager profession
     * registry path (e.g. {@code "farmer"}) -&gt; candidate reward items.
     */
    private static final Map<String, List<Item>> PROFESSION_REWARDS = Map.ofEntries(
            Map.entry("farmer", List.of(Items.CARROT, Items.PUMPKIN_PIE, Items.BREAD)),
            Map.entry("fisherman", List.of(Items.COOKED_COD, Items.COOKED_SALMON)),
            Map.entry("fletcher", List.of(Items.ARROW, Items.FEATHER)),
            Map.entry("librarian", List.of(Items.PAPER, Items.BOOK)),
            Map.entry("cleric", List.of(Items.GLOWSTONE_DUST, Items.REDSTONE)),
            Map.entry("toolsmith", List.of(Items.FLINT, Items.IRON_NUGGET)),
            Map.entry("weaponsmith", List.of(Items.IRON_NUGGET, Items.FLINT)),
            Map.entry("armorer", List.of(Items.IRON_NUGGET, Items.LEATHER)),
            Map.entry("shepherd", List.of(Items.STRING, Items.FEATHER)),
            Map.entry("butcher", List.of(Items.COOKED_PORKCHOP, Items.COOKED_BEEF)),
            Map.entry("leatherworker", List.of(Items.LEATHER, Items.RABBIT_HIDE)),
            Map.entry("mason", List.of(Items.CLAY_BALL, Items.BRICK)),
            Map.entry("cartographer", List.of(Items.PAPER, Items.COMPASS))
    );

    /** Used for nitwits, unemployed villagers, or any profession not in the table above. */
    private static final List<Item> FALLBACK_REWARD = List.of(Items.BREAD, Items.APPLE, Items.SUGAR);

    /** What a resident might ask for -- deliberately mundane (SPEC.md section 12.4's "cooked salmon" example). */
    private static final List<Item> FETCHABLE_ITEMS = List.of(
            Items.COOKED_SALMON, Items.BREAD, Items.WHEAT, Items.APPLE,
            Items.COAL, Items.STRING, Items.EGG, Items.SUGAR
    );

    private FetchTaskLogic() {}

    /** Picks one of {@link #FETCHABLE_ITEMS}, purely so {@code RequestOfferer} doesn't need item knowledge of its own. */
    public static Identifier randomFetchableItem(RandomSource random) {
        Item item = FETCHABLE_ITEMS.get(random.nextInt(FETCHABLE_ITEMS.size()));
        return BuiltInRegistries.ITEM.getKey(item);
    }

    public static Task offer(UUID issuer, UUID assignee, Identifier item, int count,
                              long issuedTick, long expiresTick) {
        CompoundTag payload = new CompoundTag();
        payload.putString("item", item.toString());
        payload.putInt("count", count);
        return new Task(UUID.randomUUID(), issuer, assignee, TaskType.FETCH.name(), payload,
                issuedTick, expiresTick, TaskState.OFFERED);
    }

    public static Identifier wantedItem(Task task) {
        Identifier id = Identifier.tryParse(task.payload().getStringOr("item", ""));
        return id == null ? Identifier.fromNamespaceAndPath("minecraft", "air") : id;
    }

    public static int wantedCount(Task task) {
        return task.payload().getIntOr("count", 0);
    }

    /**
     * Re-verifies the player's inventory against the payload -- called at
     * completion-confirm time, not just when the completion dialog opened,
     * same re-verify discipline as {@code interaction.GiftHandler}'s
     * gift-confirm (SPEC.md section 6.2).
     */
    public static boolean hasItems(ServerPlayer player, Task task) {
        int needed = wantedCount(task);
        return needed > 0 && countInInventory(player, wantedItem(task)) >= needed;
    }

    /** Shrinks matching stacks by the payload's count. Only call after {@link #hasItems} confirms it's possible. */
    public static void consumeItems(ServerPlayer player, Task task) {
        Identifier wanted = wantedItem(task);
        int remaining = wantedCount(task);
        for (int slot = 0; slot < player.getInventory().getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(wanted)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
        }
    }

    /**
     * Consumes the handed-in items, gives a modest profession-keyed reward
     * plus familiarity, and plays the completion feedback -- the whole
     * SPEC.md section 12.6 reward policy in one place. Callers must have
     * already re-verified {@link #hasItems}.
     */
    public static void applyReward(Villager villager, ServerLevel level, ServerPlayer player, Task task, long tick) {
        consumeItems(player, task);

        ItemStack reward = rewardItem(villager);
        if (!player.getInventory().add(reward)) {
            player.drop(reward, false);
        }

        FamiliarityAttachment.recordInteraction(villager, player.getUUID(), tick,
                REWARD_FAMILIARITY, "completed_request:fetch");

        level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                villager.getX(), villager.getEyeY(), villager.getZ(), 6, 0.4, 0.4, 0.4, 0.0);
        level.playSound(null, villager.getX(), villager.getY(), villager.getZ(),
                SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 1.0f, 1.0f);
    }

    private static ItemStack rewardItem(Villager villager) {
        Holder<VillagerProfession> profession = villager.getVillagerData().profession();
        String path = BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession.value()).getPath();
        List<Item> pool = PROFESSION_REWARDS.getOrDefault(path, FALLBACK_REWARD);
        Item chosen = pool.get(villager.getRandom().nextInt(pool.size()));
        return new ItemStack(chosen, REWARD_COUNT);
    }

    private static int countInInventory(ServerPlayer player, Identifier wanted) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(wanted)) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
