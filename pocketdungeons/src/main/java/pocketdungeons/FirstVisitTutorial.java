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

    /**
     * What just happened, which decides which steps may fire. The Act 0 triggers (decision 2026-10-17) carry the
     * general lessons of the dungeons; none of them gives a per-floor gather instruction.
     */
    enum Trigger {
        /** A brand new player took the Compass from their first chest and closed it. */
        COMPASS_TAKEN,
        BAG_CHOSEN,
        /** The first floor of Act 0 began. */
        PROLOGUE_FIRST_FLOOR,
        FLOOR_CLEARED,
        /** The third floor of Act 0 was cleared. */
        PROLOGUE_THIRD_CLEARED,
        /** The floor before Act 0's last was cleared. */
        PROLOGUE_BEFORE_LAST,
        /** Act 0 banked its scrap. */
        SCRAP_BANKED,
        /** The first diary arrived. */
        DIARY_FOUND,
        /** The first floor of a dungeon after Act 0 began. */
        FIRST_REAL_FLOOR,
        HOME_ARRIVAL
    }

    enum Step {
        INTRO("intro", Trigger.COMPASS_TAKEN) {
            List<String> lines() {
                return List.of(
                        "There you are. I am Lemon, Alex's Allay.",
                        "I lost Alex somewhere in the pocket dungeons, and I came out of the compass to look for her.",
                        "Stay with me and I will show you how the dungeons work.");
            }
        },
        BAG_CHEST("bag_chest", Trigger.BAG_CHOSEN) {
            List<String> lines() {
                return List.of(
                        "Someone left equipment behind in that chest. It is in your pack now.",
                        "It is only the basics. Anything better, you gather or find.");
            }
        },
        PAD("pad", Trigger.PROLOGUE_FIRST_FLOOR) {
            List<String> lines() {
                return List.of("Every floor ends on a pad. Stand on it and the floor is done.");
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
                return List.of("Any ender chest in the dungeon is your Dungeon Storage: "
                        + "27 extra slots that stay yours between runs, so you can leave spare things there.");
            }
        },
        SET_OF_THREE("set_of_three", Trigger.FLOOR_CLEARED) {
            List<String> lines() {
                return List.of("Every dungeon has its own name and a last floor. Finish it and you are paid emeralds "
                        + "and a vault. Go home sooner and you keep the charts and the chests, but not the finish.",
                        "The terminal's MAP tab shows the dungeon: where you are and the last floor. "
                        + "/dungeon map adds which branches cost a life.");
            }
        },
        FURNACE("furnace", Trigger.PROLOGUE_THIRD_CLEARED) {
            List<String> lines() {
                return List.of("A furnace smelts. Put ore in the top and fuel underneath, and iron becomes ingots.",
                        "Tonight asks nothing of it, but later dungeons will.");
            }
        },
        TRIAL_SPAWNER("trial_spawner", Trigger.PROLOGUE_BEFORE_LAST) {
            List<String> lines() {
                return List.of("The last floor is a trial spawner. It sends out waves while you stand near it.",
                        "Beat every wave and the dungeon is finished.");
            }
        },
        SCRAP("scrap", Trigger.SCRAP_BANKED) {
            List<String> lines() {
                return List.of("That is scrap. Dungeons pay it, and banking it raises your compass.",
                        "Two scrap bought you Compass 2. A higher compass opens the next dungeons.",
                        "Act 1 is next. You will have lives there, and I will explain them when you do.");
            }
        },
        DIARY("diary", Trigger.DIARY_FOUND) {
            List<String> lines() {
                return List.of("That is a page of Alex's diary.",
                        "I am looking for Alex, and I need her diaries to find her. Keep every page you find.");
            }
        },
        LIVES("lives", Trigger.FIRST_REAL_FLOOR) {
            List<String> lines() {
                return List.of("You have lives now: the party shares five. A death costs one.",
                        "Lose the last and the dungeon fails. Your lives show on the sidebar.");
            }
        },
        WAY_HOME("way_home", Trigger.HOME_ARRIVAL) {
            List<String> lines() {
                return List.of("This is home. The lever at the end of a floor is the way here: it banks your charts "
                        + "and brings you back to your own room.",
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

    /**
     * The leader's party just cleared a floor: say what is due to every online member. {@code floorsCleared} is
     * how many floors the trip has cleared, which Act 0 uses to time its general lessons (the furnace, the
     * trial spawner), never to give a gather instruction.
     */
    static void floorCleared(MinecraftServer server, InstanceRecord record, int floorsCleared) {
        UUID leader = leaderOf(record);
        if (leader == null) {
            return;
        }
        List<ServerPlayer> audience = members(server, record);
        run(server, leader, Trigger.FLOOR_CLEARED, audience);
        if (RunLifecycle.isPrologue(record)) {
            Trigger special = prologueTrigger(floorsCleared);
            if (special != null) {
                run(server, leader, special, audience);
            }
        }
    }

    /** The Act 0 trigger for the floor just cleared, or null when that floor teaches nothing extra. */
    static Trigger prologueTrigger(int floorsCleared) {
        return switch (floorsCleared) {
            case 3 -> Trigger.PROLOGUE_THIRD_CLEARED;
            case 4 -> Trigger.PROLOGUE_BEFORE_LAST;
            default -> null;
        };
    }

    /**
     * A floor just started. The first floor of Act 0 explains the pad; the first floor of any later dungeon
     * explains lives, which Act 0 does not have (decision 2026-10-17).
     */
    static void floorStarted(MinecraftServer server, InstanceRecord record) {
        UUID leader = leaderOf(record);
        if (leader == null) {
            return;
        }
        run(server, leader, RunLifecycle.isPrologue(record) ? Trigger.PROLOGUE_FIRST_FLOOR : Trigger.FIRST_REAL_FLOOR,
                members(server, record));
    }

    /** A brand new player took the Compass from their first chest and closed it: Lemon comes out and says hello. */
    static void compassTaken(MinecraftServer server, ServerPlayer player) {
        run(server, player.getUUID(), Trigger.COMPASS_TAKEN, List.of(player));
    }

    /** Act 0 just banked its scrap for {@code player}: Lemon says what scrap is. Heard once per player. */
    static void scrapBanked(MinecraftServer server, ServerPlayer player) {
        run(server, player.getUUID(), Trigger.SCRAP_BANKED, List.of(player));
    }

    /** {@code player}'s first diary just arrived: Lemon says she is looking for Alex. Heard once per player. */
    static void diaryFound(MinecraftServer server, ServerPlayer player) {
        run(server, player.getUUID(), Trigger.DIARY_FOUND, List.of(player));
    }

    /**
     * Watches for the first chest. A player who has not finished Act 0 and has never heard the introduction,
     * and who closes a container with a compass on them after having one open, is the player who took it.
     * The chest itself is the world's: this only notices the close.
     */
    static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 10 != 0) {
                return;
            }
            DungeonLog log = DungeonLog.forServer(server);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (DungeonWorldRules.inDungeon(player.level())
                        || log.taskProgress(player.getUUID(), Step.INTRO.key()) >= 1
                        || DungeonProgress.prologueDone(log, player.getUUID())) {
                    chestWatch.remove(player.getUUID());
                    continue;
                }
                boolean open = player.containerMenu != player.inventoryMenu;
                if (open) {
                    chestWatch.add(player.getUUID());
                } else if (chestWatch.contains(player.getUUID()) && Keystone.findHeld(player) != null) {
                    chestWatch.remove(player.getUUID());
                    compassTaken(server, player);
                }
            }
        });
    }

    /** Players who have a container open and have not yet been greeted; a close with a compass in hand fires the intro. */
    private static final java.util.Set<UUID> chestWatch = new java.util.HashSet<>();

    /**
     * The party just arrived home: the way-home step when it is due, the
     * vendor's gear re-roll (J5a), then {@link StationTutorial}'s nudge as
     * the tail, so one tour covers all of it.
     */
    static void homeArrival(MinecraftServer server, InstanceRecord record) {
        UUID leader = leaderOf(record);
        if (leader != null) {
            run(server, leader, Trigger.HOME_ARRIVAL, members(server, record));
        }
        LibrarianNPC.restock(server, record);
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
