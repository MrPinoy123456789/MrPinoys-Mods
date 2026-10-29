package charter.mc.cmd;

import charter.id.CoreId;
import charter.id.OrgId;
import charter.land.Core;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.granary.GranaryService;
import charter.mc.granary.Granaries;
import charter.mc.land.Fixtures;
import charter.mc.land.Protection;
import charter.org.MemberRecord;
import com.mojang.brigadier.Command;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /home}, {@code /keep} and {@code /outpost} (protected keeps, K3). Each waits a few seconds, cancelled by moving
 * or taking damage, then takes the player to their bed, their nation's keep, or their organization's outpost in the
 * same dimension. A cooldown follows. {@code /outpost} also costs food from that outpost's supply and is refused when
 * the supply is empty; the other two are free. Charter does not own beds: {@code /home} reads the vanilla spawn point.
 */
public final class Teleports {

    public enum Kind { HOME, KEEP, OUTPOST }

    private record Pending(Kind kind, Vec3 start, int ticksLeft, float health) {
    }

    private record Dest(ServerLevel level, double x, double y, double z, CoreId outpost) {
    }

    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final Map<UUID, Long> READY_AT = new HashMap<>();

    private Teleports() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            for (Kind kind : Kind.values()) {
                String name = kind.name().toLowerCase(java.util.Locale.ROOT);
                Command<CommandSourceStack> run = ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayer();
                    if (player == null) return 0;
                    String refusal = start(player, kind);
                    if (refusal != null) {
                        ctx.getSource().sendFailure(Chat.bad(refusal));
                        return 0;
                    }
                    return 1;
                };
                // The plain name, and under /charter in case another mod on a server owns it.
                dispatcher.register(Commands.literal(name).executes(run));
                dispatcher.register(Commands.literal("charter").then(Commands.literal(name).executes(run)));
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (PENDING.isEmpty() || !CharterServer.running()) return;
            for (UUID id : List.copyOf(PENDING.keySet())) {
                Pending pending = PENDING.get(id);
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player == null) {
                    PENDING.remove(id);
                } else if (player.position().distanceToSqr(pending.start()) > 0.04) {
                    cancel(player, "teleport.moved");
                } else if (player.getHealth() < pending.health()) {
                    cancel(player, "teleport.hurt");
                } else if (pending.ticksLeft() <= 1) {
                    PENDING.remove(id);
                    String refusal = finish(player, pending.kind());
                    if (refusal != null) player.sendSystemMessage(Chat.bad(refusal));
                } else {
                    PENDING.put(id, new Pending(pending.kind(), pending.start(), pending.ticksLeft() - 1, Math.min(pending.health(), player.getHealth())));
                }
            }
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
            if (damageTaken > 0 && entity instanceof ServerPlayer player && PENDING.containsKey(player.getUUID())) {
                cancel(player, "teleport.hurt");
            }
        });
    }

    /** Whether this player is waiting on a teleport (for tests). */
    public static boolean waiting(ServerPlayer player) {
        return PENDING.containsKey(player.getUUID());
    }

    /** Forgets every wait and cooldown (for tests). */
    public static void reset() {
        PENDING.clear();
        READY_AT.clear();
    }

    private static void cancel(ServerPlayer player, String key) {
        PENDING.remove(player.getUUID());
        player.sendSystemMessage(Chat.bad(Text.get(key)));
    }

    /** Starts a teleport. Returns the plain line that refuses it, or null when it began (or was done at once). */
    public static String start(ServerPlayer player, Kind kind) {
        if (!CharterServer.running()) return Text.get("teleport.not_running");
        CharterRuntime rt = CharterServer.runtime();
        if (PENDING.containsKey(player.getUUID())) return Text.get("teleport.already_waiting");
        long wait = READY_AT.getOrDefault(player.getUUID(), 0L) - rt.clock.now();
        if (wait > 0) return Text.get("teleport.cooldown", "seconds", (wait + 999) / 1000);
        Object found = destination(player, kind);
        if (found instanceof String refusal) return refusal;
        if (rt.knobs.teleportWarmupSeconds <= 0) return finish(player, kind);
        PENDING.put(player.getUUID(), new Pending(kind, player.position(), rt.knobs.teleportWarmupSeconds * 20, player.getHealth()));
        player.sendSystemMessage(Chat.line(Text.get("teleport.hold_still", "seconds", rt.knobs.teleportWarmupSeconds)));
        return null;
    }

    /** Takes the player there now: the destination is read again, food is taken, the cooldown starts. */
    private static String finish(ServerPlayer player, Kind kind) {
        CharterRuntime rt = CharterServer.runtime();
        Object found = destination(player, kind);
        if (found instanceof String refusal) return refusal;
        Dest dest = (Dest) found;
        if (kind == Kind.OUTPOST) takeFood(rt, dest.outpost());
        player.teleportTo(dest.level(), dest.x(), dest.y(), dest.z(), Set.of(), player.getYRot(), player.getXRot(), true);
        READY_AT.put(player.getUUID(), rt.clock.now() + rt.knobs.teleportCooldownSeconds * 1000L);
        player.sendSystemMessage(Chat.good(Text.get("teleport.done." + kind.name().toLowerCase(java.util.Locale.ROOT))));
        return null;
    }

    /** A {@link Dest}, or the String that says why not. */
    private static Object destination(ServerPlayer player, Kind kind) {
        CharterRuntime rt = CharterServer.runtime();
        switch (kind) {
            case HOME -> {
                var respawn = player.getRespawnConfig();
                if (respawn == null) return Text.get("teleport.no_bed");
                var data = respawn.respawnData();
                ServerLevel level = levelOf(player, data.dimension().identifier().toString());
                if (level == null) return Text.get("teleport.no_bed");
                if (!Protection.dimension(level).equals(Protection.dimension(player.level()))) return Text.get("teleport.other_dimension");
                if (!level.getBlockState(data.pos()).is(BlockTags.BEDS)) return Text.get("teleport.no_bed");
                return new Dest(level, data.pos().getX() + 0.5, data.pos().getY() + 0.6, data.pos().getZ() + 0.5, null);
            }
            case KEEP -> {
                MemberRecord member = rt.state.membership(new charter.id.PlayerId(player.getUUID())).orElse(null);
                if (member == null) return Text.get("teleport.no_org");
                OrgId nation = rt.state.root(member.org());
                Core keep = rt.state.coresOf(nation).stream().filter(Core::isKeep).findFirst().orElse(null);
                if (keep == null) return Text.get("teleport.no_keep");
                return site(player, keep, Fixtures.Kind.NEXUS, null);
            }
            default -> {
                MemberRecord member = rt.state.membership(new charter.id.PlayerId(player.getUUID())).orElse(null);
                if (member == null) return Text.get("teleport.no_org");
                Core outpost = rt.state.coresOf(member.org()).stream().filter(c -> !c.isKeep()).findFirst().orElse(null);
                if (outpost == null) return Text.get("teleport.no_outpost");
                if (!outpost.activeCamp()) return Text.get("teleport.outpost_raising");
                if (rt.state.siege.store(outpost.id).provisions.totalItems() < rt.knobs.outpostTeleportFood) {
                    return Text.get("teleport.outpost_empty");
                }
                return site(player, outpost, Fixtures.Kind.BANNER, outpost.id);
            }
        }
    }

    private static Object site(ServerPlayer player, Core core, Fixtures.Kind kind, CoreId outpost) {
        CharterRuntime rt = CharterServer.runtime();
        Fixtures.Fixture fixture = Fixtures.of(rt.state).first(core.id, kind);
        if (fixture == null) return Text.get("teleport.nowhere");
        ServerLevel level = levelOf(player, fixture.dimension());
        if (level == null) return Text.get("teleport.nowhere");
        if (!Protection.dimension(level).equals(Protection.dimension(player.level()))) return Text.get("teleport.other_dimension");
        level.getChunk(fixture.x() >> 4, fixture.z() >> 4);
        return new Dest(level, fixture.x() + 0.5, fixture.y() + 1.0, fixture.z() + 0.5, outpost);
    }

    private static ServerLevel levelOf(ServerPlayer player, String dimension) {
        for (ServerLevel level : player.level().getServer().getAllLevels()) {
            if (Protection.dimension(level).equals(dimension)) return level;
        }
        return null;
    }

    /** Takes the food from the outpost's supply, the least nourishing first, through the granary so a barrel follows. */
    private static void takeFood(CharterRuntime rt, CoreId outpost) {
        Runnable take = () -> {
            var provisions = rt.state.siege.store(outpost).provisions;
            List<String> items = new ArrayList<>(provisions.counts().keySet());
            items.sort(Comparator.comparingDouble(id -> rt.sim.food().get(id) == null ? 0 : rt.sim.food().get(id).nourishment()));
            int left = rt.knobs.outpostTeleportFood;
            for (String id : items) {
                if (left <= 0) break;
                left -= provisions.remove(id, Math.min(left, provisions.count(id)));
            }
        };
        GranaryService service = Granaries.service();
        if (service != null) service.around(take);
        else take.run();
    }
}
