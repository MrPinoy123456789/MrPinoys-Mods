package pocketdungeons;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The trip sidebar (design pass 2026-10-09, Q7; PD-187, PD-190): the floor, the lives, the spawners, the haul and
 * the compass in a column down the right edge, because the player never reads chat live. Each player gets their
 * own vanilla sidebar objective, built from packets on a private {@link Scoreboard}, so nothing touches the
 * server's real scoreboard and each member sees their own haul. The number column is blank. Lines are repainted
 * only when something changed, at most once per {@code sidebarRepaintTicks}.
 *
 * <p>Shown to a member while their trip is in the dungeon dimension; cleared the moment they are not. A player
 * can hide it with {@code /dungeon display off} (kept in memory, so a restart forgets it).
 */
final class SidebarDisplay {

    private static final String OBJECTIVE = "pd_side";

    /** The private scoreboard objectives are built on; it never holds a score. */
    private static final Scoreboard BOARD = new Scoreboard();

    /** What a player's client currently shows. */
    private record Shown(String title, List<String> lines) {}

    private static final Map<UUID, Shown> SHOWN = new HashMap<>();
    private static final Set<UUID> HIDDEN = new HashSet<>();

    private SidebarDisplay() {}

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int period = PocketDungeonsConfig.sidebarRepaintTicks();
            if (server.getTickCount() % Math.max(1, period) == 0) {
                tick(server);
            }
        });
    }

    /** {@code /dungeon display on|off}. */
    static void setHidden(ServerPlayer player, boolean hidden) {
        if (hidden) {
            HIDDEN.add(player.getUUID());
            clear(player);
        } else {
            HIDDEN.remove(player.getUUID());
        }
    }

    private static void tick(MinecraftServer server) {
        Set<UUID> painted = new HashSet<>();
        if (PocketDungeonsConfig.sidebarEnabled()) {
            for (InstanceRecord record : new ArrayList<>(InstanceRegistry.bySlot.values())) {
                if (!record.isKeystoneRun() || !OmenBar.shows(record)) {
                    continue;
                }
                for (UUID member : record.members.keySet()) {
                    ServerPlayer player = server.getPlayerList().getPlayer(member);
                    if (player == null || HIDDEN.contains(member)
                            || !player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                        continue;
                    }
                    paint(server, record, player);
                    painted.add(member);
                }
            }
        }
        for (UUID member : new ArrayList<>(SHOWN.keySet())) {
            if (!painted.contains(member)) {
                ServerPlayer player = server.getPlayerList().getPlayer(member);
                if (player != null) {
                    clear(player);
                } else {
                    SHOWN.remove(member);
                }
            }
        }
    }

    /** The facts the sidebar reads for {@code member} on {@code record}. */
    static SidebarLines.Facts factsFor(MinecraftServer server, InstanceRecord record, UUID member) {
        DungeonDef def = TripView.def(record);
        DungeonDef.Node node = def == null ? null : def.node(record.interval.nodeId);
        boolean active = record.phase == RunSession.Phase.ACTIVE;
        DungeonLog log = DungeonLog.forServer(server);
        DungeonLog.Entry entry = log.get(member);
        int total = record.floor.spawnersTotal;
        ServerPlayer present = server.getPlayerList().getPlayer(member);
        int[] heard = present == null ? null : PressureSources.heardAt(record, present.blockPosition());
        return new SidebarLines.Facts(TripView.dungeonName(record), EndlessMineRules.isMine(record),
                active ? record.interval.floorIndex + 1 : Math.max(1, record.interval.floorIndex),
                node == null ? "" : node.name(), record.floor.affixes.contains(AffixIds.OMINOUS),
                Omen.lives(record.interval.omen), record.members.size(), active,
                record.floor.spawnersCleared, total,
                DifficultyProfile.spawnersNeeded(total, PocketDungeonsConfig.spawnerClearThreshold()),
                record.interval.finished, log.haulOf(member),
                record.interval.finishBanked.getOrDefault(member, 0), entry.keystoneLevel(),
                entry.chartProgress(), ScrapMath.levelCost(entry.highestCharts()),
                heard == null ? 0 : heard[0], heard == null ? 0 : heard[1]);
    }

    private static void paint(MinecraftServer server, InstanceRecord record, ServerPlayer player) {
        SidebarLines.Facts facts = factsFor(server, record, player.getUUID());
        String title = SidebarLines.title(facts);
        List<SidebarLines.Line> lines = SidebarLines.lines(facts);
        List<String> keys = new ArrayList<>();
        for (SidebarLines.Line line : lines) {
            keys.add(line.colour() + "|" + line.text());
        }
        Shown before = SHOWN.get(player.getUUID());
        if (before != null && before.title().equals(title) && before.lines().equals(keys)) {
            return;
        }
        Objective objective = new Objective(BOARD, OBJECTIVE, ObjectiveCriteria.DUMMY,
                Component.literal(title).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE);
        if (before == null) {
            player.connection.send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD));
            player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective));
        } else if (!before.title().equals(title)) {
            player.connection.send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_CHANGE));
        }
        for (int i = 0; i < lines.size(); i++) {
            if (before != null && i < before.lines().size() && before.lines().get(i).equals(keys.get(i))) {
                continue;
            }
            SidebarLines.Line line = lines.get(i);
            player.connection.send(new ClientboundSetScorePacket("l" + i, OBJECTIVE, lines.size() - i,
                    Optional.of(Component.literal(line.text()).withStyle(style(line.colour()))),
                    Optional.of(BlankFormat.INSTANCE)));
        }
        if (before != null) {
            for (int i = lines.size(); i < before.lines().size(); i++) {
                player.connection.send(new ClientboundResetScorePacket("l" + i, OBJECTIVE));
            }
        }
        SHOWN.put(player.getUUID(), new Shown(title, keys));
    }

    /** Takes the sidebar off {@code player}'s screen, if it is up. */
    static void clear(ServerPlayer player) {
        if (SHOWN.remove(player.getUUID()) == null) {
            return;
        }
        Objective objective = new Objective(BOARD, OBJECTIVE, ObjectiveCriteria.DUMMY, Component.empty(),
                ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE);
        player.connection.send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_REMOVE));
    }

    private static ChatFormatting style(String colour) {
        return switch (colour) {
            case "green" -> ChatFormatting.GREEN;
            case "yellow" -> ChatFormatting.YELLOW;
            case "red" -> ChatFormatting.RED;
            case "aqua" -> ChatFormatting.AQUA;
            case "gray" -> ChatFormatting.GRAY;
            case "dark_purple" -> ChatFormatting.DARK_PURPLE;
            case "dark_aqua" -> ChatFormatting.DARK_AQUA;
            default -> ChatFormatting.WHITE;
        };
    }
}
