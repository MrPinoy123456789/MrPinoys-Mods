package charter.config;

import static charter.Check.check;
import static charter.Check.eq;

import charter.config.SpawnPolicy.Cause;
import charter.config.SpawnPolicy.Damage;

import java.util.Arrays;
import java.util.List;

/** The spawn rules' pure decisions: who may hurt whom, border crossings, teleports, and the allowlist. */
public final class SpawnPolicyTest {

    private SpawnPolicyTest() {
    }

    public static void run() {
        SpawnRuleSet r = new SpawnRuleSet();
        check("every protective rule defaults on", r.spawnNoPvp && r.spawnInvulnerable && r.spawnNoBuild && r.spawnNoExplosions
                && r.spawnNoFire && r.spawnSealBorder && r.spawnProtectMobs && r.spawnNoHostileSpawns && r.spawnRemoveHostiles
                && r.spawnKeepInventory && r.spawnVoidRescue && r.spawnNoHunger && r.spawnNoProjectiles && r.spawnNoPearlsIn);
        check("the spawn rules are on and explosions in kingdoms off by default",
                new Knobs().spawnRulesEnabled && !new Knobs().explosionsInKingdoms);

        // Players.
        eq("a player inside is not hurt by a player outside", Damage.DENY, SpawnPolicy.damage(r, Cause.OTHER, true, true, true, false, false));
        eq("a player inside cannot hurt one outside", Damage.DENY, SpawnPolicy.damage(r, Cause.OTHER, true, false, true, true, false));
        eq("two players outside fight as usual", Damage.ALLOW, SpawnPolicy.damage(r, Cause.OTHER, true, false, true, false, false));
        eq("an operator's hit goes through", Damage.ALLOW, SpawnPolicy.damage(r, Cause.OTHER, true, true, true, true, true));
        eq("a fall inside does not hurt", Damage.DENY, SpawnPolicy.damage(r, Cause.FALL, true, true, false, false, false));
        eq("the void inside is a rescue", Damage.RESCUE, SpawnPolicy.damage(r, Cause.VOID, true, true, false, false, false));
        eq("the void outside kills", Damage.ALLOW, SpawnPolicy.damage(r, Cause.VOID, true, false, false, false, false));
        eq("/kill always works", Damage.ALLOW, SpawnPolicy.damage(r, Cause.KILL, true, true, false, false, false));

        SpawnRuleSet mortal = new SpawnRuleSet();
        mortal.spawnInvulnerable = false;
        eq("without invulnerability a fall is still spared", Damage.DENY, SpawnPolicy.damage(mortal, Cause.FALL, true, true, false, false, false));
        eq("without invulnerability a mob's hit lands", Damage.ALLOW, SpawnPolicy.damage(mortal, Cause.OTHER, true, true, false, false, false));
        eq("without invulnerability PvP is still off", Damage.DENY, SpawnPolicy.damage(mortal, Cause.OTHER, true, true, true, true, false));
        eq("without invulnerability an explosion is still off", Damage.DENY, SpawnPolicy.damage(mortal, Cause.EXPLOSION, true, true, false, false, false));
        mortal.spawnNoPvp = false;
        eq("with PvP allowed a player's hit lands", Damage.ALLOW, SpawnPolicy.damage(mortal, Cause.OTHER, true, true, true, true, false));

        // Mobs.
        eq("a player cannot hurt a mob inside", Damage.DENY, SpawnPolicy.damage(r, Cause.OTHER, false, true, true, false, false));
        eq("a player inside cannot hurt a mob outside", Damage.DENY, SpawnPolicy.damage(r, Cause.OTHER, false, false, true, true, false));
        eq("an operator may", Damage.ALLOW, SpawnPolicy.damage(r, Cause.OTHER, false, true, true, true, true));
        eq("a mob outside is fair game", Damage.ALLOW, SpawnPolicy.damage(r, Cause.OTHER, false, false, true, false, false));
        eq("an explosion spares mobs inside", Damage.DENY, SpawnPolicy.damage(r, Cause.EXPLOSION, false, true, false, false, false));
        eq("a mob's own fall inside is left to the game", Damage.ALLOW, SpawnPolicy.damage(r, Cause.FALL, false, true, false, false, false));

        // Borders and teleports.
        check("in to out crosses", SpawnPolicy.crossesBorder(true, false));
        check("out to in crosses", SpawnPolicy.crossesBorder(false, true));
        check("inside to inside does not", !SpawnPolicy.crossesBorder(true, true));
        check("a pearl from outside landing inside is refused", SpawnPolicy.refusesTeleport(r, false, true));
        check("a pearl thrown inside lands anywhere", !SpawnPolicy.refusesTeleport(r, true, true) && !SpawnPolicy.refusesTeleport(r, true, false));
        check("a pearl outside to outside is no business of spawn", !SpawnPolicy.refusesTeleport(r, false, false));

        // The allowlist.
        SpawnPolicy.UseList use = SpawnPolicy.parseUse(Arrays.asList("#minecraft:doors", " Lever ", "", "#", null, "mymod:kiosk", "#buttons"));
        eq("blocks are ids with a namespace", List.of("minecraft:lever", "mymod:kiosk"), use.blocks());
        eq("tags lose their # and gain a namespace", List.of("minecraft:doors", "minecraft:buttons"), use.tags());
        check("no list is an empty list", SpawnPolicy.parseUse(null).blocks().isEmpty());
        check("the default list has doors and no chests", r.spawnAllowedUse.contains("#minecraft:doors")
                && r.spawnAllowedUse.stream().noneMatch(s -> s.contains("chest") && !s.contains("ender")));
    }
}
