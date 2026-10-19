package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The guardian finale of every generated Chart Room dungeon (campaign endgame, 2026-10-18; spec T5, step 6). The
 * final floor ends the way a dungeon's final floor does (the spawners clear, the title, the countdown), and then
 * the wave is a guardian: the Endless Mine's guardian for the dungeon's final act and the door's slot
 * ({@link MineGuardians}), spawned in the terminal cell at hardness {@code EndlessMineRules.hardness(slot)} and
 * scaled to the floor's level, run under a {@link BossFight} so it has phases. The guardian's mobs are adopted into
 * the finale, so the pad stays shut until all of them are down; its boss bar leads with the strongest.
 *
 * <p>Registered through {@link FinaleScripts#forDungeon} for every id {@link UnchartedGen} makes (one script object
 * serves every slot, so its state is in the {@link FinaleContext}). If the guardian cannot spawn at all the
 * dungeon's data wave ({@link UnchartedGen#finaleFor}) runs instead, so a finale can never fail to end.
 */
final class UnchartedFinale implements FinaleScript {

    static final UnchartedFinale INSTANCE = new UnchartedFinale();

    /** The guardian's phases: quiet, then it calls its guard, then it is enraged. Bounds are health fractions. */
    private static final List<BossFight.Step> PHASES = List.of(
            new BossFight.Step(1.0, List.of(), (ctx, boss) -> {
            }),
            new BossFight.Step(2.0 / 3.0,
                    List.of("The guardian staggers.", "Its guard comes."),
                    (ctx, boss) -> callGuard(ctx)),
            new BossFight.Step(1.0 / 3.0,
                    List.of("The guardian is enraged.", "End it."),
                    (ctx, boss) -> enrage(boss)));

    /** Adds the second phase calls: two, and one more for each member past the first. */
    static final int GUARD_BASE = 2;

    /** Seconds of Strength and Speed the third phase grants, long enough to outlast the fight. */
    private static final int ENRAGE_TICKS = 20 * 600;

    private UnchartedFinale() {}

    /** What one run keeps between ticks. */
    static final class State {
        BossFight fight = new BossFight(PHASES);
        /** The guardian mobs already adopted into the finale. */
        final Set<UUID> adopted = new HashSet<>();
        boolean spawned;
    }

    private static State state(FinaleContext ctx) {
        return ctx.scriptState(State.class, State::new);
    }

    @Override
    public List<String> triggerLines(FinaleContext ctx) {
        return List.of("The guardian wakes.", "Hold the room.");
    }

    @Override
    public boolean spawnWave(FinaleContext ctx) {
        return spawnGuardian(ctx);
    }

    @Override
    public void onTick(FinaleContext ctx) {
        State state = state(ctx);
        adoptNew(ctx, state);
        state.fight.tick(ctx);
    }

    // ---- the guardian -----------------------------------------------------------------------------

    /**
     * Spawns the guardian in the terminal cell and adopts its mobs. Returns whether at least one mob stands, so
     * the data wave covers a guardian that did not spawn. Package private for the gametest.
     */
    static boolean spawnGuardian(FinaleContext ctx) {
        InstanceRecord record = ctx.record();
        if (record.layout == null) {
            return false;
        }
        State state = state(ctx);
        DungeonDef def = TripView.def(record);
        ServerLevel level = ctx.level();
        BlockPos terminal = record.layout.terminal();
        int act = guardianAct(def);
        int number = UnchartedGen.guardianSlot(record.interval.dungeonId);
        int slot = EndlessMineRules.slot(number);
        BlockPos centre = terminal.offset(RoomGeometry.CELL / 2, 1, RoomGeometry.CELL / 2);
        MineGuardians.Spawn spawn = new MineGuardians.Spawn(level, terminal.immutable(), centre, act, number, slot,
                EndlessMineRules.lap(number), EndlessMineRules.hardness(number), Math.max(1, record.floor.chosenLevel));
        MineGuardians.Guardian guardian = MineGuardians.registered(act, slot);
        if (guardian == null) {
            guardian = MineGuardians::placeholder;
        }
        try {
            guardian.spawn(spawn);
        } catch (RuntimeException e) {
            PocketDungeonsMod.LOG.error("The uncharted guardian (act {}, slot {}) threw while spawning", act, slot, e);
        }
        adoptNew(ctx, state);
        state.spawned = !state.adopted.isEmpty();
        if (state.spawned) {
            crown(ctx, level, terminal, state);
        }
        return state.spawned;
    }

