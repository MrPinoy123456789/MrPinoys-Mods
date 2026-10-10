package pocketdungeons;

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
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
     * The NBT key written to authored trial spawner and vault block entities
     * by the room editor. The stamp pipeline checks this flag to distinguish
     * editor-placed blocks from stamp-placed ones in reused chunks. See
     * {@code docs/reference/ROOM_AUTHORING_SPEC.md} section 6.
     */
    static final String PD_AUTHORED = "pd_authored";

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

    /**
     * M52: trial spawners placed by encounter placement, mapped to whether the
     * cell they sit in is {@code gated}. Base-room encounter cells that go
     * through the ordinary role dispatch are recorded here as {@code false}
     * (open); situation-handler combat rooms pass their own {@code access} value.
     *
     * <p>M67: this map is no longer read by the completion gate or the
     * displayed counter, which now use {@link #activeSpawners} (all trial
     * spawners in the layout). The map is retained for any future per-cell
     * gating logic that needs to distinguish gated from open encounters.
     *
     * <p>Keyed by the spawner's world position. Instance slots reuse the same
     * origins, so re-stamping overwrites stale entries naturally.
     */
    private static final Map<BlockPos, Boolean> SITUATION_SPAWNERS = new LinkedHashMap<>();

    private TrialContent() {}

    /**
     * Resolves the configured key items once, so a typo is a boot-time log line.
     * Also registers the M52 situation handlers, which need to be in place
     * before any stamping pass runs.
     */
    static void warmUp() {
        VAULT_KEY.get();
        OMINOUS_VAULT_KEY.get();
        KnowledgeSpecs.registerHandlers();
        KennelSpecs.registerHandlers();
        MechanismSpecs.registerHandlers();
        PressureSpecs.registerHandlers();
        SpurSpecs.registerHandlers();
        TraversalSpecs.registerHandlers();
        Ordeals.register();
        SituationSpecs.registerHandlers();
        ResourceBiomeSpecs.registerHandlers();
        CowPits.register();
        CapstoneSpecs.registerHandlers();
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
                                  int tier, Set<String> affixes, String theme) {
        boolean ominous = affixes.contains(AffixIds.OMINOUS);
        boolean swarming = affixes.contains(AffixIds.SWARMING);
        boolean overclocked = affixes.contains(AffixIds.OVERCLOCKED);
        boolean silenced = affixes.contains(AffixIds.SILENCED);
        ThemeManifest.Entry runTheme = ThemeManifest.current().byId(theme);
        String spawnerPrefix = runTheme == null ? null : runTheme.meta().spawnerPrefix;
        if (spawnerPrefix == null || spawnerPrefix.isBlank()) {
            spawnerPrefix = genericPrefix(cellOrigin);
        }
        // M68: a theme's namespaced normal_spawner / ominous_spawner override
        // the legacy spawner_prefix composition. A third party theme can now
        // point at its own namespace's trial spawner configs instead of
        // secretly requiring pocketdungeons configs the prefix composition
        // always built.
        String normalSpawnerId = runTheme == null ? null : runTheme.meta().normalSpawner;
        String ominousSpawnerId = runTheme == null ? null : runTheme.meta().ominousSpawner;

        // Authored trial spawners: reconfigure in place, do not move or replace.
        List<BlockPos> authored = authoredTrialSpawners(level, cellOrigin);
        if (!authored.isEmpty()) {
            clearClassicSpawners(level, cellOrigin);
            BlockPos anchor = null;
            int cooldown = PocketDungeonsConfig.trialSpawnerCooldownTicks();
            if (overclocked) {
                cooldown = (int) Math.round(cooldown * PocketDungeonsConfig.overclockedCooldownFactor());
            }
            for (BlockPos pos : authored) {
                if (normalSpawnerId != null || ominousSpawnerId != null) {
                    reconfigureTrialSpawnerByIds(level, pos,
                            normalSpawnerId != null ? normalSpawnerId : configId(spawnerPrefix, tier, false),
                            ominousSpawnerId != null ? ominousSpawnerId : configId(spawnerPrefix, tier, true),
                            ominous, swarming, silenced, overclocked, cooldown);
                } else {
                    reconfigureTrialSpawner(level, pos, spawnerPrefix, tier, ominous,
                            swarming, silenced, overclocked, cooldown);
                }
                if (anchor == null) {
                    anchor = pos;
                }
            }
            SITUATION_SPAWNERS.put(anchor, false);
            return anchor;
        }

        // Legacy path: no authored trial spawners, fall through to anchor-based placement.
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
        if (normalSpawnerId != null || ominousSpawnerId != null) {
            // M68: namespaced overrides. Each side falls back to the legacy
            // composition when the theme only overrode one of the two.
            String normalId = normalSpawnerId != null ? normalSpawnerId : configId(spawnerPrefix, tier, false);
            String ominousId = ominousSpawnerId != null ? ominousSpawnerId : configId(spawnerPrefix, tier, true);
            if (swarming) {
                writeInlineConfigById(level, tag, "normal_config", Identifier.parse(normalId));
                writeInlineConfigById(level, tag, "ominous_config", Identifier.parse(ominousId));
            } else {
                tag.putString("normal_config", normalId);
                tag.putString("ominous_config", ominousId);
            }
        } else if (swarming) {
            writeInlineConfig(level, tag, "normal_config", spawnerPrefix, tier, false);
            writeInlineConfig(level, tag, "ominous_config", spawnerPrefix, tier, true);
        } else {
            tag.putString("normal_config", configId(spawnerPrefix, tier, false));
            tag.putString("ominous_config", configId(spawnerPrefix, tier, true));
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
        SITUATION_SPAWNERS.put(anchor, false);
        return anchor;
    }

    /**
     * M52: situation-handler encounter placement. As the theme-based
     * {@link #applyEncounter}, but uses a fixed spawner config prefix (e.g.
     * {@code "breeze_arena"}) instead of the run theme's prefix, and records
     * the cell's {@code gated} flag for the clear-gate filter (spec 6.4 open
     * question 11).
     */
    static BlockPos applyEncounter(ServerLevel level, BlockPos cellOrigin, List<BlockPos> spawns,
                                  int tier, Set<String> affixes, String configPrefix, boolean gated) {
        boolean ominous = affixes.contains(AffixIds.OMINOUS);
        boolean swarming = affixes.contains(AffixIds.SWARMING);
        boolean overclocked = affixes.contains(AffixIds.OVERCLOCKED);
        boolean silenced = affixes.contains(AffixIds.SILENCED);

        // Authored trial spawners: reconfigure in place with the situation's prefix.
        List<BlockPos> authored = authoredTrialSpawners(level, cellOrigin);
        if (!authored.isEmpty()) {
            clearClassicSpawners(level, cellOrigin);
            BlockPos anchor = null;
            int cooldown = PocketDungeonsConfig.trialSpawnerCooldownTicks();
            if (overclocked) {
                cooldown = (int) Math.round(cooldown * PocketDungeonsConfig.overclockedCooldownFactor());
            }
            for (BlockPos pos : authored) {
                reconfigureTrialSpawnerById(level, pos, configPrefix, tier, ominous,
                        swarming, silenced, overclocked, cooldown);
                if (anchor == null) {
                    anchor = pos;
                }
            }
            SITUATION_SPAWNERS.put(anchor, gated);
            return anchor;
        }

        // Legacy path: no authored trial spawners, fall through to anchor-based placement.
        BlockPos anchor = encounterAnchor(level, cellOrigin, spawns);
        if (anchor == null) {
            PocketDungeonsMod.LOG.warn(
                    "Situation encounter cell at {} has no spawner anchor; no trial spawner placed",
                    cellOrigin.toShortString());
            return null;
        }
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
        String normalId = PocketDungeonsMod.MOD_ID + ":" + configPrefix + "/normal";
        String ominousId = PocketDungeonsMod.MOD_ID + ":" + configPrefix + "/ominous";
        CompoundTag tag = new CompoundTag();
        if (swarming) {
            writeInlineConfigById(level, tag, "normal_config", Identifier.parse(normalId));
            writeInlineConfigById(level, tag, "ominous_config", Identifier.parse(ominousId));
        } else {
            tag.putString("normal_config", normalId);
            tag.putString("ominous_config", ominousId);
        }
        int cooldown = PocketDungeonsConfig.trialSpawnerCooldownTicks();
        if (overclocked) {
            cooldown = (int) Math.round(cooldown * PocketDungeonsConfig.overclockedCooldownFactor());
        }
        tag.putInt("target_cooldown_length", cooldown);
        tag.putInt("required_player_range",
                silenced ? PocketDungeonsConfig.silencedPlayerRange() : 14);
        ValueInput input = TagValueInput.create(
                ProblemReporter.DISCARDING, level.registryAccess(), tag);
        spawner.getTrialSpawner().load(input);
        spawner.setChanged();
        spawner.markUpdated();
        SITUATION_SPAWNERS.put(anchor, gated);
        return anchor;
    }

    /**
     * M67: every trial spawner in this layout that still has a live
     * {@code TrialSpawnerBlockEntity}, regardless of whether its cell is
     * gated or open. This is the denominator for the spawner-clear
     * completion gate and the displayed counter. The layout's
     * {@code trialSpawners} set is the source of truth: it is rebuilt per
     * run and carries only the spawners the current stamping pass placed,
     * so stale positions from previous runs cannot leak in.
     *
     * <p>2026-10-02: a spawner under an unbroken rubble floor seal
     * ({@link RubbleOrdeal#hidesSpawner}) is left out until the seal breaks,
     * since nobody can reach it before then.
     *
     * <p>The live block-entity check drops any position whose spawner was
     * broken or never placed (a stamping failure that returned null but
     * was still added to the set, or a player who broke the spawner
     * itself), keeping the denominator honest.
     */
    static Set<BlockPos> activeSpawners(InstanceLayout layout, ServerLevel level) {
        Set<BlockPos> out = new LinkedHashSet<>();
        for (BlockPos pos : layout.trialSpawners()) {
            // A spawner under an unbroken rubble floor seal is out of reach
            // until a blast opens it, so it does not hold the floor shut.
            if (level.getBlockEntity(pos) instanceof TrialSpawnerBlockEntity
                    && !RubbleOrdeal.hidesSpawner(level, pos)) {
                out.add(pos);
            }
        }
        return out;
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
    /**
     * PD-134 (playtest 2026-10-03-2): lets each spawner forget any mob a
     * player has tamed. A trial spawner finishes a wave only when every mob
     * it spawned has died, so a wolf tamed out of the kennel spawner kept it
     * waiting forever and the floor's spawner count stalled. A tamed mob is
     * the player's now; to the spawner it counts as defeated.
     */
    static void releaseTamed(ServerLevel level, Set<BlockPos> spawners) {
        for (BlockPos pos : spawners) {
            if (!(level.getBlockEntity(pos) instanceof TrialSpawnerBlockEntity spawner)) {
                continue;
            }
            java.util.Set<java.util.UUID> waiting = ((pocketdungeons.mixin.TrialSpawnerStateDataAccessor)
                    (Object) spawner.getTrialSpawner().getStateData()).pocketdungeons$currentMobs();
            if (waiting.removeIf(id -> level.getEntity(id)
                    instanceof net.minecraft.world.entity.TamableAnimal animal && animal.isTame())) {
                spawner.setChanged();
            }
        }
    }

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

    /**
     * Whether the cell still holds a trial spawner that has not gone to
     * cooldown. M48's dwell clock uses this as half of "unsolved": a cell you
     * have not fought through yet is a cell that costs you omen to linger in.
     */
    static boolean hasActiveSpawner(ServerLevel level, BlockPos cellOrigin) {
        for (BlockPos pos : BlockPos.betweenClosed(
                cellOrigin.offset(0, 1, 0),
                cellOrigin.offset(RoomGeometry.CELL - 1, RoomGeometry.CEILING_Y, RoomGeometry.CELL - 1))) {
            if (level.getBlockEntity(pos) instanceof TrialSpawnerBlockEntity spawner
                    && spawner.getState() != TrialSpawnerState.COOLDOWN) {
                return true;
            }
        }
        return false;
    }

    /** The narrow generic spawner families (PD-107): two related mob types each, at most. */
    static final String[] GENERIC_FAMILIES = {"undead", "bones", "spiders"};

    /**
     * The spawner prefix for a themeless room (PD-107, playtest 2026-10-02-1: one
     * spawner mixed zombies, breezes, spiders and skeletons). Picks one narrow
     * family from the cell, so a room reads as one idea and the same cell always
     * rolls the same family. A configured share keeps the broad pool, the
     * explicit chaotic spawner ({@code null} prefix).
     */
    static String genericPrefix(BlockPos cellOrigin) {
        long h = cellOrigin.asLong() * 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        double roll = ((h >>> 11) & 0xFFFFFFFFL) / (double) 0x100000000L;
        if (roll < PocketDungeonsConfig.chaoticSpawnerChance()) {
            return null;
        }
        int pick = (int) Math.floorMod(h >>> 3, (long) GENERIC_FAMILIES.length);
        return GENERIC_FAMILIES[pick];
    }

    static String configId(String prefix, int tier, boolean ominous) {
        String clampedTier = "tier_" + Math.max(1, Math.min(3, tier));
        String base = prefix == null || prefix.isBlank() ? clampedTier : prefix + "_" + clampedTier;
        return PocketDungeonsMod.MOD_ID + ":" + base + (ominous ? "/ominous" : "/normal");
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
                                          String prefix, int tier, boolean ominous) {
        writeInlineConfigById(level, tag, key, Identifier.parse(configId(prefix, tier, ominous)));
    }

    /**
     * Resolves a config by registry id, scales its mob counts for Swarming, and
     * writes the result inline under {@code key}. Shared by the theme-based and
     * situation-handler encounter paths.
     */
    private static void writeInlineConfigById(ServerLevel level, CompoundTag tag, String key,
                                              Identifier id) {
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

    // ---- authored block helpers (ROOM_AUTHORING_SPEC Phase A) --------------

    /**
     * Finds all trial spawners in the cell whose block entity carries the
     * {@link #PD_AUTHORED} flag. These are editor-placed spawners that the
     * stamp pipeline reconfigures in place rather than replacing.
     */
    private static List<BlockPos> authoredTrialSpawners(ServerLevel level, BlockPos cellOrigin) {
        List<BlockPos> found = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockEntity> entry : cellBlockEntities(level, cellOrigin)) {
            if (entry.getValue() instanceof TrialSpawnerBlockEntity) {
                CompoundTag tag = entry.getValue().saveWithoutMetadata(level.registryAccess());
                if (tag.getBooleanOr(PD_AUTHORED, false)) {
                    found.add(entry.getKey().immutable());
                }
            }
        }
        found.sort(TrialContent::compare);
        return found;
    }

    /**
     * Reconfigures an authored trial spawner in place: sets the ominous
     * blockstate, loads the run's config ids, cooldown, and range into the
     * existing block entity without replacing the block.
     */
    private static void reconfigureTrialSpawner(ServerLevel level, BlockPos pos,
                                                 String prefix, int tier, boolean ominous,
                                                 boolean swarming, boolean silenced,
                                                 boolean overclocked, int cooldown) {
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.TRIAL_SPAWNER) && state.getValue(TrialSpawnerBlock.OMINOUS) != ominous) {
            level.setBlock(pos, state.setValue(TrialSpawnerBlock.OMINOUS, ominous), FLAGS);
        }
        if (!(level.getBlockEntity(pos) instanceof TrialSpawnerBlockEntity spawner)) {
            PocketDungeonsMod.LOG.warn("Authored trial spawner at {} has no block entity",
                    pos.toShortString());
            return;
        }
        CompoundTag tag = new CompoundTag();
        if (swarming) {
            writeInlineConfig(level, tag, "normal_config", prefix, tier, false);
            writeInlineConfig(level, tag, "ominous_config", prefix, tier, true);
        } else {
            tag.putString("normal_config", configId(prefix, tier, false));
            tag.putString("ominous_config", configId(prefix, tier, true));
        }
        tag.putInt("target_cooldown_length", cooldown);
        tag.putInt("required_player_range",
                silenced ? PocketDungeonsConfig.silencedPlayerRange() : 14);
        ValueInput input = TagValueInput.create(
                ProblemReporter.DISCARDING, level.registryAccess(), tag);
        spawner.getTrialSpawner().load(input);
        spawner.setChanged();
        spawner.markUpdated();
    }

    /**
     * Reconfigures an authored trial spawner with a fixed situation prefix
     * (e.g. {@code "breeze_arena"}) instead of the run theme's prefix.
     */
    private static void reconfigureTrialSpawnerById(ServerLevel level, BlockPos pos,
                                                     String configPrefix, int tier, boolean ominous,
                                                     boolean swarming, boolean silenced,
                                                     boolean overclocked, int cooldown) {
        String normalId = PocketDungeonsMod.MOD_ID + ":" + configPrefix + "/normal";
        String ominousId = PocketDungeonsMod.MOD_ID + ":" + configPrefix + "/ominous";
        reconfigureTrialSpawnerByIds(level, pos, normalId, ominousId, ominous,
                swarming, silenced, overclocked, cooldown);
    }

    /**
     * M68: reconfigures an authored trial spawner with explicit normal and
     * ominous config ids, the path a theme's namespaced
     * {@code normal_spawner} / {@code ominous_spawner} overrides take. Each id
     * is a fully qualified {@code namespace:path} string.
     */
    private static void reconfigureTrialSpawnerByIds(ServerLevel level, BlockPos pos,
                                                      String normalId, String ominousId, boolean ominous,
                                                      boolean swarming, boolean silenced,
                                                      boolean overclocked, int cooldown) {
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.TRIAL_SPAWNER) && state.getValue(TrialSpawnerBlock.OMINOUS) != ominous) {
            level.setBlock(pos, state.setValue(TrialSpawnerBlock.OMINOUS, ominous), FLAGS);
        }
        if (!(level.getBlockEntity(pos) instanceof TrialSpawnerBlockEntity spawner)) {
            PocketDungeonsMod.LOG.warn("Authored trial spawner at {} has no block entity",
                    pos.toShortString());
            return;
        }
        CompoundTag tag = new CompoundTag();
        if (swarming) {
            writeInlineConfigById(level, tag, "normal_config", Identifier.parse(normalId));
            writeInlineConfigById(level, tag, "ominous_config", Identifier.parse(ominousId));
        } else {
            tag.putString("normal_config", normalId);
            tag.putString("ominous_config", ominousId);
        }
        tag.putInt("target_cooldown_length", cooldown);
        tag.putInt("required_player_range",
                silenced ? PocketDungeonsConfig.silencedPlayerRange() : 14);
        ValueInput input = TagValueInput.create(
                ProblemReporter.DISCARDING, level.registryAccess(), tag);
        spawner.getTrialSpawner().load(input);
        spawner.setChanged();
        spawner.markUpdated();
    }

    /**
     * Finds all vaults in the cell whose block entity carries the
     * {@link #PD_AUTHORED} flag. These are editor-placed vaults that the
     * stamp pipeline reconfigures in place rather than promoting a chest.
     */
    private static List<BlockPos> authoredVaults(ServerLevel level, BlockPos cellOrigin) {
        List<BlockPos> found = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockEntity> entry : cellBlockEntities(level, cellOrigin)) {
            if (entry.getValue() instanceof VaultBlockEntity) {
                CompoundTag tag = entry.getValue().saveWithoutMetadata(level.registryAccess());
                if (tag.getBooleanOr(PD_AUTHORED, false)) {
                    found.add(entry.getKey().immutable());
                }
            }
        }
        found.sort(TrialContent::compare);
        return found;
    }

    /**
     * Reconfigures an authored vault in place: sets the ominous blockstate
     * and loads the run's loot table, key item, and activation range into
     * the existing block entity without replacing the block.
     */
    private static void reconfigureVault(ServerLevel level, BlockPos pos, int tier,
                                         boolean ominous, String lootSuffix) {
        reconfigureVault(level, pos, tier, ominous, lootSuffix, null);
    }

    /** M68: reconfigureVault with an optional namespaced loot table override. */
    private static void reconfigureVault(ServerLevel level, BlockPos pos, int tier,
                                         boolean ominous, String lootSuffix, String lootTableOverride) {
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.VAULT) && state.getValue(VaultBlock.OMINOUS) != ominous) {
            level.setBlock(pos, state.setValue(VaultBlock.OMINOUS, ominous), FLAGS);
        }
        if (!(level.getBlockEntity(pos) instanceof VaultBlockEntity vault)) {
            PocketDungeonsMod.LOG.warn("Authored vault at {} has no block entity",
                    pos.toShortString());
            return;
        }
        ResourceKey<LootTable> table = resolveLootTable(level,
                LootTables.vaultTable(tier, ominous), lootSuffix, lootTableOverride);
        vault.setConfig(new VaultConfig(table, ACTIVATION_RANGE, DEACTIVATION_RANGE,
                keyStack(ominous), Optional.empty()));
        vault.setChanged();
    }

    // ---- encounter anchor ---------------------------------------------------

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
        return applyLoot(level, cellOrigin, tier, ominous, seed, lootSuffix, null);
    }

    /**
     * M68: applyLoot with an optional namespaced loot table override. The
     * override, when present and resolvable, wins over the legacy
     * {@code lootSuffix} composition so a third party theme can point at its
     * own namespace's vault table.
     */
    static boolean applyLoot(ServerLevel level, BlockPos cellOrigin, int tier, boolean ominous,
                             long seed, String lootSuffix, String lootTableOverride) {
        // Authored vaults: reconfigure in place, do not move or replace.
        List<BlockPos> authored = authoredVaults(level, cellOrigin);
        if (!authored.isEmpty()) {
            for (BlockPos pos : authored) {
                reconfigureVault(level, pos, tier, ominous, lootSuffix, lootTableOverride);
            }
            // Remaining chests (non-vault containers) become supply chests.
            for (BlockPos pos : RoomContent.containers(level, cellOrigin)) {
                if (level.getBlockEntity(pos) instanceof net.minecraft.world.RandomizableContainer c) {
                    c.setLootTable(resolveLootTable(level, LootTables.supplyTable(tier), lootSuffix));
                    c.setLootTableSeed(seed ^ pos.asLong());
                }
            }
            return true;
        }

        // Legacy path: no authored vaults, promote the first chest.
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
                resolveLootTable(level, LootTables.vaultTable(tier, ominous), lootSuffix, lootTableOverride),
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
                c.setLootTable(resolveLootTable(level, LootTables.supplyTable(tier), lootSuffix));
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
    static void placeCompletionChests(ServerLevel level, BlockPos origin,
                                        DoorMask.Direction entranceDir, int chests,
                                        int tier, boolean ominous, long seed, String lootSuffix) {
        placeRewardContainers(level, origin, entranceDir, chests, tier, 0, 0, ominous, seed,
                lootSuffix, null, List.of());
    }

    /**
     * M68: placeCompletionChests with an optional namespaced loot table
     * override. Kept for older callers; the barrel alone is placed.
     */
    static void placeCompletionChests(ServerLevel level, BlockPos origin,
                                        DoorMask.Direction entranceDir, int chests,
                                        int tier, boolean ominous, long seed, String lootSuffix,
                                        String lootTableOverride) {
        placeRewardContainers(level, origin, entranceDir, chests, tier, 0, 0, ominous, seed,
                lootSuffix, lootTableOverride, List.of());
    }

    /**
     * The reward corner of a cleared floor's terminal cell (2026-10-05 rework):
     * two containers, nothing more. A barrel holds every loot roll the floor
     * earned (the old three chests' rolls, and a finished dungeon's vault
     * rolls, all merged into it), and a copper chest beside it holds the
     * floor's promised rewards, the items its door advertised. Spots are
     * mirrored by the entrance direction so they always face the player
     * walking in; the other authored spots are cleared to air.
     *
     * <p>The barrel's rolls are drawn now, not lazily on open, because one
     * container must hold several chests' worth: merging the stacks keeps
     * overflow rare, and any that still does not fit drops at the barrel's
     * feet rather than voiding.
     */
    static void placeRewardContainers(ServerLevel level, BlockPos origin,
                                        DoorMask.Direction entranceDir, int rolls,
                                        int tier, int vaultRolls, int vaultTier,
                                        boolean ominous, long seed, String lootSuffix,
                                        String lootTableOverride, List<ItemStack> promised) {
        BlockPos[] spots = completionSpots(entranceDir);
        Direction facing = completionFacing(entranceDir);
        BlockPos barrelPos = origin.offset(spots[1]);
        BlockPos copperPos = origin.offset(spots[0]);
        // The third authored spot is always cleared.
        level.setBlock(origin.offset(spots[2]), Blocks.AIR.defaultBlockState(), FLAGS);

        level.setBlock(barrelPos, Blocks.BARREL.defaultBlockState()
                .setValue(net.minecraft.world.level.block.BarrelBlock.FACING, facing), FLAGS);
        List<ItemStack> rolled = new ArrayList<>();
        if (level.getServer() != null) {
            LootParams.Builder params = new LootParams.Builder(level)
                    .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(barrelPos));
            if (rolls > 0) {
                ResourceKey<LootTable> table = resolveLootTable(level,
                        LootTables.tierTable(tier, ominous), lootSuffix, lootTableOverride);
                LootTable loot = level.getServer().reloadableRegistries().getLootTable(table);
                for (int i = 0; i < rolls; i++) {
                    rolled.addAll(loot.getRandomItems(params.create(LootContextParamSets.CHEST),
                            level.getRandom().nextLong() ^ seed ^ (long) i));
                }
            }
            // The finished dungeon's vault pays at the band's top tier (D11).
            if (vaultRolls > 0) {
                LootTable vault = level.getServer().reloadableRegistries()
                        .getLootTable(resolveLootTable(level, LootTables.tierTable(vaultTier, ominous),
                                lootSuffix, lootTableOverride));
                for (int i = 0; i < vaultRolls; i++) {
                    rolled.addAll(vault.getRandomItems(params.create(LootContextParamSets.CHEST),
                            level.getRandom().nextLong() ^ seed ^ 0x7A17L ^ (long) i));
                }
            }
        }
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack stack : rolled) {
            boolean joined = false;
            for (ItemStack kept : merged) {
                if (ItemStack.isSameItemSameComponents(kept, stack)
                        && kept.getCount() + stack.getCount() <= kept.getMaxStackSize()) {
                    kept.grow(stack.getCount());
                    joined = true;
                    break;
                }
            }
            if (!joined) {
                merged.add(stack);
            }
        }
        if (level.getBlockEntity(barrelPos) instanceof net.minecraft.world.Container container) {
            for (ItemStack stack : merged) {
                boolean placed = false;
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    ItemStack in = container.getItem(slot);
                    if (in.isEmpty()) {
                        container.setItem(slot, stack.copy());
                        placed = true;
                        break;
                    } else if (ItemStack.isSameItemSameComponents(in, stack)
                            && in.getCount() + stack.getCount() <= in.getMaxStackSize()) {
                        in.grow(stack.getCount());
                        placed = true;
                        break;
                    }
                }
                if (!placed) {
                    net.minecraft.world.entity.item.ItemEntity drop =
                            new net.minecraft.world.entity.item.ItemEntity(level,
                                    barrelPos.getX() + 0.5, barrelPos.getY() + 1.0,
                                    barrelPos.getZ() + 0.5, stack.copy());
                    level.addFreshEntity(drop);
                }
            }
        }

        level.setBlock(copperPos, copperChestBlock().defaultBlockState()
                .setValue(ChestBlock.FACING, facing), FLAGS);
        if (!promised.isEmpty()
                && level.getBlockEntity(copperPos) instanceof net.minecraft.world.Container container) {
            for (ItemStack stack : promised) {
                for (int slot = 0; slot < container.getContainerSize() && !stack.isEmpty(); slot++) {
                    ItemStack in = container.getItem(slot);
                    if (in.isEmpty()) {
                        container.setItem(slot, stack.copy());
                        stack.setCount(0);
                        break;
                    }
                }
            }
        }
    }

    /** The promised-reward chest: a plain copper chest (the bag chest's oxidized cousin). */
    private static net.minecraft.world.level.block.Block copperChestBlock() {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(
                net.minecraft.resources.Identifier.withDefaultNamespace("copper_chest"));
    }

    private static BlockPos[] completionSpots(DoorMask.Direction entranceDir) {
        return switch (entranceDir) {
            case NORTH -> new BlockPos[]{new BlockPos(4, 1, 12), new BlockPos(8, 1, 12),
                    new BlockPos(12, 1, 12)};
            case SOUTH -> new BlockPos[]{new BlockPos(4, 1, 4), new BlockPos(8, 1, 4),
                    new BlockPos(12, 1, 4)};
            case WEST -> new BlockPos[]{new BlockPos(12, 1, 4), new BlockPos(12, 1, 8),
                    new BlockPos(12, 1, 12)};
            case EAST -> new BlockPos[]{new BlockPos(4, 1, 4), new BlockPos(4, 1, 8),
                    new BlockPos(4, 1, 12)};
        };
    }

    private static Direction completionFacing(DoorMask.Direction entranceDir) {
        return switch (entranceDir) {
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case WEST -> Direction.WEST;
            case EAST -> Direction.EAST;
        };
    }

    private static void placeLootChest(ServerLevel level, BlockPos pos, Direction facing,
                                       ResourceKey<LootTable> table, long seed) {
        level.setBlock(pos, Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, facing), FLAGS);
        if (level.getBlockEntity(pos) instanceof net.minecraft.world.RandomizableContainer c) {
            c.setLootTable(table);
            c.setLootTableSeed(seed ^ pos.asLong());
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

    /**
     * PD-87: drops any custom data from a vault key in place, so a key a chest
     * table once tagged ({@code pocketdungeons.bag}) is the same bare item the
     * vault asks for and a spawner ejects. Anything that is not a vault key is
     * left alone.
     */
    static void bareKey(ItemStack stack) {
        if (stack.isEmpty() || !stack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) {
            return;
        }
        if (stack.is(keyStack(false).getItem()) || stack.is(keyStack(true).getItem())) {
            stack.remove(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        }
    }

    // ---- shared -------------------------------------------------------------

    private static ResourceKey<LootTable> resolveLootTable(ServerLevel level, String basePath,
                                                            String suffix) {
        return resolveLootTable(level, basePath, suffix, null);
    }

    /**
     * M68: resolves a loot table, honouring a theme's namespaced
     * {@code loot_table} override ahead of the legacy {@code loot_suffix}
     * composition. The override is a fully qualified id
     * ({@code mypack:vaults/my_vault}); when present and the table exists, it
     * wins, so a third party theme can point at its own namespace's loot table
     * instead of secretly requiring a {@code pocketdungeons} table the suffix
     * composition always built. The legacy suffix path is the fallback.
     */
    private static ResourceKey<LootTable> resolveLootTable(ServerLevel level, String basePath,
                                                            String suffix, String overrideId) {
        if (overrideId != null && !overrideId.isBlank()) {
            ResourceKey<LootTable> candidate = ResourceKey.create(Registries.LOOT_TABLE,
                    Identifier.parse(overrideId));
            if (LootTables.exists(level.getServer(), candidate)) {
                return candidate;
            }
        }
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

    /**
     * A cell is exactly one chunk, so this is a map lookup, not a 1,792-block scan.
     * The range reaches down through a two story room's lower story: PD-98's
     * {@code blaze_cellar} authors its spawner at y -8, and a range that
     * started at the cell floor never saw it.
     */
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
                    && dy >= -RoomGeometry.storyOffset(RoomGeometry.MAX_SPAN_Y)
                    && dy <= RoomGeometry.CEILING_Y) {
                out.add(entry);
            }
        }
        return out;
    }
}
