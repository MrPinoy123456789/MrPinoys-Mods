package charter.mc.cmd;

import charter.id.CoreId;
import charter.id.OrgId;
import charter.land.Core;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.book.Cues;
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
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.BossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /home}, {@code /keep}, {@code /outpost}, {@code /spawn} and {@code /hub} (protected keeps, K3). {@code /hub} takes anyone
 * to the Nexus of the admin kingdom, the server's spawn town, whatever kingdom they belong to. Each waits a few seconds, cancelled by moving
 * or taking damage, then takes the player to their bed, their nation's keep, or their organization's outpost in the
 * same dimension. A cooldown follows. {@code /outpost} also costs food from that outpost's supply and is refused when
 * the supply is empty; the other two are free. Charter does not own beds: {@code /home} reads the vanilla spawn point.
 */
public final class Teleports {

    public enum Kind { HOME, KEEP, OUTPOST, SPAWN, HUB }

    /** A journey waiting to start. {@code bar} is the boss bar that fills over the warm-up; it goes when the wait ends. */
    private record Pending(Kind kind, Vec3 start, int ticksLeft, float health, int total, ServerBossEvent bar) {
    }

    /** Where a journey ends. {@code note} is a lang key said on arrival, or null. */
    private record Dest(ServerLevel level, double x, double y, double z, CoreId outpost, String note) {
        Dest(ServerLevel level, double x, double y, double z, CoreId outpost) {
            this(level, x, y, z, outpost, null);
        }
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
                    PENDING.remove(id).bar().removeAllPlayers();
                } else if (player.position().distanceToSqr(pending.start()) > 0.04) {
                    cancel(player, "teleport.moved");
                } else if (player.getHealth() < pending.health()) {
                    cancel(player, "teleport.hurt");
                } else if (pending.ticksLeft() <= 1) {
                    PENDING.remove(id).bar().removeAllPlayers();
                    String refusal = finish(player, pending.kind());
                    if (refusal != null) {
                        player.sendSystemMessage(Chat.bad(refusal));
                        Cues.actionBar(player, refusal, ChatFormatting.RED);
                    }
                } else {
                    int left = pending.ticksLeft() - 1;
                    pending.bar().setProgress(1.0F - (float) left / pending.total());
                    PENDING.put(id, new Pending(pending.kind(), pending.start(), left, Math.min(pending.health(), player.getHealth()),
                            pending.total(), pending.bar()));
                }
            }
        });
        // A player who leaves mid wait takes nothing with them: the wait and its bar go.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            Pending pending = PENDING.remove(handler.player.getUUID());
            if (pending != null) pending.bar().removeAllPlayers();
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
        for (Pending pending : PENDING.values()) pending.bar().removeAllPlayers();
        PENDING.clear();
        READY_AT.clear();
    }

    /** The boss bar of a waiting journey, or null (for tests). */
    public static ServerBossEvent bar(ServerPlayer player) {
        Pending pending = PENDING.get(player.getUUID());
        return pending == null ? null : pending.bar();
    }

    private static void cancel(ServerPlayer player, String key) {
        Pending pending = PENDING.remove(player.getUUID());
        if (pending != null) pending.bar().removeAllPlayers();
        player.sendSystemMessage(Chat.bad(Text.get(key)));
        Cues.actionBar(player, Text.get(key + ".short"), ChatFormatting.RED);
    }

    /** Starts a teleport. Returns the plain line that refuses it, or null when it began (or was done at once). */
    public static String start(ServerPlayer player, Kind kind) {
        if (!CharterServer.running()) return Text.get("teleport.not_running");
        CharterRuntime rt = CharterServer.runtime();
        if (PENDING.containsKey(player.getUUID())) return Text.get("teleport.already_waiting");
        long wait = READY_AT.getOrDefault(player.getUUID(), 0L) - rt.clock.now();
        if (wait > 0) return Text.get("teleport.cooldown", "seconds", (wait + 999) / 1000);
        Object found = destination(player, kind, false);
        if (found instanceof Refusal refusal) return refusal.text();
        if (rt.knobs.teleportWarmupSeconds <= 0) return finish(player, kind);
        int total = rt.knobs.teleportWarmupSeconds * 20;
        // The wait is shown as a boss bar that fills: Bedrock draws it well and it needs no reading. Chat keeps a copy.
        ServerBossEvent bar = new ServerBossEvent(java.util.UUID.randomUUID(),
                Component.literal(Text.get("teleport.bar." + kind.name().toLowerCase(java.util.Locale.ROOT))),
                BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
        bar.setProgress(0.0F);
        bar.addPlayer(player);
        PENDING.put(player.getUUID(), new Pending(kind, player.position(), total, player.getHealth(), total, bar));
        player.sendSystemMessage(Chat.line(Text.get("teleport.hold_still", "seconds", rt.knobs.teleportWarmupSeconds)));
        return null;
    }

    /** Takes the player there now: the destination is read again, food is taken, the cooldown starts. */
    private static String finish(ServerPlayer player, Kind kind) {
        CharterRuntime rt = CharterServer.runtime();
        Object found = destination(player, kind, false);
        if (found instanceof Refusal refusal) return refusal.text();
        Dest dest = (Dest) found;
        if (kind == Kind.OUTPOST) takeFood(rt, dest.outpost());
        player.teleportTo(dest.level(), dest.x(), dest.y(), dest.z(), Set.of(), player.getYRot(), player.getXRot(), true);
        READY_AT.put(player.getUUID(), rt.clock.now() + rt.knobs.teleportCooldownSeconds * 1000L);
        String done = Text.get("teleport.done." + kind.name().toLowerCase(java.util.Locale.ROOT));
        player.sendSystemMessage(Chat.good(done));
        Cues.actionBar(player, done, ChatFormatting.GREEN);
        if (dest.note() != null) player.sendSystemMessage(Chat.line(Text.get(dest.note())));
        Cues.arrive(player);
        return null;
    }

    /** Why a journey cannot start: the lang key (its {@code .short} form labels the button) and the full line. */
    private record Refusal(String key, String text) {
    }

    private static Refusal no(String key) {
        return new Refusal(key, Text.get(key));
    }

    /**
     * What a travel button should say before it is pressed: whether it is shown at all (a keep for a nation that has
     * none never applies, so no button) and the short reason it is greyed for now, or null when it would work. It
     * reads state only; unlike a press it never loads a chunk.
     */
    public record Option(boolean shown, String why) {
    }

    public static Option option(ServerPlayer player, Kind kind) {
        if (!CharterServer.running()) return new Option(true, null);
        CharterRuntime rt = CharterServer.runtime();
        if (PENDING.containsKey(player.getUUID())) return new Option(true, Text.get("teleport.already_waiting.short"));
        long wait = READY_AT.getOrDefault(player.getUUID(), 0L) - rt.clock.now();
        if (wait > 0) return new Option(true, Text.get("teleport.cooldown.short", "seconds", (wait + 999) / 1000));
        if (destination(player, kind, true) instanceof Refusal refusal) {
            boolean never = switch (kind) {
                case HUB -> refusal.key().equals("teleport.no_hub");
                case KEEP -> refusal.key().equals("teleport.no_org") || refusal.key().equals("teleport.no_keep");
                case OUTPOST -> refusal.key().equals("teleport.no_org") || refusal.key().equals("teleport.no_outpost");
                default -> false;
            };
            return new Option(!never, Text.get(refusal.key() + ".short"));
        }
        return new Option(true, null);
    }

    /** A {@link Dest}, or the {@link Refusal} that says why not. {@code cheap} reads state only: no chunk is loaded. */
    private static Object destination(ServerPlayer player, Kind kind, boolean cheap) {
        CharterRuntime rt = CharterServer.runtime();
        switch (kind) {
            case HOME -> {
                var respawn = player.getRespawnConfig();
                if (respawn == null) return no("teleport.no_bed");
                var data = respawn.respawnData();
                ServerLevel level = levelOf(player, data.dimension().identifier().toString());
                if (level == null) return no("teleport.no_bed");
                if (!Protection.dimension(level).equals(Protection.dimension(player.level()))) return no("teleport.other_dimension");
                if (!(cheap && !level.isLoaded(data.pos())) && !level.getBlockState(data.pos()).is(BlockTags.BEDS)) return no("teleport.no_bed");
                return new Dest(level, data.pos().getX() + 0.5, data.pos().getY() + 0.6, data.pos().getZ() + 0.5, null);
            }
            case SPAWN -> {
                ServerLevel level = player.level().getServer().overworld();
                if (!Protection.dimension(level).equals(Protection.dimension(player.level()))) return no("teleport.other_dimension");
                BlockPos at = level.getRespawnData().pos();
                if (cheap) return new Dest(level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, null);
                level.getChunk(at.getX() >> 4, at.getZ() >> 4);
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
                return new Dest(level, at.getX() + 0.5, y, at.getZ() + 0.5, null);
            }
            case HUB -> {
                var hub = rt.state.orgs().stream().filter(o -> o.isTopLevel() && o.exists() && o.adminKingdom).findFirst();
                if (hub.isEmpty()) return no("teleport.no_hub");
                Core keep = rt.state.coresOf(hub.get().id).stream().filter(Core::isKeep).findFirst().orElse(null);
                if (keep == null) return no("teleport.no_hub");
                return site(player, keep, Fixtures.Kind.NEXUS, null, cheap);
            }
            case KEEP -> {
                MemberRecord member = rt.state.membership(new charter.id.PlayerId(player.getUUID())).orElse(null);
                if (member == null) return no("teleport.no_org");
                OrgId nation = rt.state.root(member.org());
                Core keep = rt.state.coresOf(nation).stream().filter(Core::isKeep).findFirst().orElse(null);
                if (keep == null) return no("teleport.no_keep");
                return site(player, keep, Fixtures.Kind.NEXUS, null, cheap);
            }
            default -> {
                MemberRecord member = rt.state.membership(new charter.id.PlayerId(player.getUUID())).orElse(null);
                if (member == null) return no("teleport.no_org");
                Core outpost = rt.state.coresOf(member.org()).stream().filter(c -> !c.isKeep()).findFirst().orElse(null);
                if (outpost == null) return no("teleport.no_outpost");
                if (!outpost.activeCamp()) return no("teleport.outpost_raising");
                if (rt.state.siege.store(outpost.id).provisions.totalItems() < rt.knobs.outpostTeleportFood) {
                    return no("teleport.outpost_empty");
                }
                return site(player, outpost, Fixtures.Kind.BANNER, outpost.id, cheap);
            }
        }
    }

    private static Object site(ServerPlayer player, Core core, Fixtures.Kind kind, CoreId outpost, boolean cheap) {
        CharterRuntime rt = CharterServer.runtime();
        Fixtures.Fixture fixture = Fixtures.of(rt.state).first(core.id, kind);
        if (fixture == null && kind == Fixtures.Kind.NEXUS && charter.mc.land.NexusLift.lifted(core.id)) return liftedKeep(player, core, cheap);
        if (fixture == null) return no("teleport.nowhere");
        ServerLevel level = levelOf(player, fixture.dimension());
        if (level == null) return no("teleport.nowhere");
        if (!Protection.dimension(level).equals(Protection.dimension(player.level()))) return no("teleport.other_dimension");
        if (!cheap) level.getChunk(fixture.x() >> 4, fixture.z() >> 4);
        return new Dest(level, fixture.x() + 0.5, fixture.y() + 1.0, fixture.z() + 0.5, outpost);
    }

    /**
     * A keep whose lodestone is being carried to a new spot: the journey ends at the top of the middle of the keep's core
     * chunk instead, and the arrival line says why.
     */
    private static Object liftedKeep(ServerPlayer player, Core keep, boolean cheap) {
        ServerLevel level = levelOf(player, keep.center.dimension());
        if (level == null) return no("teleport.nowhere");
        if (!Protection.dimension(level).equals(Protection.dimension(player.level()))) return no("teleport.other_dimension");
        int x = keep.center.x() * 16 + 8;
        int z = keep.center.z() * 16 + 8;
        if (cheap) return new Dest(level, x + 0.5, player.getY(), z + 0.5, null, "nexus_lift.arrive_middle");
        level.getChunk(x >> 4, z >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new Dest(level, x + 0.5, y, z + 0.5, null, "nexus_lift.arrive_middle");
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
