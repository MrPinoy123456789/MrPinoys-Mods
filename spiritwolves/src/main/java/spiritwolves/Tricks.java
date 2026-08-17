package spiritwolves;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dig and Speak -- the two tricks you perform <em>at</em> the wolf by showing it
 * something (SPEC.md section 18.5).
 *
 * <p>Show it an ore, ingot, or raw mineral and it noses out matching ore nearby;
 * show it a mob drop and it names the creature that drop came from. One rule
 * underneath both: <b>Dig finds things by their material, Speak finds things by
 * their scent.</b> The presented item is never consumed.
 *
 * <p><b>Dig only reveals exposed ore</b> -- a block with at least one face open
 * to air or another see-through block. That is the whole design constraint: a
 * wolf that sniffs out a seam you could walk to is a bloodhound, a wolf that
 * sees through forty blocks of stone is an X-ray cheat. The check is in
 * {@link #isExposed}; do not relax it without deciding you want the other mod.
 *
 * <p><b>Why sneak.</b> The interaction is sneak + right-click, not a plain
 * right-click, because plain right-click on a tamed wolf is vanilla feeding and
 * rotten flesh -- a Speak reagent -- is wolf food. Intercepting the plain click
 * would silently break healing your wolf. Sneak + empty hand is already
 * mark-prey ({@link Senses}), so sneak + item slots in beside it.
 */
public final class Tricks {

    /** How long revealed mobs glow, and how long revealed ore keeps sparkling. (5 minutes) */
    private static final int REVEAL_TICKS = 6000;

    /** Ticks between sparkle pulses on revealed ore. */
    private static final int PULSE_INTERVAL_TICKS = 20;

    /** Cap on revealed ore blocks per use, so a rich cave doesn't flood the client. */
    private static final int MAX_REVEALED_BLOCKS = 32;

    /**
     * Minimum ticks between trick uses for one player. Dig scans a cube up to 49
     * blocks on a side, which is cheap once and rude every tick, so the sniff
     * costs a beat -- which is also how a real dog would take it.
     */
    private static final int USE_COOLDOWN_TICKS = 40;

    /** Item shown -> ore blocks it smells like. */
    private static final Map<Item, Set<Block>> ORE_BY_ITEM = new LinkedHashMap<>();

    /** Item shown -> mobs that drop it. */
    private static final Map<Item, Set<EntityType<?>>> MOBS_BY_DROP = new LinkedHashMap<>();

    /** Every block any mapping can reveal, for a cheap first-pass scan filter. */
    private static final Set<Block> ALL_ORES = new HashSet<>();

    private static final List<Sparkle> sparkles = new ArrayList<>();

    /** Owner UUID -> tick their next trick use is allowed. */
    private static final Map<UUID, Integer> useCooldown = new HashMap<>();

    private static int tickCounter;

    static {
        ore(Set.of(Items.IRON_INGOT, Items.RAW_IRON, Items.IRON_ORE, Items.DEEPSLATE_IRON_ORE),
                Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE);
        ore(Set.of(Items.GOLD_INGOT, Items.RAW_GOLD, Items.GOLD_ORE, Items.DEEPSLATE_GOLD_ORE,
                        Items.GOLD_NUGGET, Items.NETHER_GOLD_ORE),
                Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE);
        ore(Set.of(Items.COPPER_INGOT, Items.RAW_COPPER, Items.COPPER_ORE, Items.DEEPSLATE_COPPER_ORE),
                Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE);
        ore(Set.of(Items.DIAMOND, Items.DIAMOND_ORE, Items.DEEPSLATE_DIAMOND_ORE),
                Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE);
        ore(Set.of(Items.EMERALD, Items.EMERALD_ORE, Items.DEEPSLATE_EMERALD_ORE),
                Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE);
        ore(Set.of(Items.COAL, Items.COAL_ORE, Items.DEEPSLATE_COAL_ORE),
                Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE);
        ore(Set.of(Items.REDSTONE, Items.REDSTONE_ORE, Items.DEEPSLATE_REDSTONE_ORE),
                Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE);
        ore(Set.of(Items.LAPIS_LAZULI, Items.LAPIS_ORE, Items.DEEPSLATE_LAPIS_ORE),
                Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE);
        ore(Set.of(Items.QUARTZ, Items.NETHER_QUARTZ_ORE), Blocks.NETHER_QUARTZ_ORE);
        ore(Set.of(Items.NETHERITE_SCRAP, Items.NETHERITE_INGOT, Items.ANCIENT_DEBRIS),
                Blocks.ANCIENT_DEBRIS);

        drop(Items.ROTTEN_FLESH, EntityTypes.ZOMBIE, EntityTypes.HUSK, EntityTypes.DROWNED,
                EntityTypes.ZOMBIE_VILLAGER, EntityTypes.ZOMBIFIED_PIGLIN);
        drop(Items.BONE, EntityTypes.SKELETON, EntityTypes.STRAY);
        drop(Items.WITHER_SKELETON_SKULL, EntityTypes.WITHER_SKELETON);
        drop(Items.ENDER_PEARL, EntityTypes.ENDERMAN);
        drop(Items.SPIDER_EYE, EntityTypes.SPIDER, EntityTypes.CAVE_SPIDER);
        drop(Items.STRING, EntityTypes.SPIDER, EntityTypes.CAVE_SPIDER);
        drop(Items.GUNPOWDER, EntityTypes.CREEPER);
        drop(Items.SLIME_BALL, EntityTypes.SLIME);
        drop(Items.MAGMA_CREAM, EntityTypes.MAGMA_CUBE);
        drop(Items.BLAZE_ROD, EntityTypes.BLAZE);
        drop(Items.PHANTOM_MEMBRANE, EntityTypes.PHANTOM);
        drop(Items.PRISMARINE_SHARD, EntityTypes.GUARDIAN, EntityTypes.ELDER_GUARDIAN);
        drop(Items.SHULKER_SHELL, EntityTypes.SHULKER);
        drop(Items.GHAST_TEAR, EntityTypes.GHAST);
        // Deliberately no leather -> hoglin/ravager: leather reads as "cow" to
        // every player alive, and a scent that surprises you is a bad scent.
    }

    private Tricks() {}

    private static void ore(Set<Item> reagents, Block... ores) {
        Set<Block> blocks = Set.of(ores);
        for (Item reagent : reagents) {
            ORE_BY_ITEM.put(reagent, blocks);
        }
        ALL_ORES.addAll(blocks);
    }

    private static void drop(Item reagent, EntityType<?>... types) {
        MOBS_BY_DROP.put(reagent, Set.of(types));
    }

    public static void register() {
        UseEntityCallback.EVENT.register(Tricks::onUseEntity);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            if (sparkles.isEmpty() || tickCounter % PULSE_INTERVAL_TICKS != 0) {
                return;
            }
            pulse(server);
        });
    }

    // ---- the interaction -----------------------------------------------------

    private static InteractionResult onUseEntity(Player player, Level level, InteractionHand hand,
                                                 Entity target, EntityHitResult hitResult) {
        if (!(player instanceof ServerPlayer owner) || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }
        if (!player.isShiftKeyDown() || !(target instanceof Wolf wolf)) {
            return InteractionResult.PASS;
        }
        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty()) {
            // Sneak + empty hand is Senses' mark-prey. Leave it alone.
            return InteractionResult.PASS;
        }

        WolfRecord record = PlayerWolfRegistry.get(owner.getUUID());
        if (record == null || !record.summoned || !wolf.getUUID().equals(record.wolfUuid)) {
            return InteractionResult.PASS;
        }

        Set<Block> ores = ORE_BY_ITEM.get(held.getItem());
        if (ores != null) {
            return perform(owner, serverLevel, wolf, record, Abilities.DIG,
                    () -> dig(owner, serverLevel, wolf, record, ores, held));
        }

        Set<EntityType<?>> mobs = MOBS_BY_DROP.get(held.getItem());
        if (mobs != null) {
            return perform(owner, serverLevel, wolf, record, Abilities.SPEAK,
                    () -> speak(owner, serverLevel, wolf, record, mobs, held));
        }

        return InteractionResult.PASS;
    }

    /**
     * Gates a trick on being unlocked and equipped, then runs it. An unknown
     * trick passes the interaction through untouched so nothing about vanilla
     * wolf handling changes for a player who has not trained one.
     */
    private static InteractionResult perform(ServerPlayer owner, ServerLevel level, Wolf wolf,
                                             WolfRecord record, Abilities.Ability trick, Runnable body) {
        WolfRecord.AbilityRecord ar = record.abilities.get(trick.id);
        if (ar == null || ar.tier <= 0) {
            return InteractionResult.PASS;
        }
        Integer readyAt = useCooldown.get(owner.getUUID());
        if (readyAt != null && tickCounter < readyAt) {
            return InteractionResult.SUCCESS;
        }
        if (!ar.equipped) {
            owner.sendSystemMessage(Component.literal(
                            wolfName(record) + " knows " + trick.displayName
                                    + ", but is not carrying it.")
                    .withStyle(ChatFormatting.GRAY));
            return InteractionResult.SUCCESS;
        }

        useCooldown.put(owner.getUUID(), tickCounter + USE_COOLDOWN_TICKS);
        sniff(level, wolf);
        body.run();
        return InteractionResult.SUCCESS;
    }

    // ---- Dig -----------------------------------------------------------------

    private static void dig(ServerPlayer owner, ServerLevel level, Wolf wolf, WolfRecord record,
                            Set<Block> ores, ItemStack shown) {
        int tier = Abilities.equippedTier(record, Abilities.DIG);
        int radius = switch (tier) {
            case 2 -> 18;
            case 3 -> 24;
            default -> 12;
        };

        List<BlockPos> found = scanForOre(level, wolf.blockPosition(), ores, radius);
        String what = shown.getHoverName().getString();

        if (found.isEmpty()) {
            bark(level, wolf);
            owner.sendSystemMessage(Component.literal(
                            wolfName(record) + " sniffs the " + what + ", then the stone. Nothing within reach.")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }

        bark(level, wolf);
        sparkles.add(new Sparkle(owner.getUUID(), level.dimension(), found, tickCounter + REVEAL_TICKS));
        pulse(level, found);
        owner.sendSystemMessage(Component.literal(
                        wolfName(record) + " catches the scent -- " + found.size()
                                + (found.size() == 1 ? " seam" : " seams") + " of " + what + " nearby.")
                .withStyle(ChatFormatting.AQUA));
    }

    /**
     * Every matching ore block within {@code radius} that is exposed to an open
     * space. Capped at {@link #MAX_REVEALED_BLOCKS}, nearest first, so the answer
     * is the ore you could actually go and dig rather than a wall of sparkles.
     */
    private static List<BlockPos> scanForOre(ServerLevel level, BlockPos centre, Set<Block> ores, int radius) {
        List<BlockPos> found = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                    if (!level.isLoaded(cursor)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(cursor);
                    if (!ALL_ORES.contains(state.getBlock()) || !ores.contains(state.getBlock())) {
                        continue;
                    }
                    if (isExposed(level, cursor)) {
                        found.add(cursor.immutable());
                    }
                }
            }
        }

        found.sort((a, b) -> Double.compare(a.distSqr(centre), b.distSqr(centre)));
        return found.size() > MAX_REVEALED_BLOCKS ? found.subList(0, MAX_REVEALED_BLOCKS) : found;
    }

    /**
     * True if any of the block's six faces touches something you could see or
     * walk through. This is what keeps Dig a nose rather than an X-ray: ore
     * buried in solid stone stays hidden until someone opens a path to it.
     */
    private static boolean isExposed(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.values()) {
            neighbour.setWithOffset(pos, direction);
            if (!level.isLoaded(neighbour)) {
                continue;
            }
            BlockState state = level.getBlockState(neighbour);
            if (state.isAir() || !state.isViewBlocking(level, neighbour)) {
                return true;
            }
        }
        return false;
    }

    // ---- Speak ---------------------------------------------------------------

    private static void speak(ServerPlayer owner, ServerLevel level, Wolf wolf, WolfRecord record,
                              Set<EntityType<?>> mobs, ItemStack shown) {
        int tier = Abilities.equippedTier(record, Abilities.SPEAK);
        double radius = switch (tier) {
            case 2 -> 32.0;
            case 3 -> 48.0;
            default -> 24.0;
        };

        AABB box = new AABB(wolf.blockPosition()).inflate(radius);
        List<LivingEntity> found = level.getEntitiesOfClass(LivingEntity.class, box,
                candidate -> candidate.isAlive() && mobs.contains(candidate.getType()));

        String what = shown.getHoverName().getString();
        if (found.isEmpty()) {
            bark(level, wolf);
            owner.sendSystemMessage(Component.literal(
                            wolfName(record) + " sniffs the " + what + " and falls quiet. Nothing answers.")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }

        for (LivingEntity revealed : found) {
            revealed.addEffect(new MobEffectInstance(MobEffects.GLOWING, REVEAL_TICKS, 0, false, false));
        }
        bark(level, wolf);
        owner.sendSystemMessage(Component.literal(
                        wolfName(record) + " barks -- " + found.size() + " "
                                + found.get(0).getType().getDescription().getString()
                                + (found.size() == 1 ? "" : "s") + " nearby.")
                .withStyle(ChatFormatting.AQUA));
    }

    // ---- reveal upkeep --------------------------------------------------------

    private static void pulse(MinecraftServer server) {
        Iterator<Sparkle> it = sparkles.iterator();
        while (it.hasNext()) {
            Sparkle sparkle = it.next();
            if (tickCounter >= sparkle.expiryTick()) {
                it.remove();
            }
        }
        // Re-pulsed from the owner's current level, so a player who walks into
        // another dimension simply stops seeing them until the entry expires.
        for (Sparkle sparkle : sparkles) {
            ServerPlayer owner = server.getPlayerList().getPlayer(sparkle.ownerUuid());
            if (owner != null && owner.level() instanceof ServerLevel level
                    && level.dimension().equals(sparkle.dimension())) {
                pulse(level, sparkle.positions());
            }
        }
    }

    /**
     * Marks blocks with a sparkle on each open face.
     *
     * <p>Deliberately particles rather than a true glowing outline: outlining a
     * <em>block</em> server-side means spawning a duplicate display entity per
     * ore and keeping it in sync, which is a lot of moving parts for a cosmetic.
     * Particles on the exposed face also say something true -- they mark the face
     * you can reach, which is exactly what {@link #isExposed} selected for.
     */
    private static void pulse(ServerLevel level, List<BlockPos> positions) {
        BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
        for (BlockPos pos : positions) {
            for (Direction direction : Direction.values()) {
                neighbour.setWithOffset(pos, direction);
                if (!level.isLoaded(neighbour)) {
                    continue;
                }
                BlockState state = level.getBlockState(neighbour);
                if (!state.isAir() && state.isViewBlocking(level, neighbour)) {
                    continue;
                }
                double px = pos.getX() + 0.5 + direction.getStepX() * 0.6;
                double py = pos.getY() + 0.5 + direction.getStepY() * 0.6;
                double pz = pos.getZ() + 0.5 + direction.getStepZ() * 0.6;
                level.sendParticles(ParticleTypes.GLOW, true, true,
                        px, py, pz,
                        4, 0.2, 0.2, 0.2, 0.0);
                // A second forced cloud at the block centre so the ore is visible
                // through walls even if the open face is on the far side.
                level.sendParticles(ParticleTypes.GLOW, true, true,
                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                        3, 0.35, 0.35, 0.35, 0.0);
                // One marked face per block is enough to see it, and keeps a
                // 32-block reveal to 32 particle packets a pulse.
                break;
            }
        }
    }

    /** Drops any pending ore sparkles for a player whose wolf is no longer out. */
    public static void forgetOwner(UUID ownerUuid) {
        sparkles.removeIf(sparkle -> sparkle.ownerUuid().equals(ownerUuid));
        useCooldown.remove(ownerUuid);
    }

    // ---- the wolf sells it ----------------------------------------------------

    /** Nose to the item: a low, short chuff plus a puff at the wolf's head. */
    private static void sniff(ServerLevel level, Wolf wolf) {
        Senses.growl(wolf, 1.6f);
        level.sendParticles(ParticleTypes.POOF,
                wolf.getX(), wolf.getEyeY(), wolf.getZ(), 3, 0.1, 0.1, 0.1, 0.0);
    }

    /** Ears up, then the answer. */
    private static void bark(ServerLevel level, Wolf wolf) {
        wolf.getLookControl().setLookAt(wolf.getX(), wolf.getEyeY() + 2.0, wolf.getZ());
        Senses.growl(wolf, 1.9f);
        level.sendParticles(ParticleTypes.NOTE,
                wolf.getX(), wolf.getEyeY() + 0.4, wolf.getZ(), 2, 0.2, 0.1, 0.2, 0.0);
    }

    private static String wolfName(WolfRecord record) {
        return record.wolfName != null ? record.wolfName : "Your wolf";
    }

    private record Sparkle(UUID ownerUuid, ResourceKey<Level> dimension,
                           List<BlockPos> positions, int expiryTick) {}

    /** Exposed for {@link Training}: the blocks Dig cares about, for counting mined ore. */
    static boolean isOre(Block block) {
        return ALL_ORES.contains(block);
    }
}
