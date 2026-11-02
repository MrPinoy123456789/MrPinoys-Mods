package charter.config;

import java.util.ArrayList;
import java.util.List;

/**
 * The spawn rule decisions that need no Minecraft, so they can be tested plainly. The Fabric side
 * ({@code charter.mc.land.SpawnRules}) finds out where things are and who did them, then asks here.
 */
public final class SpawnPolicy {

    private SpawnPolicy() {
    }

    /** What happens to one hit on a living thing. */
    public enum Damage { ALLOW, DENY, RESCUE }

    /** The kind of hurt, as far as the rules care. */
    public enum Cause { VOID, KILL, FALL, EXPLOSION, OTHER }

    /**
     * Whether a hit goes through.
     *
     * @param victimPlayer   the one hurt is a player
     * @param victimInside   the one hurt stands in the area
     * @param attackerPlayer a player caused it (directly, or as the owner of an arrow, a potion or lit TNT)
     * @param attackerInside that player stands in the area
     * @param attackerOp     that player is an operator
     */
    public static Damage damage(SpawnRuleSet r, Cause cause, boolean victimPlayer, boolean victimInside,
                                boolean attackerPlayer, boolean attackerInside, boolean attackerOp) {
        if (cause == Cause.KILL) return Damage.ALLOW;
        if (cause == Cause.VOID) return victimPlayer && victimInside && r.spawnVoidRescue ? Damage.RESCUE : Damage.ALLOW;
        if (attackerPlayer && attackerOp) return Damage.ALLOW;
        if (victimPlayer) {
            if (victimInside) {
                if (r.spawnInvulnerable) return Damage.DENY;
                if (cause == Cause.FALL && r.spawnNoFallDamage) return Damage.DENY;
                if (cause == Cause.EXPLOSION && r.spawnNoExplosions) return Damage.DENY;
            }
            if (attackerPlayer && r.spawnNoPvp && (victimInside || attackerInside)) return Damage.DENY;
            return Damage.ALLOW;
        }
        if (victimInside && cause == Cause.EXPLOSION && r.spawnNoExplosions) return Damage.DENY;
        if (attackerPlayer && r.spawnProtectMobs && (victimInside || attackerInside)) return Damage.DENY;
        return Damage.ALLOW;
    }

    /** Whether something that starts at one end and ends at the other crosses the area's border (fluids, pistons, hoppers). */
    public static boolean crossesBorder(boolean fromInside, boolean toInside) {
        return fromInside != toInside;
    }

    /** Whether a pearl or chorus fruit teleport is refused: it lands inside, and its player was outside. */
    public static boolean refusesTeleport(SpawnRuleSet r, boolean playerInside, boolean landingInside) {
        return r.spawnNoPearlsIn && landingInside && !playerInside;
    }

    /** The allowlist split into block ids and tag ids (without the leading #), lower case, blanks dropped. */
    public record UseList(List<String> blocks, List<String> tags) {
    }

    public static UseList parseUse(List<String> entries) {
        List<String> blocks = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        if (entries == null) return new UseList(blocks, tags);
        for (String raw : entries) {
            if (raw == null) continue;
            String entry = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (entry.isEmpty() || entry.equals("#")) continue;
            if (entry.startsWith("#")) {
                tags.add(withNamespace(entry.substring(1)));
            } else {
                blocks.add(withNamespace(entry));
            }
        }
        return new UseList(blocks, tags);
    }

    private static String withNamespace(String id) {
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }
}
