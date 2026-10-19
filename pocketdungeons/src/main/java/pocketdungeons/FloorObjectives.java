package pocketdungeons;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The floor objective dispatcher (dungeon structure: a node's {@code
 * objective}). A floor defaults to {@link FloorObjective#SPAWNERS}, the
 * ordinary gate {@link RunLifecycle} has always run; the other kinds arm an
 * {@link ObjectiveState} on {@link FloorState#objective} at commit and this
 * class answers for it everywhere else the floor loop asks: the pad's
 * refusal, the bar's progress line, fixture protection, block use, the floor
 * title and the reward roll bonus.
 *
 * <p>A state armed with {@code fallback} true means the objective could not
 * be placed on this floor (or, for now, that its placement is not built yet):
 * the floor then runs exactly like a spawner floor, and every hook below is a
 * no-op for it. The per-kind subclasses replace the base behaviour as their
 * tasks land.
 */
final class FloorObjectives {

    private FloorObjectives() {}

    /** The floor title's one line for a heist floor (the vault the party is cracking). */
    static final String HEIST_TITLE = "The vault sleeps.";

    /** The floor title's one line for a claim floor (the seam the party is working). */
    static final String CLAIM_TITLE = "The seam holds.";

    /** The floor title's one line for a gather floor (the land the party is working). */
    static final String GATHER_TITLE = "The land provides.";

    /** The floor title's one line for a haunt objective floor. */
    static final String HAUNT_TITLE = "The keeper watches.";

    /** Test seam: pins the objective a record's node resolves to, for a floor built without dungeon data. */
    private static final Map<Integer, FloorObjective> TEST_OBJECTIVES =
            SessionState.clearedAtStop(new HashMap<>());
    /** Test seam: records the fixture scan may resolve without being in the live registry. */
    private static final Map<Integer, InstanceRecord> TEST_RECORDS =
            SessionState.clearedAtStop(new HashMap<>());

    /** Test seam: lets a gametest arm and query an objective on a record it built itself. */
    static void registerForTesting(InstanceRecord record, FloorObjective objective) {
        TEST_RECORDS.put(record.slot, record);
        TEST_OBJECTIVES.put(record.slot, objective);
    }

    /** Test seam: the end of {@link #registerForTesting}; drops the record and its override. */
    static void forgetForTesting(InstanceRecord record) {
        TEST_RECORDS.remove(record.slot);
        TEST_OBJECTIVES.remove(record.slot);
    }

    /** The objective the node's data declares for the floor the record is on; spawners when there is none. */
    static FloorObjective of(InstanceRecord record) {
        FloorObjective forced = TEST_OBJECTIVES.get(record.slot);
        if (forced != null) {
            return forced;
        }
        DungeonDef def = TripView.def(record);
        DungeonDef.Node node = def == null ? null : def.node(record.interval.nodeId);
        return FloorObjective.of(node == null ? null : node.objective());
    }

    /**
     * Whether the floor's armed objective fell back to the ordinary spawner
     * run (nothing was placed). The record has no objective at all on a
     * spawner floor, which is not a fallback.
     */
    static boolean isFallback(InstanceRecord record) {
        ObjectiveState state = record.floor.objective;
        return state != null && state.fallback;
    }

    /**
     * Arms the committed floor's objective on {@code record.floor.objective},
     * once the floor stands ({@link Instances#commitDoor}, after the new
     * floor state is on the record and the trip stands at its node). A
     * spawners floor keeps a {@code null} state. Until a kind's placement is
     * built the state arms as a fallback, so the floor behaves exactly like a
     * spawner floor end to end.
     */
    static void arm(ServerLevel level, InstanceRecord record, DungeonPlan plan, Keystone.Offer offer) {
        FloorObjective kind = of(record);
        if (kind == FloorObjective.SPAWNERS) {
            return;
        }
        if (kind == FloorObjective.HEIST) {
            record.floor.objective = HeistObjective.arm(level, record, plan);
            return;
        }
        if (kind == FloorObjective.CLAIM) {
            record.floor.objective = ClaimObjective.arm(level, record, plan);
            return;
        }
        if (kind == FloorObjective.HAUNT) {
            record.floor.objective = HauntObjective.arm(level, record, plan);
            return;
        }
        if (kind == FloorObjective.GATHER) {
            record.floor.objective = GatherObjective.arm(level, record, plan);
            return;
        }
        if (kind == FloorObjective.RELIC) {
            record.floor.objective = RelicObjective.arm(level, record, plan);
            return;
        }
        record.floor.objective = new ObjectiveState(kind, true);
    }

    /**
     * Whether the floor's own objective replaces the spawner gate at the pad: false for a spawner floor, a
     * fallback, and for the relic, which is greed and never the gate (the spawner gate stays as it is).
     */
    static boolean gatesPad(InstanceRecord record) {
        if (of(record) == FloorObjective.SPAWNERS || isFallback(record)) {
            return false;
        }
        ObjectiveState state = record.floor.objective;
        return state == null || state.gatesPad();
    }

    /**
     * The pad's refusal line for a live objective floor, or {@code null} to
     * let the pad through the objective check. Consulted only for a
     * non-spawners, non-fallback floor; the base state never refuses.
     */
    static String padRefusal(InstanceRecord record) {
        ObjectiveState state = record.floor.objective;
        return state == null ? null : state.padRefusal();
    }

    /**
     * The progress line the omen bar shows in place of the spawner fraction
     * on an objective floor ({@code "Veins 2/3"}), or {@code null} when the
     * floor has no line of its own.
     */
    static String objectiveLine(InstanceRecord record) {
        ObjectiveState state = record.floor.objective;
        return state == null ? null : state.progressLine();
    }

    /** Extra reward rolls a cleared objective floor earned, on top of the chest count; 0 on any other floor. */
    static int bonusRolls(InstanceRecord record) {
        ObjectiveState state = record.floor.objective;
        return state == null ? 0 : state.bonusRolls();
    }

    /**
     * The extra rolls a cleared floor earned, given what the floor pays before objectives: a relic carried
     * to the pad raises the Loot percent, which only the base chests and that percent can price.
     */
    static int bonusRolls(InstanceRecord record, int baseChests, int lootPercent) {
        ObjectiveState state = record.floor.objective;
        return state == null ? 0 : state.bonusRolls(record, baseChests, lootPercent);
    }

    /**
     * A use (right-click) on a block an armed objective claims, anywhere in
     * the dungeon dimension. True when the click was the objective's own.
     */
    static boolean onUse(ServerPlayer player, BlockPos pos) {
        ObjectiveState state = stateAt(pos);
        return state != null && state.onUse(player, pos);
    }

    /**
     * A left-click on one of an armed objective's protected blocks, so a hit can do the thing a
     * click does: the vault core answers a swing the same as a use. Fixtures stay unbreakable;
     * this is feedback, not a break.
     */
    static boolean onAttack(ServerPlayer player, BlockPos pos) {
        ObjectiveState state = stateAt(pos);
        return state != null && state.onAttack(player, pos);
    }

    /**
     * Whether breaking {@code pos} is an armed objective's verb (a claim's vein): the break rule
     * lets it go under any tool, fists included, and {@link #onBreak} credits it. These blocks are
     * not fixtures, so they are found by their own scan of every live instance.
     */
    static boolean isBreakClaim(BlockPos pos) {
        for (InstanceRecord record : InstanceRegistry.bySlot.values()) {
            if (claimsBreak(record, pos)) {
                return true;
            }
        }
        for (InstanceRecord record : TEST_RECORDS.values()) {
            if (claimsBreak(record, pos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean claimsBreak(InstanceRecord record, BlockPos pos) {
        ObjectiveState state = record.floor == null ? null : record.floor.objective;
        return state != null && state.breakClaims(pos);
    }

    /** A break landing on {@code pos} inside {@code record}'s floor, for the objective to credit. */
    static boolean onBreak(InstanceRecord record, ServerPlayer player, BlockPos pos) {
        ObjectiveState state = record.floor == null ? null : record.floor.objective;
        return state != null && state.onBreak(player, pos);
    }

    /** The objective's once-a-second wink over a block that still needs doing. */
    static void sparkle(ServerLevel level, BlockPos pos) {
        level.sendParticles(ParticleTypes.WAX_ON, pos.getX() + 0.5, pos.getY() + 0.8,
                pos.getZ() + 0.5, 2, 0.3, 0.3, 0.3, 0.0);
    }

    /**
     * Whether {@code pos} is a block an armed objective protects (the same
     * protection an Ordeal's lever gets in {@link RoomProtection}): scanned
     * across every live instance, since a block's own floor answers.
     */
    static boolean isFixture(BlockPos pos) {
        return stateAt(pos) != null;
    }

    /**
     * The line the floor title adds on an objective floor, the way
     * {@link Haunts#titleLine} speaks for a haunted one; {@code null} on a
     * spawner or fallback floor, which reads like an ordinary floor.
     */
    static String titleLine(InstanceRecord record) {
        if (isFallback(record)) {
            return null;
        }
        return switch (of(record)) {
            case HEIST -> HEIST_TITLE;
            case CLAIM -> CLAIM_TITLE;
            case HAUNT -> HAUNT_TITLE;
            case GATHER -> GATHER_TITLE;
            case RELIC -> RelicObjective.TITLE;
            case SPAWNERS -> null;
        };
    }

    /**
     * One watch tick of the floor's armed objective, once a second from the
     * same loop that runs {@link Haunts#tick}: the heist's vault channel and
     * its ominous echo wave. A no-op on every floor without an armed state.
     */
    static void tick(MinecraftServer server, InstanceRecord record) {
        ObjectiveState state = record.floor.objective;
        if (state != null) {
            state.tick(server, record);
        }
    }

    /**
     * The objective-level listeners: a hit on a member cracking the vault
     * breaks the channel, which the heist's state keeps and resets.
     */
    static void register() {
        // The relic's stale-copy sweep: once a second, offset from the other watchers.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 == 13) {
                RelicObjective.sweep(server);
            }
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (!(entity instanceof ServerPlayer player) || blocked) {
                return;
            }
            InstanceRecord record = InstanceRegistry.byMember.get(player.getUUID());
            ObjectiveState state = record == null ? null : record.floor.objective;
            if (state != null) {
                state.channelerHurt(player);
            }
        });
    }

    /** The armed objective state claiming {@code pos} as a fixture, or {@code null}. */
    private static ObjectiveState stateAt(BlockPos pos) {
        for (InstanceRecord record : InstanceRegistry.bySlot.values()) {
            ObjectiveState state = stateOf(record, pos);
            if (state != null) {
                return state;
            }
        }
        for (InstanceRecord record : TEST_RECORDS.values()) {
            ObjectiveState state = stateOf(record, pos);
            if (state != null) {
                return state;
            }
        }
        return null;
    }

    private static ObjectiveState stateOf(InstanceRecord record, BlockPos pos) {
        ObjectiveState state = record.floor == null ? null : record.floor.objective;
        return state != null && state.fixtureAt(pos) != null ? state : null;
    }

    /**
     * One armed floor objective: its kind, the positions it protects
     * ({@link #fixtures}), and whether it fell back to the ordinary spawner
     * run ({@link #fallback}, set when the objective could not be placed).
     * The base is inert: no refusals, no progress line, no bonus rolls, no
     * uses. Each kind extends this with its own placements and rules.
     */
    static class ObjectiveState {

        final FloorObjective kind;

        /** The block positions this objective protects and answers use for. */
        final Set<BlockPos> fixtures = new HashSet<>();

        /** True when the objective was not placed and the floor runs as a plain spawner floor. */
        boolean fallback;

        ObjectiveState(FloorObjective kind, boolean fallback) {
            this.kind = kind;
            this.fallback = fallback;
        }

        /** What this objective claims at {@code pos}, or {@code null}; the base claims nothing. */
        Object fixtureAt(BlockPos pos) {
            return fixtures.contains(pos) ? this : null;
        }

        /** The pad refusal line while the objective is unfinished, or {@code null}. */
        String padRefusal() {
            return null;
        }

        /** The bar's progress line ({@code "Veins 2/3"}), or {@code null}. */
        String progressLine() {
            return null;
        }

        /** Extra reward rolls the cleared floor earned; the base earns none. */
        int bonusRolls() {
            return 0;
        }

        /**
         * Extra rolls that depend on what the floor pays before objectives ({@code baseChests} at {@code lootPercent}),
         * asked once as the floor's rewards are placed; the base earns none.
         */
        int bonusRolls(InstanceRecord record, int baseChests, int lootPercent) {
            return 0;
        }

        /** Whether this objective's {@link #padRefusal} replaces the spawner gate; the relic alone says no. */
        boolean gatesPad() {
            return true;
        }

        /** A right-click on one of this objective's blocks; the base claims nothing. */
        boolean onUse(ServerPlayer player, BlockPos pos) {
            return false;
        }

        /** A left-click on one of this objective's blocks; the base ignores it. */
        boolean onAttack(ServerPlayer player, BlockPos pos) {
            return false;
        }

        /** Whether breaking this block is the objective's verb (a claim vein); the base is none. */
        boolean breakClaims(BlockPos pos) {
            return false;
        }

        /** A break on a {@link #breakClaims} block; the base credits none. */
        boolean onBreak(ServerPlayer player, BlockPos pos) {
            return false;
        }

        /** One watch tick of this objective (the heist's channel and echo wave); the base has none. */
        void tick(MinecraftServer server, InstanceRecord record) {}

        /** A member took damage; the base objective holds no channel to break. */
        void channelerHurt(ServerPlayer player) {}
    }
}
