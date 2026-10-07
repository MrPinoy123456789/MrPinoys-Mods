package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;

/**
 * The omen boss bar: one vanilla {@link ServerBossEvent} per floor loop
 * instance, shown to every member while they stand in the dungeon dimension.
 *
 * <p>J3: the bar is the trip's lives ({@code 5 - omen}). During a floor it
 * reads the spawner gate and the lives left; between floors it names the
 * floor just cleared, what going home pays, and the lives. At home it is
 * hidden. Colour and fill follow lives: full and green at five, red at one.
 *
 * <p>Membership of the bar is reconciled on every watch tick
 * ({@link #sync}), so every route into the dungeon is covered without each
 * entry point having to remember it; {@link #detach} and {@link #close} take
 * players off at once on the way out. Everything here is vanilla and
 * server-side.
 */
final class OmenBar {

    private final ServerBossEvent event = new ServerBossEvent(UUID.randomUUID(),
            Component.empty(), BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);

    /** Server tick of the last cue per {@link Omen.Source}, for {@link OmenBarText#cueCooldownTicks}. */
    private final long[] lastCueTick = new long[Omen.Source.values().length];
    /** Last tick any rise made a sound, so a gain inside a line cooldown is still heard once a second (item 17). */
    private long lastSoundTick = Long.MIN_VALUE / 2;

    /** The cue line being held on the action bar, and the tick it stops being repainted. */
    private Component heldLine;
    private long heldUntilTick;
    private long lastRepaintTick;

    /** Ticks between repaints of a held line; under the client's own fade so it never blinks out. */
    private static final int HELD_REPAINT_TICKS = 40;

    private OmenBar() {
        Arrays.fill(lastCueTick, Long.MIN_VALUE / 2);
    }

    private static OmenBar of(InstanceRecord record) {
        if (record.omenBar == null) {
            record.omenBar = new OmenBar();
        }
        return record.omenBar;
    }

    /** Whether the bar has anything to say in the record's current phase. */
    static boolean shows(InstanceRecord record) {
        if (!record.inFloorLoop()) {
            return false;
        }
        return switch (record.phase) {
            case ACTIVE -> true;
            case FLOOR_CLEARED, PREVIEW, SAFE_RETURN -> record.interval.floorIndex > 0;
            case HOME -> false;
        };
    }

