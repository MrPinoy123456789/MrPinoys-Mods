package pocketdungeons;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.cow.CowSoundVariant;
import net.minecraft.world.entity.animal.cow.CowSoundVariants;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import pocketdungeons.mixin.MobGoalsAccessor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The Cow Pits' optional last stand (roadmap T30, the "Specimen 0" beat). The dungeon has no data
 * finale: a party that reads the sign and leaves Specimen 0 alone walks out with the floor's haul
 * and nothing else. Feed the pedestal cow any food (wheat is refused first, as it is everywhere in
 * the Pits) and the mark {@link CowPits#fedOn} is set; the next watch tick wakes this script.
 *
 * <p>The fight: a moo and the title ("Specimen 0 wakes."), the countdown every finale gives, then
 * the pedestal cow grows to {@code cowSpecimenZeroScale} and becomes the elite under a boss bar
 * while every tagged cow on the floor (bounded by {@link CowPits#FLOOR_CAP}) turns on the party.
 * The pad stays shut until they are all dead; winning pays the finale reward chest a data finale
 * would have paid.
 *
 * <p>Phased on the {@link BossFight} spine (backlog F2): phase one is Specimen 0 alone, the herd
 * grazing; at two thirds the herd charges with it (the stagger is the tell); at one third every cow
 * of the herd scales to {@link #HERD_SCALE}. A Specimen 0 that dies early turns the rest of the herd on
 * the party at once, so a placid cow never has to be hunted down to open the pad.
 */
final class CowPitsFinale implements FinaleScript {

    /** The title line the party sees when the fight wakes. */
    static final String TELL_TITLE = "Specimen 0 wakes.";
    /** The elite the pedestal cow becomes: a named cow whose health is the {@code cowSpecimenZeroHealth} knob. */
    private static final String ELITE_NAME = "Specimen 0";
    /** The scale the herd grows to in the last phase (Specimen 0 keeps its own knob). */
    static final double HERD_SCALE = 1.5;

    /** The three phases: Specimen 0 alone, the herd charges, the herd scales. Bounds are elite health. */
    private static final List<BossFight.Step> PHASES = List.of(
            new BossFight.Step(1.0, List.of(), (ctx, boss) -> {
            }),
            new BossFight.Step(2.0 / 3.0,
                    List.of("Specimen 0 staggers.", "The herd charges with it."),
                    (ctx, boss) -> angerHerd(ctx)),
            new BossFight.Step(1.0 / 3.0,
                    List.of("Specimen 0 bellows.", "The herd swells."),
                    (ctx, boss) -> scaleHerd(ctx)));

    /** One run's herd bookkeeping: who already charges, so a goal is never added twice. */
    static final class Herd {
        final Set<UUID> angry = new HashSet<>();
    }

    private static BossFight fight(FinaleContext ctx) {
        return ctx.scriptState(BossFight.class, () -> new BossFight(PHASES));
    }

    /**
     * The cleared-spawner trigger does not apply here: the barn's spawners open nothing. Only a
     * party that feeds Specimen 0 wakes the fight.
     */
    @Override
    public boolean ownsTrigger() {
        return true;
    }

    @Override
    public boolean shouldTrigger(FinaleContext ctx) {
        return CowPits.fedOn(ctx.record());
    }

    @Override
    public List<String> triggerLines(FinaleContext ctx) {
        return List.of(TELL_TITLE, "The herd turns.");
    }

    @Override
    public void onTriggered(FinaleContext ctx) {
        if (!ctx.members().isEmpty()) {
            ServerPlayer member = ctx.members().get(0);
            CowSoundVariant sounds = SoundEvents.COW_SOUNDS.get(CowSoundVariants.SoundSet.CLASSIC);
            if (sounds != null) {
                ctx.level().playSound(null, member.blockPosition(), sounds.ambientSound().value(),
                        SoundSource.HOSTILE, 4.0f, 0.4f);
            }
        }
    }

    @Override
    public boolean spawnWave(FinaleContext ctx) {
        Mob zero = specimenZero(ctx);
        if (zero == null) {
            zero = ctx.spawnNearParty("minecraft:cow");
        }
        if (zero != null) {
            AttributeInstance scale = zero.getAttribute(Attributes.SCALE);
            if (scale != null) {
                scale.setBaseValue(PocketDungeonsConfig.cowSpecimenZeroScale());
                zero.refreshDimensions();
            }
            ctx.adoptElite(zero, new DungeonDef.Elite("minecraft:cow", ELITE_NAME,
                    PocketDungeonsConfig.cowSpecimenZeroHealth(), ""));
        }
        if (zero instanceof PathfinderMob angry) {
            anger(ctx, angry);
        }
        int added = 0;
        for (Cow cow : herd(ctx)) {
            if (cow == zero || added >= CowPits.FLOOR_CAP - 1) {
                continue;
            }
            ctx.addMob(cow);
            added++;
        }
        return true;
    }

    @Override
    public void onTick(FinaleContext ctx) {
        fight(ctx).tick(ctx);
        Mob boss = ctx.elite();
        if (boss == null || !boss.isAlive()) {
            angerHerd(ctx);
        }
    }

    @Override
    public void onFloorEnded(FinaleContext ctx) {
        CowPits.forgetFed(ctx.record().slot);
    }

    /** The living pedestal cow on this floor, or {@code null} if it died or left the bounds. */
    private static Mob specimenZero(FinaleContext ctx) {
        for (Cow cow : herd(ctx)) {
            if (CowPits.isSpecimenZero(cow)) {
                return cow;
            }
        }
        return null;
    }

    /** Every tagged cow still on the floor, padded a little past the layout for wanderers. */
    private static List<Cow> herd(FinaleContext ctx) {
        AABB bounds = ctx.record().layout.bounds().inflate(8.0);
        return ctx.level().getEntitiesOfClass(Cow.class, bounds,
                cow -> cow.entityTags().contains(CowPits.TAG));
    }

    /** Every living herd cow charges the party (once each). */
    private static void angerHerd(FinaleContext ctx) {
        for (Cow cow : herd(ctx)) {
            if (!CowPits.isSpecimenZero(cow)) {
                anger(ctx, cow);
            }
        }
    }

    /** Every living herd cow, Specimen 0 aside, grows to {@link #HERD_SCALE}. */
    private static void scaleHerd(FinaleContext ctx) {
        for (Cow cow : herd(ctx)) {
            AttributeInstance scale = cow.getAttribute(Attributes.SCALE);
            if (scale != null && !CowPits.isSpecimenZero(cow)) {
                scale.setBaseValue(HERD_SCALE);
                cow.refreshDimensions();
            }
        }
    }

    /** Turns a placid cow on the party: melee, and it picks the nearest player it can see. */
    private static void anger(FinaleContext ctx, PathfinderMob cow) {
        if (!ctx.scriptState(Herd.class, Herd::new).angry.add(cow.getUUID())) {
            return;
        }
        ((MobGoalsAccessor) cow).pocketdungeons$goalSelector().addGoal(0, new MeleeAttackGoal(cow, 1.2, true));
        ((MobGoalsAccessor) cow).pocketdungeons$targetSelector().addGoal(0,
                new NearestAttackableTargetGoal<>(cow, Player.class, true));
    }
}
