package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@link FloorObjective#GATHER} floor (The First Night): the floor scatters a few spare blocks more
 * than its quota of one material (logs, stone, coal, iron) in columns through its cells, and the pad holds
 * until the party has broken the quota by hand. A break is the whole verb: the block is unprotected, any
 * tool or fist mines it, the count rises and the block drops what it drops. Blocks past the quota pay one
 * bonus reward roll each. Unbroken blocks wink a sparkle once a second.
 *
 * <p>The material and the quota are the node's {@code gather} spec. The quota grows by half of it for each
 * member past the first. Placement runs under the generic fallback rule: no free spot for a single block
 * means no objective, and the floor runs as an ordinary spawner floor.
 */
final class GatherObjective extends FloorObjectives.ObjectiveState {

    /** The set-block flags every container swap in this mod uses (see {@code TrialContent.FLAGS}). */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS
            | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

    /** Most columns one cell may hold, so the material spreads over the floor. */
    private static final int COLUMNS_PER_CELL = 4;

    /** The tallest a column grows (a trunk, a pillar, an ore seam). */
    private static final int COLUMN_HEIGHT = 3;

    /** Spare blocks placed past the quota, so a missed one never soft locks a floor. */
    static final int SPARE = 4;

    private final ServerLevel level;

    /** The material, as the node names it. */
    private final BlockState state;

    /** The player facing name of the material ("Oak Log"). */
    private final String label;

    /** Every block placed, broken or not; kept for tests and bookkeeping. */
    final List<BlockPos> placed = new ArrayList<>();

    /** The blocks still standing. */
    final Set<BlockPos> unbroken = new HashSet<>();

    /** The blocks the party must break: the node's count, plus half of it per extra member, clamped to what landed. */
    int quota;

    /** Blocks broken so far. */
    int gathered;

    private GatherObjective(ServerLevel level, BlockState state) {
        super(FloorObjective.GATHER, false);
        this.level = level;
        this.state = state;
        this.label = state.getBlock().getName().getString();
    }

    /** The quota for a party: {@code count}, plus half of it for each member past the first. */
    static int quotaFor(int count, int members) {
        return count + Math.max(0, members - 1) * (count / 2);
    }

    static GatherObjective arm(ServerLevel level, InstanceRecord record, DungeonPlan plan) {
        DungeonDef def = TripView.def(record);
        DungeonDef.Node node = def == null ? null : def.node(record.interval.nodeId);
        DungeonDef.Node.Gather spec = node == null ? null : node.gather();
        Block block = spec == null ? null
                : BuiltInRegistries.BLOCK.getOptional(Identifier.parse(spec.block())).orElse(null);
        if (block == null || plan == null || record.layout == null) {
            GatherObjective none = new GatherObjective(level, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            none.fallback = true;
            return none;
        }
        GatherObjective gather = new GatherObjective(level, block.defaultBlockState());
        int want = quotaFor(spec.count(), record.members.size()) + SPARE;
        gather.place(record, plan, want, level.getRandom());
        gather.quota = Math.min(quotaFor(spec.count(), record.members.size()), gather.placed.size());
        if (gather.placed.isEmpty()) {
            gather.fallback = true;
        }
        return gather;
    }

    /** Fills the floor's cells with columns of the material until {@code want} blocks stand or the cells run out. */
    private void place(InstanceRecord record, DungeonPlan plan, int want, RandomSource random) {
        List<PlanCell> order = cellOrder(plan);
        Map<PlanCell, Set<BlockPos>> takenByCell = new HashMap<>();
        for (int pass = 0; pass < COLUMNS_PER_CELL && placed.size() < want; pass++) {
            for (PlanCell cell : order) {
                if (placed.size() >= want) {
                    return;
                }
                Set<BlockPos> taken = takenByCell.computeIfAbsent(cell, c -> {
                    Set<BlockPos> seeded = new HashSet<>();
                    seeded.add(record.layout.geometry().cellCentre(c));
                    return seeded;
                });
                BlockPos at = MineGuardians.freeSpotInCell(level,
                        record.layout.geometry().cellOrigin(cell), taken, random);
                if (at == null) {
                    continue;
                }
                for (int h = 0; h < COLUMN_HEIGHT && placed.size() < want; h++) {
                    BlockPos pos = at.above(h);
                    if (!level.getBlockState(pos).isAir()) {
                        break;
                    }
                    level.setBlock(pos, state, FLAGS);
                    placed.add(pos);
                    unbroken.add(pos);
                }
            }
        }
    }

    /** Every cell but the entrance: branch cells first, then the critical path in grid order. */
    private static List<PlanCell> cellOrder(DungeonPlan plan) {
        List<PlanCell> branches = new ArrayList<>();
        List<PlanCell> path = new ArrayList<>();
        for (PlanCell cell : plan.cells()) {
            if (cell.equals(plan.entrance())) {
                continue;
            }
            (plan.criticalPath() != null && plan.criticalPath().contains(cell) ? path : branches).add(cell);
        }
        Comparator<PlanCell> byCell = Comparator.comparingInt(PlanCell::x).thenComparingInt(PlanCell::z);
        branches.sort(byCell);
        path.sort(byCell);
        List<PlanCell> order = new ArrayList<>(branches);
        order.addAll(path);
        return order;
    }

    @Override
    boolean breakClaims(BlockPos pos) {
        return !fallback && unbroken.contains(pos);
    }

    @Override
    boolean onBreak(ServerPlayer player, BlockPos pos) {
        if (!breakClaims(pos)) {
            return false;
        }
        unbroken.remove(pos);
        gathered++;
        player.sendOverlayMessage(Component.literal(progressLine()).withStyle(ChatFormatting.GREEN));
        return true;
    }

    @Override
    void tick(MinecraftServer server, InstanceRecord record) {
        if (fallback) {
            return;
        }
        for (BlockPos pos : unbroken) {
            FloorObjectives.sparkle(level, pos);
        }
    }

    @Override
    String padRefusal() {
        if (fallback || gathered >= quota) {
            return null;
        }
        int left = quota - gathered;
        return "Not yet. Break " + left + " more " + label + (left == 1 ? "." : "s.");
    }

    @Override
    String progressLine() {
        return fallback ? null : label + " " + Math.min(gathered, quota) + "/" + quota;
    }

    /** Each block past the quota pays one extra roll of the floor's reward table. */
    @Override
    int bonusRolls() {
        return FloorObjectiveRules.bonusRolls(gathered, quota);
    }
}
