package pocketdungeons;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultConfig;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a stamped room into a pocket trial chamber: a trial spawner where the
 * encounter goes, a vault where the loot goes, and the {@code ominous}
 * blockstate on whichever of them the run's depth says should bite harder.
 *
 * <h2>Why the blocks are placed here and not authored into the .nbt</h2>
 *
 * <p>The plan (U6 Stage 2) called for authoring {@code minecraft:trial_spawner}
 * and {@code minecraft:vault} into every template and regenerating the library.
 * They are placed at stamp time instead, and the reason cross-cutting section 3
 * demanded template authoring is still satisfied: <strong>both anchors come from
 * rotation-transformed sources, never from an offset computed off
 * {@code cellOrigin}</strong>. The spawner lands on a
 * {@code pocketdungeons:spawn} jigsaw position, which
 * {@code StructureTemplate.getJigsaws(placementPos, rotation)} already hands back
 * rotation-correct; the vault replaces an authored chest, whose position
 * {@code placeInWorld} already transformed. Section 3's actual rule -- "read them
 * back out of the template, transformed for the placement rotation" -- holds
 * either way.
 *
 * <p>What that buys: the fourteen shipped {@code .nbt} files, their measured
 * 53/53 coverage and their 200/200 plan success are untouched -- U3's rollback
 * path this once protected ({@code trialsEnabled: false}) was deleted outright
 * in T17, once a real client confirmed the trial loop works. What it costs: the
 * blocks' configs are written every stamp rather than arriving pre-baked --
 * which they were going to be anyway, since a
 * tier is only known at run time.
 *
 * <h2>Ominous</h2>
 *
 * <p>The mod stamps the {@code ominous} blockstate itself and never relies on
 * vanilla noticing a nearby player's Trial Omen. That is load-bearing: Bad Omen
 * becomes Trial Omen on entering a trial chamber <em>structure</em>, and there is
 * no such structure in {@code pocketdungeons:void}. Verified at bytecode level --
 * {@code TrialSpawnerBlock}'s server ticker reads
 * {@code BlockStateProperties.OMINOUS} off the blockstate every tick and passes it
 * straight into {@code TrialSpawner.tickServer}, which assigns it to
 * {@code isOminous} unconditionally -- and confirmed in-world before any of this
 * was written.
 */
final class TrialContent {

    /**
     * Same flag set {@code RoomContent} uses, and for the same hard-won reason:
     * {@code UPDATE_SUPPRESS_DROPS} only suppresses a removed block's own item
     * drop, while a container's <em>contents</em> are dropped separately by
     * {@code BlockEntity.preRemoveSideEffects} unless
     * {@code UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS} is set. Replacing a chest with
     * a vault without it scatters a freshly-unpacked loot roll on the floor.
     */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS
            | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

    /** Vanilla's own vault defaults; see {@code VaultConfig}'s no-arg constructor. */
    private static final double ACTIVATION_RANGE = 4.0;
    private static final double DEACTIVATION_RANGE = 4.5;


    private static final ConfiguredItem VAULT_KEY = new ConfiguredItem("vaultKeyItem",
            PocketDungeonsConfig::vaultKeyItem,
            "vaults will fall back to minecraft:trial_key.");
    private static final ConfiguredItem OMINOUS_VAULT_KEY = new ConfiguredItem("ominousVaultKeyItem",
            PocketDungeonsConfig::ominousVaultKeyItem,
            "ominous vaults will fall back to minecraft:ominous_trial_key.");

    private TrialContent() {}

    /**
     * Resolves the configured key items once, so a typo is a boot-time log line.
     *
     * <p>Unconditional since T17: the trial loop was the only path even before
     * then ({@code trialsEnabled: false} was the kill switch for U3's chests and
     * mob spawns, now deleted), so there is no longer a case where these keys
     * are not needed.
     */
    static void warmUp() {
        VAULT_KEY.get();
        OMINOUS_VAULT_KEY.get();
    }

    // ---- encounter ----------------------------------------------------------

