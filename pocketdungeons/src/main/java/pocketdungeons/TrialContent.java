package pocketdungeons;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
import java.util.Set;
import java.util.UUID;

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
 * 53/53 coverage and their 200/200 plan success are untouched, and
 * {@code trialsEnabled: false} rolls the whole milestone back to U3's behaviour
 * exactly rather than approximately, because U3's path is the geometry that is
 * still on disk. What it costs: the blocks' configs are written every stamp
 * rather than arriving pre-baked -- which they were going to be anyway, since a
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

    private static final Codec<Set<UUID>> REWARDED_PLAYERS =
            UUIDUtil.CODEC_LINKED_SET.lenientOptionalFieldOf("rewarded_players", Set.of()).codec();

    private static final ConfiguredItem VAULT_KEY = new ConfiguredItem("vaultKeyItem",
            PocketDungeonsConfig::vaultKeyItem,
            "vaults will fall back to minecraft:trial_key.");
    private static final ConfiguredItem OMINOUS_VAULT_KEY = new ConfiguredItem("ominousVaultKeyItem",
            PocketDungeonsConfig::ominousVaultKeyItem,
            "ominous vaults will fall back to minecraft:ominous_trial_key.");

    private TrialContent() {}

    /** Whether the trial loop is on. False restores U3's chests and mob spawns wholesale. */
    static boolean enabled() {
        return PocketDungeonsConfig.trialsEnabled();
    }

    /** Resolves the configured key items once, so a typo is a boot-time log line. */
    static void warmUp() {
        if (enabled()) {
            VAULT_KEY.get();
            OMINOUS_VAULT_KEY.get();
        }
    }

    /**
     * Whether a cell at this depth fights and pays ominously.
     *
     * <p>Replaces U3's {@code effectiveTier(depth)}: the far third of a run is
     * ominous whether or not the player paid for it, and an ominous <em>run</em>
     * makes the whole thing ominous from the entrance. U7 adds a third route --
     * a keystone at or above {@code ominousFromLevel}.
     */
    static boolean ominousAt(int depth, int pathLength, boolean ominousRun) {
        return ominousRun || depth >= (pathLength * 2) / 3;
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
                                  int tier, boolean ominous, boolean ominousRun) {
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
        tag.putString("ominous_config", ominousConfigId(tier, ominousRun));
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

    /**
     * The ominous config a spawner in <em>this run</em> should use.
     *
     * <p>Two variants, and the difference is only which key they eject.
     * {@link #ominousAt} is a function of <em>depth</em>, so a plain run
     * legitimately contains ominous cells -- but vanilla's ominous vault takes
     * {@code minecraft:ominous_trial_key}, a different item from
     * {@code minecraft:trial_key}, so mixing the two kinds inside one run means
     * the key budget has to balance <em>per kind</em>, and
     * {@code balanceKeyBudget} only balances the total.
     *
     * <p>Rather than teach the planner a second budget, the key is made a
     * property of the run: a plain run mints plain keys everywhere, an ominous
     * run mints ominous keys everywhere, and the deep-third ramp survives intact
     * as a harder fight and a better loot table. One key kind per run makes
     * {@code loot <= encounter} sufficient exactly as written.
     */
    private static String ominousConfigId(int tier, boolean ominousRun) {
        return PocketDungeonsMod.MOD_ID + ":tier_" + Math.max(1, Math.min(3, tier))
                + (ominousRun ? "/ominous" : "/ominous_plain_key");
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
                             boolean ominousRun, long seed) {
        List<BlockPos> containers = RoomContent.containers(level, cellOrigin);
        containers.sort(TrialContent::compare);
        if (containers.isEmpty()) {
            PocketDungeonsMod.LOG.warn("Loot cell at {} has no container to promote to a vault",
                    cellOrigin.toShortString());
            return false;
        }

        BlockPos vaultPos = containers.get(0);
        Direction facing = facingOf(level, vaultPos);
        // The blockstate and the loot table follow the cell's depth; the key
        // follows the run. See ominousConfigId for why those two come apart.
        placeVault(level, vaultPos, facing, ominous,
                lootTable("chests/tier_" + Math.max(1, Math.min(3, tier))
                        + (ominous ? "_ominous" : "")),
                keyStack(ominousRun), ItemStack.EMPTY);

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
     * The three completion offers of U7 Stage 3, placed in the terminal cell.
     *
     * <p>All three take the <em>same</em> token item, and the player holds exactly
     * one, so <strong>vanilla enforces the choice</strong>: opening one consumes
     * the token and the other two can never be opened. There is no mod-side
     * sealing code, and each party member's own token gives them their own
     * independent choice for free.
     *
     * <p>Positions are computed from {@code cellOrigin} rather than read out of the
     * template -- the exit room authors no chest and no spawn point to hang them
     * on -- which cross-cutting section 3 warns about. It is safe here for a
     * specific reason: {@code {5,10} x {5,10}} is <strong>closed under the
     * rotation transform</strong> ({@code 15 - 5 = 10}), so any fixed choice of
     * three of those four corners lands on three distinct interior floor positions
     * at every rotation. They may not be the <em>same</em> three across rotations,
     * and that does not matter -- the three offers are interchangeable. All four
     * are clear of the doorway lanes ({@code x,z} in {@code [7,8]}) and of the
     * 2x2 lodestone pad.
     *
     * @return the vault positions, in offer order
     */
    static List<BlockPos> applyChoiceVaults(ServerLevel level, BlockPos cellOrigin,
                                            int keystoneLevel) {
        List<BlockPos> placed = new ArrayList<>();
        ResourceKey<LootTable> empty = lootTable("empty");
        Keystone.Offer[] offers = Keystone.offers(keystoneLevel);
        for (int i = 0; i < offers.length && i < CHOICE_SPOTS.length; i++) {
            BlockPos pos = cellOrigin.offset(CHOICE_SPOTS[i]);
            ItemStack token = Keystone.mintToken(keystoneLevel);
            placeVault(level, pos, Direction.SOUTH, offers[i].ominous(), empty, token,
                    Keystone.mint(offers[i].level(), offers[i].affix()));
            placed.add(pos);
        }
        return placed;
    }

    /** See {@link #applyChoiceVaults}: three of a rotation-closed four-corner set. */
    private static final net.minecraft.core.Vec3i[] CHOICE_SPOTS = {
            new net.minecraft.core.Vec3i(5, 1, 5),
            new net.minecraft.core.Vec3i(10, 1, 5),
            new net.minecraft.core.Vec3i(10, 1, 10),
    };

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
     * matching one. A run legitimately contains both kinds at once -- the near
     * two thirds plain, the deep third ominous -- so
     * {@code loot <= encounter} has to hold per kind, which it does because
     * {@link #ominousAt} is a function of depth alone and both roles read it.
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

    // ---- reading a vault back out -------------------------------------------

    /**
     * Which players have already claimed this vault.
     *
     * <p>{@code VaultServerData.getRewardedPlayers()} is package-private, so this
     * goes through the block entity's own saved NBT -- {@code saveWithoutMetadata}
     * is public, and {@code server_data.rewarded_players} is
     * {@code UUIDUtil.CODEC_LINKED_SET}, which is public too. Reading the codec
     * rather than the field means a rename shows up as an empty set and a decode
     * error in the log, not as a compile that quietly does the wrong thing.
     */
    static Set<UUID> rewardedPlayers(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof VaultBlockEntity vault)) {
            return Set.of();
        }
        CompoundTag saved = vault.saveWithoutMetadata(level.registryAccess());
        Tag serverData = saved.get("server_data");
        if (serverData == null) {
            return Set.of();
        }
        return REWARDED_PLAYERS.parse(NbtOps.INSTANCE, serverData)
                .resultOrPartial(err -> PocketDungeonsMod.LOG.warn(
                        "Could not read rewarded players from the vault at {}: {}",
                        pos.toShortString(), err))
                .orElse(Set.of());
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
