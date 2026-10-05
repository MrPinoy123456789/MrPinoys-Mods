package pocketdungeons;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lemon's first-visit tour (playtest 2026-10-03, A5): the player's own script
 * for a brand new player. The bag chest and the door wall at the first bag
 * choice, the run storage and the three-floor set at the first floor cleared,
 * the way home at the first arrival home. Each step is said once, ever, and is
 * stored for good in the per-player task sidecar ({@link DungeonLog#taskProgress}),
 * the same shape as {@link StationTutorial}.
 *
 * <p><strong>The party leader drives the tour</strong> (owner decision,
 * 2026-10-03). The flags are the leader's: the instance's owner. A member who
 * joins a leader's run mid-tour hears the lines the leader is due, and never
 * restarts the leader's tour or marks anything of their own. A solo player is
 * their own leader. Lemon is still one companion per player (her body is
 * private to its owner); a shared, single Lemon per party is a larger change
 * and is not part of this.
 *
 * <p>Lemon cannot walk, so every line points ("the door ahead of you", "the
 * board on the wall") rather than leads. At the first home arrival the tour
 * runs into {@link StationTutorial}'s nudge, so it reads as one tour and not
 * two systems; that nudge keeps its one-station-per-visit pacing.
 *
 * <p>The pure half ({@link #due}) takes the log, so a headless test can drive it.
 */
final class FirstVisitTutorial {

    private FirstVisitTutorial() {}

    /** What just happened, which decides which steps may fire. */
    enum Trigger { BAG_CHOSEN, FLOOR_CLEARED, HOME_ARRIVAL }

    enum Step {
        BAG_CHEST("bag_chest", Trigger.BAG_CHOSEN) {
            List<String> lines() {
                return List.of(
                        "Good pick. That chest held your bag, and your kit is in your pack now. Have a look through it.",
                        "Come home safe and the bag chest is filled with a fresh kit, so you never start a run with an empty pack.");
            }
        },
        DOOR_WALL("door_wall", Trigger.BAG_CHOSEN) {
            List<String> lines() {
                return List.of(
                        "Now the front wall, the one with the door on it. Start with the door itself: that is where a run begins.",
                        "Next to it is the board. It shows what the door is offering, so read it before you choose.",
                        "To pick that door, right-click it.",
                        "Then there is the lever. It opens the door once you have chosen. Pull it when you are ready.");
            }
        },
        ENDER_CHEST("ender_chest", Trigger.FLOOR_CLEARED) {
            List<String> lines() {
                return List.of("Back in the safe room there is an ender chest set into the wall. It is your run storage: "
                        + "27 extra slots that stay with the run, so you can leave spare things there.");
            }
        },
        SET_OF_THREE("set_of_three", Trigger.FLOOR_CLEARED) {
            List<String> lines() {
                int floors = PocketDungeonsConfig.floorsPerSafeVisit();
                return List.of("Floors come in a set of " + floors + ". Clear " + floors
                        + " and going home is the sensible thing: it banks your key progress and refills your kit. "
                        + "Go deeper and the omen starts higher.");
            }
        },
        WAY_HOME("way_home", Trigger.HOME_ARRIVAL) {
            List<String> lines() {
                return List.of("This is home. The lever at the end of a floor is the way here: it banks your key progress "
                        + "and brings you back to your own room, a fresh kit in the bag chest.",
                        "Build in here as you like. Everything you place is kept.");
            }
        };

        final String id;
        final Trigger trigger;

        Step(String id, Trigger trigger) {
            this.id = id;
            this.trigger = trigger;
        }

        /** What Lemon says, in order, one bubble each. */
        abstract List<String> lines();

        String key() {
            return "tour_" + id;
        }
    }

    /** Every step {@code trigger} fires that {@code leader} has not heard yet, in tour order. Never a finished one. */
    static List<Step> due(DungeonLog log, UUID leader, Trigger trigger) {
        List<Step> steps = new ArrayList<>();
        for (Step step : Step.values()) {
            if (step.trigger == trigger && log.taskProgress(leader, step.key()) < 1) {
                steps.add(step);
            }
        }
        return steps;
    }

    /** Marks {@code step} done for {@code leader}, forever. */
    static void markDone(DungeonLog log, UUID leader, Step step) {
        log.setTaskProgress(leader, step.key(), 1);
    }

    /**
     * Whether the tour is the leader's to run for {@code record}: the party
     * leader is the instance's owner, and a visit to someone else's room carries
     * no tour of its own.
     */
    static UUID leaderOf(InstanceRecord record) {
        return record == null || record.visitInstance ? null : record.owner;
    }

    /**
     * A bag was just chosen by {@code chooser}. Only the leader's own choice
     * starts the tour, since the chest and the wall are described to the player
     * standing at them; a member who chooses later hears nothing and moves
     * nothing.
     */
    static void bagChosen(MinecraftServer server, InstanceRecord record, ServerPlayer chooser) {
        UUID leader = leaderOf(record);
        if (leader == null || !leader.equals(chooser.getUUID())) {
            return;
        }
        run(server, leader, Trigger.BAG_CHOSEN, List.of(chooser));
    }

    /** The leader's party just cleared a floor: say what is due to every online member. */
    static void floorCleared(MinecraftServer server, InstanceRecord record) {
        UUID leader = leaderOf(record);
        if (leader != null) {
            run(server, leader, Trigger.FLOOR_CLEARED, members(server, record));
        }
    }

    /**
     * The party just arrived home: the way-home step when it is due, then
     * {@link StationTutorial}'s nudge as the tail, so one tour covers both.
     */
    static void homeArrival(MinecraftServer server, InstanceRecord record) {
        UUID leader = leaderOf(record);
        if (leader != null) {
            run(server, leader, Trigger.HOME_ARRIVAL, members(server, record));
        }
        StationTutorial.nagMembers(server, record);
    }

    private static List<ServerPlayer> members(MinecraftServer server, InstanceRecord record) {
        List<ServerPlayer> online = new ArrayList<>();
        for (UUID member : record.members.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null) {
                online.add(player);
            }
        }
        return online;
    }

    /**
     * Says the due steps to {@code audience} and marks each done for the leader.
     * A step Lemon could not say because the player has her quiet is left undone
     * and comes back at the next trigger; the first line decides, since the rest
     * of a step follows it.
     */
    private static void run(MinecraftServer server, UUID leader, Trigger trigger, List<ServerPlayer> audience) {
        DungeonLog log = DungeonLog.forServer(server);
        for (Step step : due(log, leader, trigger)) {
            boolean said = false;
            for (ServerPlayer player : audience) {
                boolean first = true;
                for (String line : step.lines()) {
                    Lemon.Delivery delivery = Lemon.tour(player, line);
                    if (first) {
                        said |= delivery == Lemon.Delivery.SHOWN || delivery == Lemon.Delivery.DEFERRED_FIGHT;
                        first = false;
                    }
                }
            }
            if (said) {
                markDone(log, leader, step);
            }
        }
    }
}
