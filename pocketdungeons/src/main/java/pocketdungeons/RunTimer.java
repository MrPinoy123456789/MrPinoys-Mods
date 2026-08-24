package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

import java.util.UUID;

/**
 * The clock a keystone run is measured against, shown as a server-side boss bar
 * so a vanilla client renders it with no client mod.
 *
 * <p><strong>Expiry alone does not end the run.</strong> The bar turns red, reads
 * {@code OVER TIME}, and the run continues to whatever end the player walks to --
 * completing late still reaches the reward room, just with none of its three
 * chests earned. The run only ends on its own once the clock runs out
 * <em>and</em> nobody has completed it yet (see {@code Instances}' expiry
 * check), which is the one way left to lose a keystone level.
 */
final class RunTimer {

    private final ServerBossEvent bar;
    private final int totalSeconds;
    private final int keystoneLevel;
    private final int roomCount;

    private int elapsedTicks;
    private int roomsSeen;
    private boolean overTime;

    RunTimer(int keystoneLevel, int totalSeconds, int roomCount) {
        this.keystoneLevel = keystoneLevel;
        this.totalSeconds = Math.max(1, totalSeconds);
        this.roomCount = Math.max(1, roomCount);
        this.bar = new ServerBossEvent(UUID.randomUUID(), Component.empty(),
                BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
        refresh();
    }

    void addPlayer(ServerPlayer player) {
        bar.addPlayer(player);
    }

    void removePlayer(ServerPlayer player) {
        bar.removePlayer(player);
    }

    void close() {
        bar.removeAllPlayers();
        bar.setVisible(false);
    }

    /** How many distinct rooms the party has stood in, for the bar's progress readout. */
    void notePresence(int rooms) {
        if (rooms > roomsSeen) {
            roomsSeen = rooms;
        }
    }

    boolean overTime() {
        return overTime;
    }

    int secondsRemaining() {
        return Math.max(0, totalSeconds - elapsedTicks / 20);
    }

    /** The clock's full length, for scoring a completion against how much of it is left. */
    int totalSeconds() {
        return totalSeconds;
    }

    /**
     * Advances the clock by {@code ticks} and repaints the bar.
     *
     * <p>Called from the existing instance watcher rather than every tick, so
     * {@code ticks} is the watcher's interval. The bar only needs to be right to
     * the second and the watcher runs at 20 ticks by default.
     */
    void tick(int ticks) {
        elapsedTicks += Math.max(0, ticks);
        if (!overTime && secondsRemaining() <= 0) {
            overTime = true;
        }
        refresh();
    }

    private void refresh() {
        int remaining = secondsRemaining();
        String title = "Keystone [" + keystoneLevel + "] - "
                + (overTime ? "OVER TIME" : KeystoneMath.formatClock(remaining))
                + " - " + roomsSeen + "/" + roomCount + " rooms";

        ChatFormatting colour = overTime ? ChatFormatting.DARK_RED
                : remaining * 5 <= totalSeconds ? ChatFormatting.RED
                : remaining * 2 <= totalSeconds ? ChatFormatting.YELLOW
                : ChatFormatting.GREEN;

        bar.setName(Component.literal(title).withStyle(colour));
        bar.setProgress(Math.max(0.0f, Math.min(1.0f, (float) remaining / totalSeconds)));
        bar.setColor(overTime ? BossEvent.BossBarColor.RED
                : remaining * 5 <= totalSeconds ? BossEvent.BossBarColor.RED
                : remaining * 2 <= totalSeconds ? BossEvent.BossBarColor.YELLOW
                : BossEvent.BossBarColor.GREEN);
    }
}
