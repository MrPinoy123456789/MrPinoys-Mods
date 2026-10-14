package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The {@link FloorObjective#HEIST} floor (sunken treasury shape): a quiet
 * floor that turns loud. At arm time every trial spawner on the floor is
 * captured, block state and block entity tag together, and swapped for
 * unbreakable reinforced deepslate, so the floor walks in silent. The Vault
 * Core (a heavy core on a gold plinth) stands in the terminal cell, and the
 * strongboxes wait in the branch cells. A member who channels on the core for
 * {@code heistCoreChannelSeconds} cracks it: the stand-ins go back exactly as
 * they were (the spawners wake with their stamped configs), the strongboxes
 * open as barrels each holding a deferred roll of the floor's tier chest
 * table, the pad unseals, and the crew rises around the core. An ominous
 * floor raises a second identical wave shortly after.
 *
 * <p>Placement runs under the generic fallback rule: no free spot for the
 * core means no heist, the state arms as {@code fallback}, and the floor
 * reads as an ordinary spawner floor everywhere else. Strongboxes are
 * optional loot, so a cell that offers no free spot is skipped, never a
 * failure.
 */
final class HeistObjective extends FloorObjectives.ObjectiveState {

    /** The sealed line: the bar's progress before the crack. */
    static final String SEALED_LINE = "The vault is sealed.";
    /** The pad's refusal while sealed: names the verb, a channel is any hit or click on the core. */
    static final String SEALED_PAD = "The vault is sealed. Crack the core.";
    /** The bar's progress line once the vault is open. */
    static final String OPEN_LINE = "The vault is open.";
    /** The overlay line a channelling member sees once a second. */
    static final String CHANNEL_LINE = "Cracking the vault...";

    /** Tag on every crew mob the crack raises, so tests and tools can find them. */
    static final String CREW_TAG = PocketDungeonsMod.MOD_ID + ".heist_crew";

    /** How far (Chebyshev, blocks) the channeler may stray from the core before the channel resets. */
    private static final int CHANNEL_RANGE = 4;

    /** Ticks between the crack and an ominous floor's second crew wave (one watch tick). */
    static final int OMINOUS_ECHO_TICKS = 20;

    /** The set-block flags every container swap in this mod uses (see {@code TrialContent.FLAGS}). */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS
            | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

    /** The block a strongbox is before the crack: waxed oxidized copper, a sealed chest's look. */
    private static final Block STRONGBOX_BLOCK = BuiltInRegistries.BLOCK
            .getValue(Identifier.parse("minecraft:waxed_oxidized_copper"));

    /** One swapped-out spawner: its position, block state and full block entity tag, kept for the crack. */
    record StandIn(BlockPos pos, BlockState state, CompoundTag tag) {}

    /** The level the floor stands in; captured at arm so the tick needs no dimension lookup. */
    private final ServerLevel level;

    /** Every spawner this floor hid behind deepslate, in capture order. */
    final List<StandIn> standIns = new ArrayList<>();

    /** The strongbox positions; each becomes a paying barrel on the crack. */
    final List<BlockPos> strongboxes = new ArrayList<>();

    /** The gold plinth's position; the core sits on it. {@code null} on a fallback floor. */
    BlockPos plinth;

    /** The Vault Core's position. {@code null} on a fallback floor. */
    BlockPos core;

    /** True once the vault is cracked: the pad is open, the spawners are back, the crew is out. */
    boolean cracked;

    /** The member mid-channel on the core, or {@code null} between channels. */
    UUID channeler;

    /** Server tick the running channel completes at. */
    long channelEndsAt;

    /** Server tick an ominous floor's second crew wave lands at, or 0 when none is owed. */
    long ominousEchoAt;

    private HeistObjective(ServerLevel level) {
        super(FloorObjective.HEIST, false);
        this.level = level;
    }

    /**
     * Places the heist on a freshly stamped floor. The core's spot is found
     * before anything is touched: a terminal cell with no free spot arms a
     * fallback state and the floor runs as an ordinary spawner floor.
     */
    static HeistObjective arm(ServerLevel level, InstanceRecord record, DungeonPlan plan) {
        HeistObjective state = new HeistObjective(level);
        if (plan == null || record.layout == null) {
            state.fallback = true;
            return state;
        }
        RandomSource random = level.getRandom();

        // The Vault Core stands in the terminal cell, off the pad and out of
        // the door lanes (the centre seed keeps it clear of the pad itself).
        Set<BlockPos> taken = new HashSet<>();
        taken.add(record.layout.terminal().offset(
                RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2));
        state.plinth = MineGuardians.freeSpotInCell(level, record.layout.terminal(), taken, random);
        if (state.plinth == null) {
            state.fallback = true;
            return state;
        }

        // Quiet floor: every trial spawner becomes reinforced deepslate until
        // the crack puts each back with its stamped config intact.
        for (BlockPos pos : record.layout.trialSpawners()) {
            BlockEntity entity = level.getBlockEntity(pos);
            state.standIns.add(new StandIn(pos.immutable(), level.getBlockState(pos),
                    entity == null ? new CompoundTag()
                            : entity.saveWithFullMetadata(level.registryAccess())));
            level.setBlock(pos, Blocks.REINFORCED_DEEPSLATE.defaultBlockState(), FLAGS);
        }

        level.setBlock(state.plinth, Blocks.GOLD_BLOCK.defaultBlockState(), FLAGS);
        state.core = state.plinth.above();
        level.setBlock(state.core, Blocks.HEAVY_CORE.defaultBlockState(), FLAGS);
        state.fixtures.add(state.plinth);
        state.fixtures.add(state.core);

        placeStrongboxes(level, record, plan, state, random);
        return state;
    }

    /**
     * Scatters the floor's strongboxes: one pass over the branch cells (the
     * cells off the critical path), then a second pass over every cell that
     * is not the entrance or the terminal, at most two boxes to a cell. A
     * cell with no free spot is skipped; the boxes are greed, never the gate.
     */
    private static void placeStrongboxes(ServerLevel level, InstanceRecord record, DungeonPlan plan,
                                         HeistObjective state, RandomSource random) {
        int want = FloorObjectiveRules.strongboxes(PocketDungeonsConfig.heistStrongboxBase(),
                record.members.size());
        if (want <= 0) {
            return;
        }
        List<PlanCell> branches = new ArrayList<>();
        List<PlanCell> rest = new ArrayList<>();
        for (PlanCell cell : plan.cells()) {
            if (cell.equals(plan.entrance()) || cell.equals(plan.terminal())) {
                continue;
            }
            (plan.criticalPath() != null && plan.criticalPath().contains(cell) ? rest : branches).add(cell);
        }
        Comparator<PlanCell> byCell = Comparator.comparingInt(PlanCell::x).thenComparingInt(PlanCell::z);
        branches.sort(byCell);
        rest.sort(byCell);
        List<PlanCell> order = new ArrayList<>(branches);
        order.addAll(rest);

        Map<PlanCell, Set<BlockPos>> takenByCell = new HashMap<>();
        for (int pass = 0; pass < 2 && state.strongboxes.size() < want; pass++) {
            for (PlanCell cell : order) {
                if (state.strongboxes.size() >= want) {
                    return;
                }
                Set<BlockPos> cellTaken = takenByCell.computeIfAbsent(cell, c -> {
                    Set<BlockPos> seeded = new HashSet<>();
                    seeded.add(record.layout.geometry().cellCentre(c));
                    return seeded;
                });
                BlockPos at = MineGuardians.freeSpotInCell(level,
                        record.layout.geometry().cellOrigin(cell), cellTaken, random);
                if (at == null) {
                    continue;
                }
                level.setBlock(at, STRONGBOX_BLOCK.defaultBlockState(), FLAGS);
                state.fixtures.add(at);
                state.strongboxes.add(at);
            }
        }
    }

    /**
     * A right-click on the core starts (or, while one runs, declines to
     * restart) the channel. Progress lives on this state, so a broken
     * channel resets and the next member to use the core starts it over.
     */
    @Override
    boolean onUse(ServerPlayer player, BlockPos pos) {
        if (cracked || fallback || !pos.equals(core)) {
            return false;
        }
        if (channeler == null) {
            channeler = player.getUUID();
            channelEndsAt = player.level().getServer().getTickCount()
                    + PocketDungeonsConfig.heistCoreChannelSeconds() * 20L;
            player.sendOverlayMessage(Component.literal(CHANNEL_LINE).withStyle(ChatFormatting.GOLD));
        }
        return true;
    }

    /** A swing at the core works the same as a click: it starts the channel. */
    @Override
    boolean onAttack(ServerPlayer player, BlockPos pos) {
        return onUse(player, pos);
    }

    @Override
    void tick(MinecraftServer server, InstanceRecord record) {
        if (!cracked && core != null) {
            FloorObjectives.sparkle(level, core);
        }
        long now = server.getTickCount();
        if (ominousEchoAt != 0 && now >= ominousEchoAt) {
            ominousEchoAt = 0;
            raiseCrew(record);
        }
        if (cracked || channeler == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(channeler);
        // The channel ends with the channeler gone, out of the level, or out
        // of reach; the stored progress resets and anybody may pick it up.
        if (player == null || player.level() != level || outOfRange(player)) {
            channeler = null;
            return;
        }
        player.sendOverlayMessage(Component.literal(CHANNEL_LINE).withStyle(ChatFormatting.GOLD));
        if (now >= channelEndsAt) {
            crack(record);
        }
    }

    /** A hit breaks a running channel. */
    @Override
    void channelerHurt(ServerPlayer player) {
        if (channeler != null && channeler.equals(player.getUUID())) {
            channeler = null;
            player.sendOverlayMessage(Component.literal("Interrupted.").withStyle(ChatFormatting.RED));
        }
    }

    @Override
    String padRefusal() {
        return cracked || fallback ? null : SEALED_PAD;
    }

    @Override
    String progressLine() {
        if (fallback) {
            return null;
        }
        return cracked ? OPEN_LINE : SEALED_LINE;
    }

    /**
     * The vault cracks: every stand-in goes back to its captured block state
     * and block entity tag (the spawners wake with their stamped configs
     * intact), each strongbox becomes a barrel holding a deferred roll of the
     * floor's tier chest table, and the crew rises. Idempotent: a second call
     * changes nothing.
     */
    void crack(InstanceRecord record) {
        if (cracked) {
            return;
        }
        cracked = true;
        channeler = null;
        for (StandIn standIn : standIns) {
            level.setBlock(standIn.pos(), standIn.state(), FLAGS);
            BlockEntity entity = level.getBlockEntity(standIn.pos());
            if (entity != null && !standIn.tag().isEmpty()) {
                entity.loadWithComponents(TagValueInput.create(
                        ProblemReporter.DISCARDING, level.registryAccess(), standIn.tag()));
                entity.setChanged();
            }
        }
        for (BlockPos pos : strongboxes) {
            if (!level.getBlockState(pos).is(STRONGBOX_BLOCK)) {
                continue;
            }
            level.setBlock(pos, Blocks.BARREL.defaultBlockState()
                    .setValue(BarrelBlock.FACING, Direction.UP), FLAGS);
            if (level.getBlockEntity(pos) instanceof RandomizableContainer container) {
                container.setLootTable(strongboxTable(record));
                container.setLootTableSeed(record.layout.seed() ^ pos.asLong());
            }
        }
        raiseCrew(record);
        if (record.floor.affixes.contains(AffixIds.OMINOUS)) {
            ominousEchoAt = level.getServer().getTickCount() + OMINOUS_ECHO_TICKS;
        }
    }

    /**
     * The crew the crack wakes: {@code heistCrewBase} drowned plus one per
     * member, risen in a ring around the core and scaled the way an omen wave
     * is (keystone level, floor bonus and omen), carrying the no-drops tag.
     */
    private void raiseCrew(InstanceRecord record) {
        int count = PocketDungeonsConfig.heistCrewBase() + Math.max(0, record.members.size());
        for (int i = 0; i < count; i++) {
            double angle = 2.0 * Math.PI * i / Math.max(1, count);
            BlockPos at = plinth.offset((int) Math.round(Math.cos(angle) * 3.0), 0,
                    (int) Math.round(Math.sin(angle) * 3.0));
            Mob mob = EntityTypes.DROWNED.spawn(level, at, EntitySpawnReason.TRIGGERED);
            if (mob == null) {
                PocketDungeonsMod.LOG.warn("Heist crew mob failed to spawn at {}", at.toShortString());
                continue;
            }
            mob.addTag(CREW_TAG);
            mob.addTag(PocketDungeonsMod.OMEN_WAVE_TAG);
            mob.setPersistenceRequired();
            MobScaling.applyMobScale(mob, record.layout.keystoneLevel() + record.floor.levelBonus,
                    Omen.clamp(record.interval.omen));
        }
    }

    /** The table a strongbox pays: the floor's own tier chest table, resolved the completion-chest way. */
    private ResourceKey<LootTable> strongboxTable(InstanceRecord record) {
        ThemeManifest.Entry theme = record.floor.theme == null ? null
                : ThemeManifest.current().byId(record.floor.theme);
        String suffix = theme == null ? null : theme.meta().lootSuffix;
        int tier = LootBands.floorTier(record, ZoneRules.of(record), record.interval.floorIndex + 1);
        return TrialContent.strongboxTable(level, tier,
                record.floor.affixes.contains(AffixIds.OMINOUS), suffix);
    }

    /** Whether the channeler has strayed out of the core's reach. */
    private boolean outOfRange(ServerPlayer player) {
        BlockPos at = player.blockPosition();
        return Math.max(Math.abs(at.getX() - core.getX()),
                Math.max(Math.abs(at.getY() - core.getY()), Math.abs(at.getZ() - core.getZ())))
                > CHANNEL_RANGE;
    }
}
