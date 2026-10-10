package pocketdungeons;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.phys.Vec3;

/**
 * The Warden whelp (design pass 2026-10-09): what a sculk room sends when it answers. A small, fast,
 * melee-only Warden that always knows where the party is, drops nothing, is gone after
 * {@code whelpSeconds}, and is the only one active in an instance at a time. While it is out the room's
 * Heard meter cannot rise ({@link PressureSources}). It is not the Ancient City's Warden, which stays the
 * only full one the mod spawns.
 */
final class Whelp {

    /** Marks a whelp: {@code SonicBoomWhelpMixin} keeps it from firing. */
    static final String TAG = PocketDungeonsMod.MOD_ID + ".whelp";
    /** The no-loot tag {@code OmenWaveNoDropsMixin} already honours. */
    private static final String NO_LOOT_TAG = PocketDungeonsMod.OMEN_WAVE_TAG;
    /** Half a Warden's size. */
    private static final double SCALE = 0.5;

    private static final Map<Integer, UUID> ACTIVE = new HashMap<>();

    private Whelp() {}

    /** Whether this instance already has a whelp out. Forgets one that has died or been removed. */
    static boolean active(ServerLevel level, InstanceRecord record) {
        UUID id = ACTIVE.get(record.slot);
        if (id == null) {
            return false;
        }
        Entity entity = level.getEntity(id);
        if (entity instanceof Warden warden && warden.isAlive()) {
            return true;
        }
        ACTIVE.remove(record.slot);
        return false;
    }

    /** Sends a whelp at {@code target}; whether one came. Nothing happens if one is already out. */
    static boolean spawn(ServerLevel level, InstanceRecord record, ServerPlayer target) {
        if (active(level, record)) {
            return false;
        }
        Vec3 at = Instances.randomSpawnNear(target, level, new Random(level.getServer().getTickCount()), 5, 10);
        if (at == null) {
            return false;
        }
        Warden whelp = create(level, at, target);
        if (whelp == null) {
            return false;
        }
        ACTIVE.put(record.slot, whelp.getUUID());
        return true;
    }

    /** A whelp standing at {@code at}, angry at {@code target}; {@code null} if one could not be made. */
    static Warden create(ServerLevel level, Vec3 at, net.minecraft.world.entity.LivingEntity target) {
        // COMMAND, not TRIGGERED: a triggered Warden spends seconds climbing out of the floor.
        Warden whelp = EntityTypes.WARDEN.create(level, EntitySpawnReason.COMMAND);
        if (whelp == null) {
            return null;
        }
        whelp.setPos(at.x, at.y, at.z);
        whelp.getAttribute(Attributes.SCALE).setBaseValue(SCALE);
        whelp.getAttribute(Attributes.MAX_HEALTH).setBaseValue(PocketDungeonsConfig.whelpHealth());
        whelp.setHealth((float) PocketDungeonsConfig.whelpHealth());
        whelp.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(PocketDungeonsConfig.whelpDamage());
        whelp.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(PocketDungeonsConfig.whelpSpeed());
        whelp.addTag(TAG);
        whelp.addTag(NO_LOOT_TAG);
        whelp.setPersistenceRequired();
        // A Warden finalised the vanilla way starts with a dig cooldown; without it the brain digs in at once.
        whelp.getBrain().setMemoryWithExpiry(MemoryModuleType.DIG_COOLDOWN, net.minecraft.util.Unit.INSTANCE,
                PocketDungeonsConfig.whelpSeconds() * 20L + 200L);
        level.addFreshEntity(whelp);
        point(whelp, target);
        return whelp;
    }

    /** Keeps the whelp on the party's nearest member and sends it away when its time is up. */
    static void tick(MinecraftServer server, ServerLevel level, InstanceRecord record) {
        UUID id = ACTIVE.get(record.slot);
        if (id == null) {
            return;
        }
        if (!(level.getEntity(id) instanceof Warden whelp) || !whelp.isAlive()) {
            ACTIVE.remove(record.slot);
            return;
        }
        if (whelp.tickCount >= PocketDungeonsConfig.whelpSeconds() * 20) {
            whelp.discard();
            ACTIVE.remove(record.slot);
            return;
        }
        if (whelp.tickCount % 20 == 0) {
            ServerPlayer nearest = null;
            for (UUID member : record.members.keySet()) {
                ServerPlayer player = server.getPlayerList().getPlayer(member);
                if (player != null && player.level() == level && player.isAlive()
                        && (nearest == null || player.distanceToSqr(whelp) < nearest.distanceToSqr(whelp))) {
                    nearest = player;
                }
            }
            if (nearest != null) {
                point(whelp, nearest);
            }
        }
    }

    /** The whelp always knows where the party is: it is angry at {@code target} and has them as its prey. */
    static void point(Warden whelp, net.minecraft.world.entity.LivingEntity target) {
        whelp.increaseAngerAt(target, 150, false);
        whelp.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, target);
    }

    /** The floor or instance ended: the whelp goes with it. */
    static void clear(ServerLevel level, int slot) {
        UUID id = ACTIVE.remove(slot);
        if (id != null && level != null) {
            Entity entity = level.getEntity(id);
            if (entity != null) {
                entity.discard();
            }
        }
    }
}