    /**
     * Puts a trial spawner where the room's encounter goes.
     *
     * <p>The anchor, in order of preference: an authored {@code minecraft:spawner}
     * (only {@code spawner_den} has one, and under trials its classic spawner
     * <em>becomes</em> the trial spawner rather than sitting alongside one), then
     * the room's spawn jigsaws. A room with neither cannot host an encounter and
     * says so in the log rather than silently producing a fight-free "encounter".
     *
     * @return true if a spawner was placed
     */
    static boolean applyEncounter(ServerLevel level, BlockPos cellOrigin, List<BlockPos> spawns,
                                  int tier, boolean ominous) {
        BlockPos anchor = encounterAnchor(level, cellOrigin, spawns);
        if (anchor == null) {
            PocketDungeonsMod.LOG.warn(
                    "Encounter cell at {} has no spawner anchor; no trial spawner placed",
                    cellOrigin.toShortString());
            return false;
        }

        // Any classic spawner in the cell is replaced, not left running beside a
        // trial spawner -- two spawners in one room is two difficulty curves.
        clearClassicSpawners(level, cellOrigin);

        BlockState state = Blocks.TRIAL_SPAWNER.defaultBlockState()
                .setValue(TrialSpawnerBlock.OMINOUS, ominous);
        level.setBlock(anchor, state, FLAGS);

        BlockEntity be = level.getBlockEntity(anchor);
        if (!(be instanceof TrialSpawnerBlockEntity spawner)) {
            PocketDungeonsMod.LOG.warn("Trial spawner at {} has no block entity",
                    anchor.toShortString());
            return false;
        }

        // minecraft:trial_spawner is a data-driven registry: the block stores a
        // config *id*, not an inline blob, which is why the tiers ship as JSON
        // under data/pocketdungeons/trial_spawner/ and Java writes two strings.
        // A misspelt id does not throw -- the codec drops the field and the
        // spawner silently keeps FullConfig.DEFAULT -- so `admin cellreport`
        // reads the ids back out rather than trusting the write.
        CompoundTag tag = new CompoundTag();
        tag.putString("normal_config", configId(tier, false));
        tag.putString("ominous_config", configId(tier, true));
        tag.putInt("target_cooldown_length", PocketDungeonsConfig.trialSpawnerCooldownTicks());
        tag.putInt("required_player_range", 14);

        ValueInput input = TagValueInput.create(
                ProblemReporter.DISCARDING, level.registryAccess(), tag);
        spawner.getTrialSpawner().load(input);
        spawner.setChanged();
        spawner.markUpdated();
        return true;
    }

    private static String configId(int tier, boolean ominous) {
        return PocketDungeonsMod.MOD_ID + ":tier_" + Math.max(1, Math.min(3, tier))
                + (ominous ? "/ominous" : "/normal");
    }

    private static BlockPos encounterAnchor(ServerLevel level, BlockPos cellOrigin,
                                            List<BlockPos> spawns) {
        for (BlockPos pos : classicSpawners(level, cellOrigin)) {
            return pos;
        }
        // Deterministic: a seed has to reproduce a dungeon exactly, and
        // getJigsaws' ordering is the template's, not something to lean on.
        BlockPos best = null;
        for (BlockPos pos : spawns) {
            if (best == null || compare(pos, best) < 0) {
                best = pos;
            }
        }
        return best;
    }

    private static int compare(BlockPos a, BlockPos b) {
        if (a.getX() != b.getX()) return Integer.compare(a.getX(), b.getX());
        if (a.getZ() != b.getZ()) return Integer.compare(a.getZ(), b.getZ());
        return Integer.compare(a.getY(), b.getY());
    }

