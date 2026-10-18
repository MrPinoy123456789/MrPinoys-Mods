package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@link FloorObjective#CLAIM} floor (gilded seam shape): the floor hides
 * {@code claimQuota + claimExtraVeins} gilded blackstone veins in its cells,
 * and the pad holds until the party has claimed the quota by hand. A claim is
 * a break: the vein is unprotected and any tool or fist mines it, the count
 * and the Tremor rise by one, and the floor answers. Once Tremor reaches
 * {@code claimWaveTremor} every further claim raises a hoglin pack at the
 * vein, and at {@code claimHunterTremor} the floor sends The Assayer once.
 * Veins claimed past the quota pay one bonus reward roll each
 * ({@link FloorObjectiveRules#bonusRolls}). Unclaimed veins wink a sparkle
 * once a second so they read as the objective.
 *
 * <p>Placement runs under the generic fallback rule: no free spot for a single
 * vein means no claim, the state arms as {@code fallback}, and the floor reads
 * as an ordinary spawner floor everywhere else. The waves and The Assayer are
 * pressure only: a spawn that fails never un-credits the claim that triggered
 * it.
 */
final class ClaimObjective extends FloorObjectives.ObjectiveState {

    /** The block a vein is before it is claimed: gilded blackstone, a seam worth working. */
    private static final Block VEIN_BLOCK = Blocks.GILDED_BLACKSTONE;

    /** Tag on every mob a claim raises, so tests and tools can find them. */
    static final String WAVE_TAG = PocketDungeonsMod.MOD_ID + ".claim_wave";

    /** The name the floor's hunter carries. */
    static final String ASSAYER_NAME = "The Assayer";

    /** The set-block flags every container swap in this mod uses (see {@code TrialContent.FLAGS}). */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS
            | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

    /** Most veins one cell may hold: the seam spreads rather than stacking. */
    private static final int VEINS_PER_CELL = 2;

    /**
     * The health multiplier The Assayer gets on top of the ordinary mob scale,
     * the guardian idiom ({@link MineGuardians#scale} gives a named mob the
     * ordinary scale times {@code 1 + hardness}; a hardness of one doubles it).
     */
    private static final double ASSAYER_HEALTH_MULT = 2.0;

    /** The level the floor stands in; captured at arm so the claim needs no dimension lookup. */
    private final ServerLevel level;

    /** The record this floor armed for; the claim reads party size, keystone and omen off it live. */
    private final InstanceRecord record;

    /** The plan the floor was armed from, kept for the Assayer's neighbour lookup. */
    private DungeonPlan plan;

    /** The vein positions, claimed or not; kept for tests and bookkeeping. */
    final List<BlockPos> veins = new ArrayList<>();

    /** The veins still standing. A vein is never a fixture: breaking it IS the claim. */
    final Set<BlockPos> unclaimed = new HashSet<>();

    /** The veins the quota actually demands: {@code claimQuota} clamped to what was placed. */
    int quota;

    /** Veins claimed so far. */
    int claimed;

    /** How loud the floor has grown: one per claim, plus one for an ominous floor's head start. */
    int tremor;

    /** True once the floor has sent The Assayer; it never sends a second. */
    boolean assayerOut;

    private ClaimObjective(ServerLevel level, InstanceRecord record) {
        super(FloorObjective.CLAIM, false);
        this.level = level;
        this.record = record;
    }

    /**
     * Lays the seam on a freshly stamped floor. Veins spread over every cell
     * but the entrance, branch cells first and the critical path after them,
     * at most {@link #VEINS_PER_CELL} to a cell, so the terminal cell only
     * takes veins a small plan has nowhere else to put. A floor with no free
     * spot for one vein arms a fallback state and runs as a spawner floor.
     */
    static ClaimObjective arm(ServerLevel level, InstanceRecord record, DungeonPlan plan) {
        ClaimObjective state = new ClaimObjective(level, record);
        if (plan == null || record.layout == null) {
            state.fallback = true;
            return state;
        }
        state.plan = plan;
        int want = FloorObjectiveRules.veins(PocketDungeonsConfig.claimQuota(),
                PocketDungeonsConfig.claimExtraVeins(), record.members.size());
        placeVeins(level, record, plan, state, want, level.getRandom());
        state.quota = FloorObjectiveRules.effectiveQuota(PocketDungeonsConfig.claimQuota(),
                state.veins.size());
        if (state.veins.isEmpty()) {
            state.fallback = true;
            return state;
        }
        if (record.floor.affixes.contains(AffixIds.OMINOUS)) {
            state.tremor = 1;
        }
        return state;
    }

    /**
     * Scatters the floor's veins the way the heist scatters its strongboxes:
     * one pass over the branch cells, then a second over the critical path,
     * {@link #VEINS_PER_CELL} passes in all so no cell stacks more than two.
     * A cell with no free spot is skipped; the quota clamps to what landed.
     */
    private static void placeVeins(ServerLevel level, InstanceRecord record, DungeonPlan plan,
                                   ClaimObjective state, int want, RandomSource random) {
        List<PlanCell> order = veinCellOrder(plan);
        Map<PlanCell, Set<BlockPos>> takenByCell = new HashMap<>();
        for (int pass = 0; pass < VEINS_PER_CELL && state.veins.size() < want; pass++) {
            for (PlanCell cell : order) {
                if (state.veins.size() >= want) {
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
                    PocketDungeonsMod.LOG.info("claim vein: no spot in cell {} (origin {})",
                            cell, record.layout.geometry().cellOrigin(cell));
                    continue;
                }
                level.setBlock(at, VEIN_BLOCK.defaultBlockState(), FLAGS);
                state.veins.add(at);
                state.unclaimed.add(at);
            }
        }
    }

    /**
     * The cells veins may stand in: every cell but the entrance, branch cells
     * (off the critical path) first so the seam rewards wandering, then the
     * critical path cells in grid order, which leaves the terminal for last.
     */
    private static List<PlanCell> veinCellOrder(DungeonPlan plan) {
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

    /**
     * A vein is worked by breaking it: unprotected, mineable by any tool or a
     * fist, and the break itself is the claim. The count and the Tremor rise,
     * the floor growls loud enough to hear across the cell, and the pressure
     * answers. A vein already mined (gone from {@link #unclaimed}) credits
     * nothing.
     */
    @Override
    boolean breakClaims(BlockPos pos) {
        return !fallback && unclaimed.contains(pos);
    }

    @Override
    boolean onBreak(ServerPlayer player, BlockPos pos) {
        if (!breakClaims(pos)) {
            return false;
        }
        unclaimed.remove(pos);
        claimed++;
        tremor++;
        level.playSound(null, pos, SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.2f, 0.8f);
        level.sendParticles(ParticleTypes.FLAME, pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5,
                8, 0.3, 0.4, 0.3, 0.02);
        player.sendOverlayMessage(Component.literal(progressLine()).withStyle(ChatFormatting.GOLD));
        if (FloorObjectiveRules.waveAt(tremor, PocketDungeonsConfig.claimWaveTremor())) {
            raisePack(pos);
        }
        if (!assayerOut && FloorObjectiveRules.waveAt(tremor, PocketDungeonsConfig.claimHunterTremor())) {
            assayerOut = true;
            sendAssayer();
        }
        return true;
    }

    /** Each unclaimed vein winks once a second so it reads as a thing to break. */
    @Override
    void tick(MinecraftServer server, InstanceRecord record) {
        if (fallback) {
            return;
        }
        for (BlockPos vein : unclaimed) {
            FloorObjectives.sparkle(level, vein);
        }
    }

    @Override
    String padRefusal() {
        if (fallback || claimed >= quota) {
            return null;
        }
        int left = quota - claimed;
        return "The seam holds. Break " + left + " more gilded vein" + (left == 1 ? "." : "s.");
    }

    @Override
    String progressLine() {
        if (fallback) {
            return null;
        }
        String line = "Veins " + claimed + "/" + quota;
        return FloorObjectiveRules.waveAt(tremor, PocketDungeonsConfig.claimWaveTremor())
                ? line + ", the seam is loud." : line;
    }

    /** Each vein past the quota pays one extra roll of the floor's reward table. */
    @Override
    int bonusRolls() {
        return FloorObjectiveRules.bonusRolls(claimed, quota);
    }

    /**
     * The pack a loud claim brings up: one hoglin plus one per two members,
     * risen in a ring around the spent vein and scaled the way an omen wave is
     * (keystone level, floor bonus and omen), carrying the no-drops tag. A mob
     * that fails to spawn is skipped; the claim still stands.
     */
    private void raisePack(BlockPos vein) {
        int count = 1 + Math.max(0, record.members.size()) / 2;
        for (int i = 0; i < count; i++) {
            double angle = 2.0 * Math.PI * i / Math.max(1, count);
            BlockPos at = vein.offset((int) Math.round(Math.cos(angle) * 2.0), 0,
                    (int) Math.round(Math.sin(angle) * 2.0));
            Mob mob = EntityTypes.HOGLIN.spawn(level, at, EntitySpawnReason.TRIGGERED);
            if (mob == null) {
                PocketDungeonsMod.LOG.warn("Claim wave hoglin failed to spawn at {}", at.toShortString());
                continue;
            }
            // The dungeon dimension is not piglin safe: an untagged hoglin turns zoglin in seconds.
            if (mob instanceof net.minecraft.world.entity.monster.hoglin.Hoglin hoglin) {
                hoglin.setImmuneToZombification(true);
            }
            mob.addTag(WAVE_TAG);
            mob.addTag(PocketDungeonsMod.OMEN_WAVE_TAG);
            mob.setPersistenceRequired();
            MobScaling.applyMobScale(mob, record.layout.keystoneLevel() + record.floor.levelBonus,
                    Omen.clamp(record.interval.omen), MobScaling.actOf(record));
        }
    }

    /**
     * The floor's hunter, sent once when Tremor reaches {@code claimHunterTremor}:
     * a piglin brute named {@link #ASSAYER_NAME}, scaled like the pack and then
     * given the guardian idiom's doubled health (a named mob at hardness one,
     * {@link MineGuardians#scale}), standing in the deepest cell next to the
     * terminal or at the terminal's edge when no neighbour offers a spot. It
     * does not gate the pad, and a failed spawn is the end of it.
     */
    private void sendAssayer() {
        BlockPos at = assayerSpot();
        if (at == null) {
            PocketDungeonsMod.LOG.warn("The Assayer found no spot near the terminal cell");
            return;
        }
        Mob brute = EntityTypes.PIGLIN_BRUTE.spawn(level, at, EntitySpawnReason.TRIGGERED);
        if (brute == null) {
            PocketDungeonsMod.LOG.warn("The Assayer failed to spawn at {}", at.toShortString());
            return;
        }
        // The Assayer is a hunter, not a convert: the dungeon dimension is not piglin safe.
        if (brute instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin piglin) {
            piglin.setImmuneToZombification(true);
        }
        brute.addTag(WAVE_TAG);
        brute.addTag(PocketDungeonsMod.OMEN_WAVE_TAG);
        brute.setPersistenceRequired();
        brute.setCustomName(Component.literal(ASSAYER_NAME).withStyle(ChatFormatting.GOLD));
        brute.setCustomNameVisible(true);
        MobScaling.applyMobScale(brute, record.layout.keystoneLevel() + record.floor.levelBonus,
                Omen.clamp(record.interval.omen), MobScaling.actOf(record));
        AttributeInstance health = brute.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(health.getBaseValue() * ASSAYER_HEALTH_MULT);
            brute.setHealth(brute.getMaxHealth());
        }
    }

    /**
     * Where The Assayer stands: a free spot in the cell adjacent to the
     * terminal that lies deepest on the plan (closest to the run's end), or a
     * free spot in the terminal cell itself when no neighbour offers one.
     */
    private BlockPos assayerSpot() {
        RandomSource random = level.getRandom();
        for (PlanCell cell : assayerCells()) {
            Set<BlockPos> taken = new HashSet<>();
            taken.add(record.layout.geometry().cellCentre(cell));
            BlockPos at = MineGuardians.freeSpotInCell(level,
                    record.layout.geometry().cellOrigin(cell), taken, random);
            if (at != null) {
                return at;
            }
        }
        return null;
    }

    /** The terminal cell's neighbours, deepest first, then the terminal itself as the last resort. */
    private List<PlanCell> assayerCells() {
        PlanCell terminal = terminalCell();
        List<PlanCell> neighbours = new ArrayList<>();
        if (plan != null && terminal != null) {
            for (PlanCell cell : plan.cells()) {
                if (!cell.equals(terminal) && cell.directionTo(terminal) != null) {
                    neighbours.add(cell);
                }
            }
            neighbours.sort(Comparator
                    .comparingInt((PlanCell c) -> plan.depths().getOrDefault(c, 0)).reversed()
                    .thenComparingInt(PlanCell::x).thenComparingInt(PlanCell::z));
        }
        if (terminal != null) {
            neighbours.add(terminal);
        }
        return neighbours;
    }

    /** The plan's terminal cell, matched by its origin on this record's layout. */
    private PlanCell terminalCell() {
        if (plan == null || record.layout.terminal() == null) {
            return null;
        }
        for (PlanCell cell : plan.cells()) {
            if (record.layout.geometry().cellOrigin(cell).equals(record.layout.terminal())) {
                return cell;
            }
        }
        return null;
    }
}