    /** The act whose guardians the final floor draws from: the final floor's theme's act, else the dungeon's. */
    static int guardianAct(DungeonDef def) {
        if (def == null) {
            return DungeonDef.MIN_ACT;
        }
        DungeonDef.Node last = def.finals().isEmpty() ? null : def.finals().get(0);
        String theme = last != null && last.overridesTheme() ? last.theme() : def.mainTheme();
        int act = DungeonDefs.actOfTheme(DungeonDefs.current().all()).getOrDefault(DungeonDef.qualify(theme), def.act());
        return Math.max(DungeonDef.MIN_ACT, Math.min(DungeonDef.MAX_ACT, act));
    }

    /** Every guardian mob of this terminal cell not yet in the finale joins it (a guardian may add mobs later). */
    private static void adoptNew(FinaleContext ctx, State state) {
        if (ctx.record().layout == null) {
            return;
        }
        BlockPos terminal = ctx.record().layout.terminal();
        AABB bounds = CellGeometry.cellBounds(terminal).inflate(RoomGeometry.CELL * 2.0);
        for (Mob mob : ctx.level().getEntitiesOfClass(Mob.class, bounds)) {
            if (mob.isAlive() && mob.entityTags().contains(MineGuardians.GUARDIAN_TAG)
                    && MineGuardians.scopedTo(mob, terminal) && state.adopted.add(mob.getUUID())) {
                ctx.addMob(mob);
            }
        }
    }

    /** Makes the strongest guardian mob the finale's elite: the boss bar and the {@link BossFight} follow it. */
    private static void crown(FinaleContext ctx, ServerLevel level, BlockPos terminal, State state) {
        Mob best = null;
        for (UUID id : state.adopted) {
            if (level.getEntity(id) instanceof Mob mob && mob.isAlive()
                    && (best == null || mob.getMaxHealth() > best.getMaxHealth())) {
                best = mob;
            }
        }
        if (best == null || ctx.members().isEmpty()) {
            return;
        }
        // The elite's health is the mob's own base value: the guardian's scale is a modifier on top of it, and
        // the party share is added by the finale (FinaleRules.eliteHealth).
        var health = best.getAttribute(Attributes.MAX_HEALTH);
        int base = health == null ? 40 : (int) Math.max(1, Math.round(health.getBaseValue()));
        String name = best.getCustomName() == null ? "Guardian" : best.getCustomName().getString();
        String type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(best.getType()).toString();
        ctx.adoptElite(best, new DungeonDef.Elite(type, name, base, ""));
    }

    // ---- the phases -------------------------------------------------------------------------------

    /** The second phase: the guard arrives, two adds and one more per member past the first. */
    private static void callGuard(FinaleContext ctx) {
        DungeonDef def = TripView.def(ctx.record());
        String melee = def != null && def.act() >= 2 ? "minecraft:husk" : "minecraft:zombie";
        int count = GUARD_BASE + Math.max(0, ctx.party() - 1);
        List<Mob> added = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Mob mob = ctx.spawnNearParty(i % 2 == 0 ? melee : "minecraft:skeleton");
            if (mob != null) {
                added.add(mob);
            }
        }
        if (!added.isEmpty()) {
            ctx.tell("The guardian's guard arrives.", ChatFormatting.RED);
        }
    }

    /** The third phase: the guardian hits harder and moves faster for the rest of the fight. */
    private static void enrage(Mob boss) {
        boss.addEffect(new MobEffectInstance(MobEffects.STRENGTH, ENRAGE_TICKS, 0, false, true));
        boss.addEffect(new MobEffectInstance(MobEffects.SPEED, ENRAGE_TICKS, 0, false, true));
    }

    /** Test seam: the phases, so a gametest can read their bounds. */
    static List<BossFight.Step> phases() {
        return PHASES;
    }
}
