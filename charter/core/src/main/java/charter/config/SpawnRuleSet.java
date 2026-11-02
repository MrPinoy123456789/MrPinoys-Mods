package charter.config;

import java.util.ArrayList;
import java.util.List;

/**
 * The spawn rules: what is switched off on the land of an admin kingdom, the server's spawn town. The {@code spawnRules}
 * section of {@code charter.json}. Each rule applies only inside an admin kingdom's claims and only to players who are
 * not operators; operators are exempt from all of them so they can build and fix things. {@link Knobs#spawnRulesEnabled}
 * switches the whole set off.
 *
 * <p>Every "no" rule defaults to true, the protective value. The few "allow" rules are movement and conveniences that harm
 * nobody, so they default to allowed. A key missing from the file keeps the default written here.
 */
public final class SpawnRuleSet {

    /** Gson builds this section with this constructor, so the defaults below hold for any key the file leaves out. */
    public SpawnRuleSet() {
    }

    // ---- players ----
    /** No player hurts another player (melee, arrows, tridents, potions, fire aspect, explosions they set off) in or out of the area. */
    public boolean spawnNoPvp = true;
    /** Players inside take no damage at all (falls, fire, lava, drowning, suffocation, cactus, wither, starving); the void excepted. */
    public boolean spawnInvulnerable = true;
    /** No fall damage inside, also when {@link #spawnInvulnerable} is off. */
    public boolean spawnNoFallDamage = true;
    /** Hunger does not go down inside: food is topped up while a player stands there. */
    public boolean spawnNoHunger = true;
    /** A player who dies inside keeps their items and experience. */
    public boolean spawnKeepInventory = true;
    /** A player falling out of the world under the area is put back on its Nexus instead of dying. */
    public boolean spawnVoidRescue = true;
    /** Splash and lingering potions, tipped arrows and other harmful effects from other players do not land on players inside. */
    public boolean spawnNoHarmfulEffects = true;
    /** Ender pearls and chorus fruit cannot carry a player into the area from outside it. */
    public boolean spawnNoPearlsIn = true;
    /** Bows, crossbows, tridents, snowballs, eggs, fishing rods, wind charges and splash potions cannot be used inside. */
    public boolean spawnNoProjectiles = true;

    // ---- mobs ----
    /** Players cannot hurt any mob inside (animals, villagers, golems, pets, hostile mobs), nor hit mobs from inside. */
    public boolean spawnProtectMobs = true;
    /** No hostile mob is added inside: natural spawns, spawners, patrols, raids, phantoms. */
    public boolean spawnNoHostileSpawns = true;
    /** Hostile mobs that wander in, or that were already there, are removed. Named ones are left alone. */
    public boolean spawnRemoveHostiles = true;
    /** Sheep do not eat grass, rabbits do not raid carrots, villagers do not harvest crops inside. */
    public boolean spawnNoMobGriefing = true;

    // ---- blocks and world ----
    /** No breaking, placing or using blocks and entities inside, members of the admin kingdom included; see {@link #spawnAllowedUse}. */
    public boolean spawnNoBuild = true;
    /** No explosion breaks blocks inside or hurts anything standing there. */
    public boolean spawnNoExplosions = true;
    /** No fire spreads, burns or is lit inside (fire spread, lava, lightning). */
    public boolean spawnNoFire = true;
    /** Water and lava, pistons, dispensers and hoppers do not cross the border in either direction. */
    public boolean spawnSealBorder = true;
    /** The ground does not change by itself: no crop, sapling or grass growth, leaf decay, ice or snow melting, farmland drying. */
    public boolean spawnFreezeBlocks = true;
    /** Snow does not pile up and water does not freeze from the weather. */
    public boolean spawnNoSnowAndIce = true;
    /** Lightning does not strike inside: no fire, no charged creepers, no witches from villagers. */
    public boolean spawnNoLightning = true;
    /** A nether portal is never built inside by someone arriving from the Nether. Existing portals still work. */
    public boolean spawnNoPortalCreation = true;
    /** Blocks hit by a player's projectile do not react (TNT lit by flame arrows, pots broken, targets, chorus flowers). */
    public boolean spawnNoProjectileBlockHits = true;

    // ---- allowed ----
    /** Fireworks may boost an elytra flight inside. */
    public boolean spawnAllowElytraBoost = true;
    /** A Riptide trident may launch its holder inside. */
    public boolean spawnAllowRiptide = true;
    /** Players may drop items inside. Off: a drop goes back into the inventory. */
    public boolean spawnAllowItemDrop = true;
    /** Players may trade with villagers and wandering traders inside. */
    public boolean spawnAllowTrading = true;
    /** Players may ride boats, plain minecarts and horses inside (with an empty hand, so nothing is fed or equipped). */
    public boolean spawnAllowRiding = true;
    /** Entering the area says it is the spawn and what is off there, instead of the usual kingdom line. */
    public boolean spawnEntryNotice = true;
    /**
     * Blocks anyone may use inside, by id ({@code minecraft:lever}) or tag ({@code #minecraft:doors}). Everything else is
     * refused. A waxed sign may always be read and its click commands run; an unwaxed one could be edited, so it is refused.
     */
    public List<String> spawnAllowedUse = new ArrayList<>(List.of(
            "#minecraft:doors", "#minecraft:trapdoors", "#minecraft:fence_gates", "#minecraft:buttons",
            "minecraft:lever", "minecraft:bell", "minecraft:crafting_table", "minecraft:enchanting_table",
            "minecraft:ender_chest", "minecraft:stonecutter", "minecraft:smithing_table", "minecraft:loom",
            "minecraft:cartography_table", "minecraft:grindstone"));
}
