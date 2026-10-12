package pocketdungeons;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;

import java.util.ArrayList;
import java.util.List;

/**
 * The Restless affix at work (design pass 2026-10-09, Q8; widened to every mob by the affix workshop
 * 2026-10-11): when a mob dies on a floor carrying the affix there is a chance it rises once more where it
 * fell, as the floor theme's stand-in ({@link RestlessRules#riseType}). Souls drift up and a groan sounds
 * first, two seconds before it stands, so the player sees it coming and can burn the body; a mob that died
 * to fire, or one that has already risen, stays down, and so do bosses, elites, finale and wave mobs and
 * pets. The risen mob is an ordinary mob of the floor (scaled and dressed
 * like the rest) with a tag that stops a second rise.
 */
final class Restless {

    /** Tag on a mob that has risen. */
    static final String TAG = PocketDungeonsMod.MOD_ID + ".risen";

    private record Pending(long dueTick, int slot, BlockPos pos, String typeId) {}

    private static final List<Pending> PENDING = SessionState.clearedAtStop(new ArrayList<>());

    /** Tags of mobs that never rise: bosses, elites and the mobs of a finale or capstone wave. */
    private static final List<String> NEVER_RISE_TAGS = List.of(FinaleWave.TAG, CapstoneFights.WAVE_TAG,
            CapstoneFights.WARDEN_TAG, CapstoneFights.UNSCALED_TAG, BossContent.BOSS_TAG, WitherFight.TAG,
            HerobrineFight.STEVE_TAG, HerobrineFight.ADD_TAG, HerobrineFight.ALEX_TAG,
            PocketDungeonsMod.WHELP_TAG);

    private Restless() {}

    /** Whether the mob is a boss, an elite, a finale or wave mob, or a tamed pet: none of these rise. */
    private static boolean exempt(Mob mob) {
        if (mob instanceof TamableAnimal pet && pet.isTame()) {
            return true;
        }
        for (String tag : NEVER_RISE_TAGS) {
            if (mob.entityTags().contains(tag)) {
                return true;
            }
        }
        return false;
    }

    static void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof Mob mob) || !(entity.level() instanceof ServerLevel level)
                    || !DungeonWorldRules.inDungeon(level)) {
                return;
            }
            InstanceRecord record = Instances.dungeonRecordAt(entity.blockPosition());
            if (record == null || record.layout == null) {
                return;
            }
            double chance = chanceFor(record);
            if (chance <= 0.0) {
                return;
            }
            String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
            boolean fire = source.is(DamageTypeTags.IS_FIRE) || mob.isOnFire();
            if (!RestlessRules.shouldRise(record.floor.theme, typeId, mob.entityTags().contains(TAG), exempt(mob),
                    fire, chance, level.getRandom().nextDouble())) {
                return;
            }
            String riseId = RestlessRules.riseType(record.floor.theme, typeId);
            BlockPos at = mob.blockPosition();
            level.sendParticles(ParticleTypes.SOUL, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 12, 0.3, 0.5, 0.3, 0.02);
            level.playSound(null, at, SoundEvents.SOUL_ESCAPE.value(), SoundSource.HOSTILE, 1.0f, 0.6f);
            PENDING.add(new Pending(level.getServer().getTickCount() + RestlessRules.RISE_DELAY_TICKS, record.slot, at, riseId));
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (PENDING.isEmpty()) {
                return;
            }
            ServerLevel level = server.getLevel(PocketDungeonsMod.DUNGEON_LEVEL);
            long now = server.getTickCount();
            for (Pending p : new ArrayList<>(PENDING)) {
                if (now < p.dueTick()) {
                    continue;
                }
                PENDING.remove(p);
                InstanceRecord record = InstanceRegistry.bySlot.get(p.slot());
                if (level == null || record == null || record.tearingDown) {
                    continue;
                }
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(p.typeId())).orElse(null);
                if (type == null) {
                    continue;
                }
                Entity risen = type.spawn(level, p.pos(), EntitySpawnReason.TRIGGERED);
                if (risen instanceof Mob mob) {
                    mob.setPersistenceRequired();
                    mob.addTag(TAG);
                }
            }
        });
    }

    /** The rise chance of the floor the record holds: the strongest of its affixes, the ominous figure on an ominous floor. */
    static double chanceFor(InstanceRecord record) {
        double best = 0.0;
        boolean ominous = record.floor.affixes.contains(AffixIds.OMINOUS);
        for (String id : record.floor.affixes) {
            AffixDefinition definition = AffixManifest.current().byId(id);
            if (definition == null) {
                continue;
            }
            double chance = ominous ? definition.effects.undeadRiseChanceOminous : definition.effects.undeadRiseChance;
            best = Math.max(best, chance);
        }
        return best;
    }

}
