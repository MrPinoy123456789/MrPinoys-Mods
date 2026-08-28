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
 * <p><strong>Expiry does not end the run.</strong> The bar turns red, reads
 * {@code OVER TIME}, and the run continues. The keystone is downgraded by 2
 * levels once when the clock runs out, but the player can still finish and
 * earn a door offer to mitigate the loss.
 */
final class RunTimer {

    private final ServerBossEvent bar;
    private final int totalSeconds;
    private final int keystoneLevel;
    private final int roomCount;

    private int elapsedTicks;
    private int roomsSeen;
    private boolean overTime;
    private boolean completed;

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
        if (completed) {
            return;
        }
        elapsedTicks += Math.max(0, ticks);
        if (!overTime && secondsRemaining() <= 0) {
            overTime = true;
        }
        refresh();
    }

    /** Freezes the clock at its last reading once the run has been completed. */
    void markCompleted() {
        completed = true;
        refresh();
    }

    private void refresh() {
        if (completed) {
            bar.setName(Component.literal(
                    "Keystone [" + keystoneLevel + "] - COMPLETE"
                    + " - " + roomsSeen + "/" + roomCount + " rooms")
                    .withStyle(ChatFormatting.GREEN));
            bar.setProgress(1.0f);
            bar.setColor(BossEvent.BossBarColor.GREEN);
            return;
        }
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