    /**
     * Repaints the bar from the record and reconciles who sees it. Cheap to
     * call often: the vanilla setters only send a packet on a real change, and
     * adding a player who already watches is a set lookup.
     */
    static void sync(MinecraftServer server, InstanceRecord record) {
        if (!shows(record)) {
            if (record.omenBar != null) {
                record.omenBar.event.removeAllPlayers();
            }
            return;
        }
        OmenBar bar = of(record);
        boolean active = record.phase == RunSession.Phase.ACTIVE;
        int floorCount = active ? record.interval.floorIndex + 1 : Math.max(1, record.interval.floorIndex);
        int chests = !active && record.floor.rewardChests >= 0 ? record.floor.rewardChests
                : Omen.baseRewardChests() + ZoneRules.of(record).bonusChests(floorCount);
        String title;
        if (active) {
            int total = record.floor.spawnersTotal;
            title = OmenBarText.activeTitle(record.interval.omen, chests, record.floor.spawnersCleared, total,
                    DifficultyProfile.spawnersNeeded(total, PocketDungeonsConfig.spawnerClearThreshold()));
        } else {
            title = OmenBarText.clearedTitle(record.interval.floorIndex, TripView.dungeonName(record),
                    record.interval.endlessMine, record.interval.finished, TripView.finalAhead(record),
                    record.interval.omen, chests);
        }
        repaintHeldLine(server, record, bar);
        bar.event.setName(Component.literal(title));
        // J3: the bar is lives either way: full and green at five, red at one.
        bar.event.setColor(colour(OmenBarText.omenColourIndex(record.interval.omen)));
        bar.event.setProgress(Omen.lives(record.interval.omen) / (float) Omen.LIVES);

        for (UUID member : record.members.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null && player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                bar.event.addPlayer(player);
            }
        }
        for (ServerPlayer watching : new ArrayList<>(bar.event.getPlayers())) {
            if (!record.members.containsKey(watching.getUUID())
                    || !watching.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                bar.event.removePlayer(watching);
            }
        }
    }

    /** Repaints the held cue line (PD-100) until its hold runs out. */
    private static void repaintHeldLine(MinecraftServer server, InstanceRecord record, OmenBar bar) {
        if (bar.heldLine == null) {
            return;
        }
        long now = server.getTickCount();
        if (now >= bar.heldUntilTick) {
            bar.heldLine = null;
            return;
        }
        if (now - bar.lastRepaintTick < HELD_REPAINT_TICKS) {
            return;
        }
        bar.lastRepaintTick = now;
        for (UUID member : record.members.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null && player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                player.sendOverlayMessage(bar.heldLine);
            }
        }
    }

    /** Takes {@code member} off the bar as they leave the instance, online or not. */
    static void detach(InstanceRecord record, UUID member) {
        if (record.omenBar == null) {
            return;
        }
        for (ServerPlayer watching : new ArrayList<>(record.omenBar.event.getPlayers())) {
            if (watching.getUUID().equals(member)) {
                record.omenBar.event.removePlayer(watching);
            }
        }
    }

    /** Takes everyone off the bar for good, as the instance is torn down. */
    static void close(InstanceRecord record) {
        if (record.omenBar != null) {
            record.omenBar.event.removeAllPlayers();
            record.omenBar = null;
        }
    }

    /**
     * A trigger {@code source} answered (J3: a wave, the bargain's harder
     * floor): a short line on each member's action bar and a low cue, held
     * back per source so dwell does not repeat itself, then an immediate
     * repaint of the bar.
     */
    static void cue(MinecraftServer server, InstanceRecord record, Omen.Source source) {
        if (!record.inFloorLoop()) {
            return;
        }
        showLine(server, record, source, OmenBarText.cueLine(source));
        sync(server, record);
    }

    /**
     * A death took a life: the cue names what is left ({@code "A bad omen. 2
     * lives left."}), never held back, then the bar repaints.
     */
    static void deathCue(MinecraftServer server, InstanceRecord record, int omen) {
        if (!record.inFloorLoop()) {
            return;
        }
        showLine(server, record, Omen.Source.DEATH, OmenBarText.deathCue(omen));
        sync(server, record);
    }

    /** The shared cue: rate limited per source, held on screen for the sensor line (PD-100). */
    private static void showLine(MinecraftServer server, InstanceRecord record, Omen.Source source,
                                 String text) {
        OmenBar bar = of(record);
        long now = server.getTickCount();
        int slot = source.ordinal();
        if (now - bar.lastCueTick[slot] >= OmenBarText.cueCooldownTicks(source)) {
            bar.lastCueTick[slot] = now;
            Component line = Component.literal(text).withStyle(ChatFormatting.DARK_PURPLE);
            int hold = OmenBarText.holdTicks(source);
            if (hold > 0) {
                bar.heldLine = line;
                bar.heldUntilTick = now + hold;
                bar.lastRepaintTick = now;
            }
            for (UUID member : record.members.keySet()) {
                ServerPlayer player = server.getPlayerList().getPlayer(member);
                if (player != null && player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                    player.sendOverlayMessage(line);
                    Chime.omenRises(player, source);
                }
            }
            bar.lastSoundTick = now;
        } else if (now - bar.lastSoundTick >= 20) {
            // A trigger inside the line's cooldown is still heard, once a second at most.
            bar.lastSoundTick = now;
            for (UUID member : record.members.keySet()) {
                ServerPlayer player = server.getPlayerList().getPlayer(member);
                if (player != null && player.level().dimension().equals(PocketDungeonsMod.DUNGEON_LEVEL)) {
                    Chime.omenRises(player, source);
                }
            }
        }
    }

    private static BossEvent.BossBarColor colour(int colourIndex) {
        return switch (colourIndex) {
            case 0 -> BossEvent.BossBarColor.GREEN;
            case 1 -> BossEvent.BossBarColor.YELLOW;
            default -> BossEvent.BossBarColor.RED;
        };
    }
}
