package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * The dead-end fountain (playtest 2026-10-02-1: dead end rooms that only had a
 * vault, maybe a chest and a chiseled stone wall, should sometimes hold a
 * fountain that gives a boon: a full heal, food, or a clean omen).
 *
 * <p>A full water cauldron on a chiseled pedestal. The pedestal block says which
 * boon it holds, so the fountain needs no saved state: quartz is a full heal,
 * sandstone is food, deepslate cleanses the omen. Using it empties the cauldron,
 * and turns the pedestal plain, which is the whole "one use" rule (a cauldron
 * refilled from a bucket is just a cauldron): the first player to drink takes the boon
 * for themselves (the omen cleanse is the floor's, so it helps the party).
 */
final class Fountain {

    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS;

    /** What a pedestal becomes once its fountain is drunk: chiseled, but no boon's block. */
    private static final BlockState SPENT_PEDESTAL = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();

    enum Boon {
        HEAL(Blocks.CHISELED_QUARTZ_BLOCK),
        FOOD(Blocks.CHISELED_SANDSTONE),
        CLEANSE(Blocks.CHISELED_DEEPSLATE);

        final Block pedestal;

        Boon(Block pedestal) {
            this.pedestal = pedestal;
        }

        static Boon of(BlockState state) {
            for (Boon boon : values()) {
                if (state.is(boon.pedestal)) {
                    return boon;
                }
            }
            return null;
        }
    }

    /** Pedestal spots in a cell, tried in order: the four corners, clear of the doorways. */
    private static final List<int[]> SPOTS = List.of(
            new int[]{2, 13}, new int[]{13, 13}, new int[]{13, 2}, new int[]{2, 2});

    private Fountain() {}

    /**
     * Rolls {@link PocketDungeonsConfig#fountainChance()} for a dead-end cell and, on a hit,
     * places a fountain in the first free corner. Seeded from the plan seed and the cell,
     * so one plan always builds the same fountains.
     *
     * @return the boon placed, or {@code null} for none
     */
    static Boon maybePlace(ServerLevel level, BlockPos cellOrigin, long seed) {
        Boon boon = roll(seed, cellOrigin, PocketDungeonsConfig.fountainChance());
        if (boon == null) {
            return null;
        }
        for (int[] spot : SPOTS) {
            BlockPos base = cellOrigin.offset(spot[0], 1, spot[1]);
            BlockPos top = base.above();
            if (level.getBlockState(base).isAir() && level.getBlockState(top).isAir()
                    && !level.getBlockState(base.below()).isAir()) {
                level.setBlock(base, boon.pedestal.defaultBlockState(), FLAGS);
                level.setBlock(top, Blocks.WATER_CAULDRON.defaultBlockState()
                        .setValue(LayeredCauldronBlock.LEVEL, 3), FLAGS);
                return boon;
            }
        }
        return null;
    }

    /** Whether a cell holds a fountain and which boon, from the plan seed and the cell alone. */
    static Boon roll(long seed, BlockPos cellOrigin, double chance) {
        RandomSource random = RandomSource.create(seed ^ (cellOrigin.asLong() * 0x9E3779B97F4A7C15L) ^ 0xF0F7A1DL);
        if (!Fuel.rollChance(random, chance)) {
            return null;
        }
        return Boon.values()[random.nextInt(Boon.values().length)];
    }

    /** The boon a full water cauldron at {@code pos} holds, or {@code null} if it is not a fountain. */
    static Boon boonAt(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).is(Blocks.WATER_CAULDRON)) {
            return null;
        }
        return Boon.of(level.getBlockState(pos.below()));
    }

    /**
     * A right-click on a block. Handled when it is a full fountain in the dungeon.
     *
     * @return whether the click was claimed
     */
    static boolean onUse(ServerPlayer player, ServerLevel level, BlockPos pos) {
        return onUse(player, level, pos, level.dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL));
    }

    /** As above, with the dimension test supplied so a gametest can stand in the dungeon. */
    static boolean onUse(ServerPlayer player, ServerLevel level, BlockPos pos, boolean inDungeon) {
        if (!inDungeon) {
            return false;
        }
        Boon boon = boonAt(level, pos);
        if (boon == null) {
            return false;
        }
        level.setBlock(pos, Blocks.CAULDRON.defaultBlockState(), FLAGS);
        // The pedestal goes plain too: a refilled cauldron (a water bucket makes
        // it full again) must not read as the same fountain a second time.
        level.setBlock(pos.below(), SPENT_PEDESTAL, FLAGS);
        level.playSound(null, pos, SoundEvents.PLAYER_SPLASH, SoundSource.BLOCKS, 0.6f, 1.2f);
        apply(player, boon);
        return true;
    }

    /** Grants {@code boon} to {@code player} and says what happened. */
    static void apply(ServerPlayer player, Boon boon) {
        switch (boon) {
            case HEAL -> {
                player.setHealth(player.getMaxHealth());
                say(player, "The water mends you. Full health.");
            }
            case FOOD -> {
                player.getFoodData().setFoodLevel(20);
                player.getFoodData().setSaturation(20.0f);
                say(player, "The water fills you. You are fed.");
            }
            case CLEANSE -> {
                InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
                if (record != null) {
                    OmenSources.relieve(player.level().getServer(), record,
                            PocketDungeonsConfig.fountainOmenRelief());
                }
                say(player, "The water washes the omen out of the air.");
            }
        }
        PlaytestJournal.fountain(player, boon.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static void say(ServerPlayer player, String line) {
        player.sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.AQUA));
    }
}
