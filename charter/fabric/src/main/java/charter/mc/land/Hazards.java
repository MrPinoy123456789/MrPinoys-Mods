package charter.mc.land;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonStructureResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * The questions the world-changing Mixins ask (PLAN.md §5.6, Mixins 2 to 7, 9 and
 * 10). A Mixin package may hold only Mixins, so the logic lives here and each Mixin
 * is a few lines that call it. Everything goes through {@link Protection}.
 */
public final class Hazards {

    /** The fire being ticked, on the one server thread, so a burn-out knows where it burns from. */
    public static BlockPos burning;

    private Hazards() {
    }

    /**
     * Positions an explosion may break or set alight (the same list feeds the fire and a wind charge's door and button
     * toggling): the mod's objects are spared, every claim is spared while {@code explosionsInKingdoms} is off (protected
     * or not, the owner's own TNT included), the spawn is spared, and protected land is spared unless a permitted player
     * lit it. Entity damage is decided elsewhere.
     */
    public static List<BlockPos> explodable(ServerLevel level, Entity source, List<BlockPos> positions) {
        if (positions.isEmpty()) return positions;
        Entity owner = source instanceof TraceableEntity t ? t.getOwner() : null;
        if (owner == null && source instanceof net.minecraft.world.entity.player.Player) owner = source;
        boolean claimsSafe = charter.mc.CharterServer.running() && !charter.mc.CharterServer.runtime().knobs.explosionsInKingdoms;
        List<BlockPos> kept = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            if (Protection.fixtureAt(level, pos) != null) continue;
            if (claimsSafe && Protection.claimed(level, pos)) continue;
            if (SpawnRules.sparesFromExplosion(level, pos, owner)) continue;
            if (Protection.guarded(level, pos) && !(owner != null && Protection.mayAct(owner, level, pos))) continue;
            kept.add(pos);
        }
        return kept;
    }

    /** Whether a piston may move: nothing it pushes, destroys or pushes into may sit in another owner's protected land. */
    public static boolean pistonMayMove(Level level, BlockPos piston, Direction facing, boolean extending) {
        PistonStructureResolver resolver = new PistonStructureResolver(level, piston, facing, extending);
        if (!resolver.resolve()) return true;
        Direction push = resolver.getPushDirection();
        List<BlockPos> touched = new ArrayList<>(resolver.getToPush());
        touched.addAll(resolver.getToDestroy());
        for (BlockPos pos : resolver.getToPush()) touched.add(pos.relative(push));
        // The spawn's border holds the head itself too, so a piston outside cannot shove visitors standing inside.
        if (extending && SpawnRules.crossesBorder(level, piston, piston.relative(facing))) return false;
        for (BlockPos pos : touched) {
            if (Protection.fixtureAt(level, pos) != null) return false;
            if (Protection.crossesIntoGuarded(level, piston, pos)) return false;
        }
        return true;
    }

    /** Whether a vehicle may be hurt: in protected land only a permitted player can do it (melee, arrow or trident). */
    public static boolean vehicleMayBeHurt(Level level, BlockPos pos, DamageSource source, Entity vehicle) {
        if (SpawnRules.sparesThing(vehicle, source)) return false;
        if (!Protection.guarded(level, pos)) return true;
        Entity cause = source.getEntity();
        if (cause == null) return true;
        return Protection.mayAct(cause, level, pos);
    }

    /** Whether an explosion may hurt this entity: not in the spawn, unless an operator set it off. */
    public static boolean explosionMayHurt(Entity entity, Entity source) {
        return !SpawnRules.sparesEntityFromExplosion(entity, source);
    }

    /** Whether an entity landing on farmland may trample it. */
    public static boolean mayTrample(Level level, BlockPos pos, Entity entity) {
        return Protection.mayAct(entity, level, pos);
    }
}
