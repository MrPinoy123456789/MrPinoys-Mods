package pocketdungeons;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerConfig;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
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
import java.util.Set;

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
     * @return the anchor a trial spawner was placed at, or {@code null} if none
     *         was (M10: {@link InstanceRecord#trialSpawners} collects this)
     */
    static BlockPos applyEncounter(ServerLevel level, BlockPos cellOrigin, List<BlockPos> spawns,
                                  int tier, Set<Affix> affixes) {
        boolean ominous = affixes.contains(Affix.OMINOUS);
        boolean swarming = affixes.contains(Affix.SWARMING);
        boolean overclocked = affixes.contains(Affix.OVERCLOCKED);
        boolean silenced = affixes.contains(Affix.SILENCED);
        BlockPos anchor = encounterAnchor(level, cellOrigin, spawns);
        if (anchor == null) {
            PocketDungeonsMod.LOG.warn(
                    "Encounter cell at {} has no spawner anchor; no trial spawner placed",
                    cellOrigin.toShortString());
            return null;
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
            return null;
        }

        // minecraft:trial_spawner is a data-driven registry: the block stores a
        // config *id*, not an inline blob, which is why the tiers ship as JSON
        // under data/pocketdungeons/trial_spawner/ and Java writes two strings.
        // A misspelt id does not throw -- the codec drops the field and the
        // spawner silently keeps FullConfig.DEFAULT -- so `admin cellreport`
        // reads the ids back out rather than trusting the write.
        //
        // Swarming is the one exception: the two-arg RegistryFileCodec Mojang
        // built TrialSpawnerConfig.CODEC from allows an *inline* config object
        // in place of the id string, so a swarming run writes the tier's config
        // back out with its mob counts scaled, rather than needing a JSON file
        // per affix per tier (M4 5.1/T4.5).
        CompoundTag tag = new CompoundTag();
        if (swarming) {
            writeInlineConfig(level, tag, "normal_config", tier, false);
            writeInlineConfig(level, tag, "ominous_config", tier, true);
        } else {
            tag.putString("normal_config", configId(tier, false));
            tag.putString("ominous_config", configId(tier, true));
        }
        // Overclocked: the waves come back fast, so a fast clear is faster still.
        int cooldown = PocketDungeonsConfig.trialSpawnerCooldownTicks();
        if (overclocked) {
            cooldown = (int) Math.round(cooldown * PocketDungeonsConfig.overclockedCooldownFactor());
        }
        tag.putInt("target_cooldown_length", cooldown);
        // Silenced: the mobs cannot hear you either -- a tighter detection range
        // hands back the tactical "sneak it or fight it" choice section 3.3 says
        // procedural layouts otherwise destroy.
        tag.putInt("required_player_range",
                silenced ? PocketDungeonsConfig.silencedPlayerRange() : 14);

        ValueInput input = TagValueInput.create(
                ProblemReporter.DISCARDING, level.registryAccess(), tag);
        spawner.getTrialSpawner().load(input);
        spawner.setChanged();
        spawner.markUpdated();
        return anchor;
    }

    /**
     * How many of these trial spawners have reached {@code COOLDOWN}, the
     * "cleared" state confirmed against the 26.2 jar (a spawner that ejects its
     * reward transitions {@code WAITING_FOR_REWARD_EJECTION -> EJECTING_REWARD
     * -> COOLDOWN}). An untouched spawner sits at {@code INACTIVE}: it never
     * activated because nobody came near it, and that counts as
     * <strong>not</strong> cleared. Excluding it from the denominator would let a
     * run skip every spawner and still pass the M10 completion gate, which is
     * exactly the sprint this gate exists to refuse.
     *
     * <p>A position with no trial spawner block entity any more (broken, somehow)
     * also counts as not cleared, for the same reason.
     */
    static int countCleared(ServerLevel level, Set<BlockPos> spawners) {
        int cleared = 0;
        for (BlockPos pos : spawners) {
            if (level.getBlockEntity(pos) instanceof TrialSpawnerBlockEntity spawner
                    && spawner.getState() == TrialSpawnerState.COOLDOWN) {
                cleared++;
            }
        }
        return cleared;
    }

    private static String configId(int tier, boolean ominous) {
        return PocketDungeonsMod.MOD_ID + ":tier_" + Math.max(1, Math.min(3, tier))
                + (ominous ? "/ominous" : "/normal");
    }

    /**
     * Resolves the tier's shipped config out of the registry and writes it back
     * under {@code key}, scaling {@code totalMobs}/{@code simultaneousMobs} by
     * {@link PocketDungeonsConfig#swarmingMobFactor()}. More bodies is more
     * drops -- Swarming's kiss.
     *
     * <p>If the base id is missing from the registry (a typo, a datapack that
     * failed to load) this falls back to writing the plain id string, which is
     * exactly what every other run already does and costs nothing extra.
     */
    private static void writeInlineConfig(ServerLevel level, CompoundTag tag, String key,
                                          int tier, boolean ominous) {
        Identifier id = Identifier.parse(configId(tier, ominous));
        TrialSpawnerConfig base = level.registryAccess()
                .lookupOrThrow(Registries.TRIAL_SPAWNER_CONFIG).getValue(id);
        if (base == null) {
            PocketDungeonsMod.LOG.warn(
                    "Swarming: trial spawner config {} not found; falling back to the plain id", id);
            tag.putString(key, id.toString());
            return;
        }
        double factor = PocketDungeonsConfig.swarmingMobFactor();
        TrialSpawnerConfig scaled = new TrialSpawnerConfig(
                base.spawnRange(),
                (float) (base.totalMobs() * factor),
                (float) (base.simultaneousMobs() * factor),
                base.totalMobsAddedPerPlayer(),
                base.simultaneousMobsAddedPerPlayer(),
                base.ticksBetweenSpawn(),
                base.spawnPotentialsDefinition(),
                base.lootTablesToEject(),
                base.itemsToDropWhenOminous());

        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, level.registryAccess());
        DataResult<Tag> encoded = TrialSpawnerConfig.DIRECT_CODEC.encodeStart(ops, scaled);
        Optional<Tag> result = encoded.resultOrPartial(error ->
                PocketDungeonsMod.LOG.warn("Swarming: failed to encode scaled config for {}: {}", id, error));
        if (result.isPresent()) {
            tag.put(key, result.get());
        } else {
            tag.putString(key, id.toString());
        }
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
                             long seed, String lootSuffix) {
        List<BlockPos> containers = RoomContent.containers(level, cellOrigin);
        containers.sort(TrialContent::compare);
        if (containers.isEmpty()) {
            PocketDungeonsMod.LOG.warn("Loot cell at {} has no container to promote to a vault",
                    cellOrigin.toShortString());
            return false;
        }

        BlockPos vaultPos = containers.get(0);
        Direction facing = facingOf(level, vaultPos);
        // The vault table, not the chest table the reward and completion chests
        // draw from: a vault ejects everything it rolls onto the floor instead
        // of holding it in 27 slots, so it wants a short table. See
        // LootTables.vaultTable.
        placeVault(level, vaultPos, facing, ominous,
                resolveLootTable(level, LootTables.vaultTable(tier, ominous), lootSuffix),
                keyStack(ominous), ItemStack.EMPTY);

        // Everything else in the cell stays a chest, retargeted to the supply
        // table: a small free reward beside the key-gated one, which is the shape
        // a real trial chamber has.
        //
        // M6 T6.1: the supply table is tiered, and it carries the same guaranteed
        // floor the tier tables do. It is the only container in a run that is
        // gated on nothing (no key, no clock), so a player who fights badly
        // still walks out with food, light, bones, blocks and seeds. The tier
        // matters for the same reason it matters in the vault (T6.2): a tier-3
        // run's free chest has no business handing out tier-1 blocks.
        for (int i = 1; i < containers.size(); i++) {
            BlockPos pos = containers.get(i);
            if (level.getBlockEntity(pos) instanceof net.minecraft.world.RandomizableContainer c) {
                c.setLootTable(lootTable(LootTables.supplyTable(tier)));
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

    static void placeCompletionChests(ServerLevel level, BlockPos origin,
                                        DoorMask.Direction entranceDir, int chests,
                                        int tier, boolean ominous, long seed, String lootSuffix) {
        ResourceKey<LootTable> table = resolveLootTable(level,
                LootTables.tierTable(tier, ominous), lootSuffix);

        // Three chests on the far side of the terminal cell, beyond the 2x2
        // lodestone pad and in front of the sealed door. Spots are mirrored by
        // the entrance direction so they always face the player walking in.
        BlockPos[] spots = switch (entranceDir) {
            case NORTH -> new BlockPos[]{new BlockPos(4, 1, 12), new BlockPos(8, 1, 12),
                    new BlockPos(12, 1, 12)};
            case SOUTH -> new BlockPos[]{new BlockPos(4, 1, 4), new BlockPos(8, 1, 4),
                    new BlockPos(12, 1, 4)};
            case WEST -> new BlockPos[]{new BlockPos(12, 1, 4), new BlockPos(12, 1, 8),
                    new BlockPos(12, 1, 12)};
            case EAST -> new BlockPos[]{new BlockPos(4, 1, 4), new BlockPos(4, 1, 8),
                    new BlockPos(4, 1, 12)};
        };
        Direction facing = switch (entranceDir) {
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case WEST -> Direction.WEST;
            case EAST -> Direction.EAST;
        };
        for (int i = 0; i < spots.length; i++) {
            BlockPos pos = origin.offset(spots[i]);
            if (i < chests) {
                level.setBlock(pos, Blocks.CHEST.defaultBlockState()
                        .setValue(ChestBlock.FACING, facing), FLAGS);
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

    private static ResourceKey<LootTable> resolveLootTable(ServerLevel level, String basePath,
                                                            String suffix) {
        if (suffix != null && !suffix.isBlank()) {
            ResourceKey<LootTable> candidate = ResourceKey.create(Registries.LOOT_TABLE,
                    Identifier.fromNamespaceAndPath(PocketDungeonsMod.MOD_ID, basePath + suffix));
            if (LootTables.exists(level.getServer(), candidate)) {
                return candidate;
            }
        }
        return lootTable(basePath);
    }

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