    private static List<BlockPos> classicSpawners(ServerLevel level, BlockPos cellOrigin) {
        List<BlockPos> found = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockEntity> entry : cellBlockEntities(level, cellOrigin)) {
            if (entry.getValue() instanceof SpawnerBlockEntity) {
                found.add(entry.getKey().immutable());
            }
        }
        found.sort(TrialContent::compare);
        return found;
    }

    private static void clearClassicSpawners(ServerLevel level, BlockPos cellOrigin) {
        for (BlockPos pos : classicSpawners(level, cellOrigin)) {
            level.setBlock(pos, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), FLAGS);
        }
    }

    // ---- loot ---------------------------------------------------------------

    /**
     * Turns a loot cell's authored chest into a vault.
     *
     * <p>Exactly one vault per loot cell, which is what makes
     * {@code loot <= encounter} a sufficient key budget. {@code treasure_alcove}
     * authors two chests; the second becomes a supply chest rather than a second
     * vault (two keys for one room) or a hole in the wall (the room's whole look).
     *
     * @return true if a vault was placed
     */
    static boolean applyLoot(ServerLevel level, BlockPos cellOrigin, int tier, boolean ominous,
                             long seed) {
        List<BlockPos> containers = RoomContent.containers(level, cellOrigin);
        containers.sort(TrialContent::compare);
        if (containers.isEmpty()) {
            PocketDungeonsMod.LOG.warn("Loot cell at {} has no container to promote to a vault",
                    cellOrigin.toShortString());
            return false;
        }

        BlockPos vaultPos = containers.get(0);
        Direction facing = facingOf(level, vaultPos);
        placeVault(level, vaultPos, facing, ominous,
                lootTable("chests/tier_" + Math.max(1, Math.min(3, tier))
                        + (ominous ? "_ominous" : "")),
                keyStack(ominous), ItemStack.EMPTY);

        // Everything else in the cell stays a chest, retargeted to the supply
        // table: a small free reward beside the key-gated one, which is the shape
        // a real trial chamber has.
        for (int i = 1; i < containers.size(); i++) {
            BlockPos pos = containers.get(i);
            if (level.getBlockEntity(pos) instanceof net.minecraft.world.RandomizableContainer c) {
                c.setLootTable(lootTable("chests/supply"));
                c.setLootTableSeed(seed ^ pos.asLong());
            }
        }
        return true;
    }

    /**
     * Sets each of the reward room's three chests to the run's tier table (U8
     * Stage 2), or removes it if the run did not earn it. Chests are read back at
     * their authored positions -- {@code (4,1,4)}, {@code (8,1,4)},
     * {@code (12,1,4)} -- rather than scanned, since the reward room is a single
     * fixed template stamped at rotation 0 every time; there is no rotation to
     * transform against.
     *
     * <p>Removing an unearned chest uses the same
     * {@code UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS} flag every other container
     * removal in this mod does (trap 4): without it, a chest carrying a pending
     * loot table unpacks and scatters on the floor the instant it is broken.
     */
    private static final BlockPos[] REWARD_CHEST_SPOTS = {
            new BlockPos(4, 1, 4), new BlockPos(8, 1, 4), new BlockPos(12, 1, 4)
    };

    static void placeRewardChests(ServerLevel level, BlockPos origin, int chests,
                                   int tier, boolean ominous, long seed) {
        ResourceKey<LootTable> table = lootTable("chests/tier_" + Math.max(1, Math.min(3, tier))
                + (ominous ? "_ominous" : ""));
        for (int i = 0; i < REWARD_CHEST_SPOTS.length; i++) {
            BlockPos pos = origin.offset(REWARD_CHEST_SPOTS[i]);
            if (i < chests) {
                level.setBlock(pos, Blocks.CHEST.defaultBlockState()
                        .setValue(ChestBlock.FACING, Direction.SOUTH), FLAGS);
                if (level.getBlockEntity(pos) instanceof net.minecraft.world.RandomizableContainer c) {
                    c.setLootTable(table);
                    c.setLootTableSeed(seed ^ pos.asLong());
                }
            } else {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
            }
        }
    }

    private static void placeVault(ServerLevel level, BlockPos pos, Direction facing,
                                   boolean ominous, ResourceKey<LootTable> table,
                                   ItemStack keyItem, ItemStack displayItem) {
        level.setBlock(pos, Blocks.VAULT.defaultBlockState()
                .setValue(VaultBlock.FACING, facing)
                .setValue(VaultBlock.OMINOUS, ominous), FLAGS);

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof VaultBlockEntity vault)) {
            PocketDungeonsMod.LOG.warn("Vault at {} has no block entity", pos.toShortString());
            return;
        }
        vault.setConfig(new VaultConfig(table, ACTIVATION_RANGE, DEACTIVATION_RANGE,
                keyItem, Optional.empty()));
        if (!displayItem.isEmpty()) {
            vault.getSharedData().setDisplayItem(displayItem);
        }
        vault.setChanged();
    }

    /**
     * Which way a chest was facing, so the vault that replaces it faces the same
     * way. Read off the placed block rather than assumed, because
     * {@code placeInWorld} has already rotated it.
     */
    private static Direction facingOf(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(HorizontalDirectionalBlock.FACING)) {
            return state.getValue(HorizontalDirectionalBlock.FACING);
        }
        return Direction.SOUTH;
    }

    /**
     * The key a vault of this kind wants.
     *
     * <p>Two fields, not one: vanilla's ominous vault takes
     * {@code minecraft:ominous_trial_key}, a different item from
     * {@code minecraft:trial_key}, and the ominous spawner configs eject the
     * matching one. Since U8 Stage 6 a run is ominous everywhere or nowhere --
     * one key kind per run, always -- so {@code loot <= encounter} needs no
     * per-kind bookkeeping any more.
     */
    static ItemStack keyStack(boolean ominous) {
        ConfiguredItem configured = ominous ? OMINOUS_VAULT_KEY : VAULT_KEY;
        var item = configured.get();
        if (item == null) {
            return new ItemStack(ominous
                    ? net.minecraft.world.item.Items.OMINOUS_TRIAL_KEY
                    : net.minecraft.world.item.Items.TRIAL_KEY);
        }
        return new ItemStack(item);
    }

    // ---- shared -------------------------------------------------------------

    private static ResourceKey<LootTable> lootTable(String path) {
        return ResourceKey.create(Registries.LOOT_TABLE,
                Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, path));
    }

    /** A cell is exactly one chunk, so this is a map lookup, not a 1,792-block scan. */
    private static Iterable<Map.Entry<BlockPos, BlockEntity>> cellBlockEntities(
            ServerLevel level, BlockPos cellOrigin) {
        LevelChunk chunk = level.getChunkAt(cellOrigin);
        List<Map.Entry<BlockPos, BlockEntity>> out = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            BlockPos pos = entry.getKey();
            int dx = pos.getX() - cellOrigin.getX();
            int dy = pos.getY() - cellOrigin.getY();
            int dz = pos.getZ() - cellOrigin.getZ();
            if (dx >= 0 && dx < RoomGeometry.CELL && dz >= 0 && dz < RoomGeometry.CELL
                    && dy >= 0 && dy <= RoomGeometry.CEILING_Y) {
                out.add(entry);
            }
        }
        return out;
    }
}
