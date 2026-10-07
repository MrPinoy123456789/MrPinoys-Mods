package pocketdungeons;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The staging room's dungeon map as plain text lines (design D6): the layers of
 * the dungeon the trip is in, the node the party stands at, the final floor,
 * each edge's scrap cost, and which floors each door can still reach. Pure
 * strings with a {@link Tone} per line; {@code DialogScreens} turns them into
 * a dialog. No Minecraft imports, so {@code DungeonMapTextTest} pins the shape.
 *
 * <p>Steps appear only on the doors the party is choosing between right now.
 * The steps of later floors are rolled on arrival and never shown (D6).
 */
final class DungeonMapText {

    private DungeonMapText() {}

    /** How a line is coloured; the dialog maps each to a chat colour. */
    enum Tone { TITLE, HEADING, CURRENT, VISITED, FINAL, SIDE, NORMAL, NOTE }

    record Line(String text, Tone tone) {}

    /**
     * The map for a trip in progress.
     *
     * @param current the node the party last entered (its floor is cleared or in progress)
     * @param path    the node ids the trip has taken, in order, current last
     * @param doors   the dealt doors out of {@code current}, or empty when the dungeon is finished
     * @param finished whether the final floor has been cleared
     */
    static List<Line> lines(DungeonDef def, String current, List<String> path, TripDoors.Door[] doors,
                            boolean finished) {
        List<Line> out = new ArrayList<>();
        out.add(new Line(def.name().toUpperCase(), Tone.TITLE));
        out.add(new Line("Act " + def.act() + ", " + def.layers() + " layers. "
                + "You are here: >> <<. Visited: *. Some doors cost scrap.", Tone.NOTE));

        Set<String> ahead = TripDoors.reachableFrom(def, current);
        for (int layer = 1; layer <= def.layers(); layer++) {
            out.add(new Line("Layer " + layer, Tone.HEADING));
            for (DungeonDef.Node node : def.nodes()) {
                if (node.layer() != layer) {
                    continue;
                }
                boolean here = node.id().equals(current);
                boolean visited = path.contains(node.id());
                StringBuilder text = new StringBuilder();
                Tone tone = Tone.NORMAL;
                if (here) {
                    text.append(">> ").append(node.name()).append(" <<");
                    tone = Tone.CURRENT;
                } else if (visited) {
                    text.append("* ").append(node.name());
                    tone = Tone.VISITED;
                } else {
                    text.append(node.name());
                }
                if (node.isFinal()) {
                    text.append(" (FINAL FLOOR)");
                    if (!here && !visited) {
                        tone = Tone.FINAL;
                    }
                }
                if (!visited && !here && node.layer() > layerOf(def, current) && !ahead.contains(node.id())) {
                    text.append(" (out of reach now)");
                }
                out.add(new Line(text.toString(), tone));
                for (DungeonDef.Edge edge : def.edgesFrom(node.id())) {
                    DungeonDef.Node to = def.node(edge.to());
                    String name = to == null ? edge.to() : to.name();
                    if (edge.free()) {
                        out.add(new Line("    to " + name, Tone.NORMAL));
                    } else {
                        out.add(new Line("    to " + name + " (side branch: " + scrap(edge.cost()) + ")",
                                Tone.SIDE));
                    }
                }
            }
        }

        if (finished) {
            out.add(new Line("The dungeon is cleared. Pull the HOME lever.", Tone.CURRENT));
        } else if (doors.length > 0) {
            out.add(new Line("Doors from here", Tone.HEADING));
            for (int slot = 0; slot < doors.length; slot++) {
                TripDoors.Door door = doors[slot];
                DungeonDef.Node node = def.node(door.nodeId());
                String name = node == null ? door.nodeId() : node.name();
                StringBuilder text = new StringBuilder("Door ").append(slot + 1).append(": ").append(name)
                        .append(", ").append(stepWord(door.step()));
                if (door.sideBranch()) {
                    text.append(", side branch: ").append(scrap(door.cost()));
                }
                out.add(new Line(text.toString(), door.sideBranch() ? Tone.SIDE : Tone.NORMAL));
                List<String> reach = new ArrayList<>();
                for (String id : TripDoors.reachableFrom(def, door.nodeId())) {
                    DungeonDef.Node reachable = def.node(id);
                    if (reachable != null && !id.equals(door.nodeId())) {
                        reach.add(reachable.name());
                    }
                }
                out.add(new Line(reach.isEmpty() ? "    ends the dungeon"
                        : "    can reach: " + String.join(", ", reach), Tone.NOTE));
            }
        }
        return out;
    }

    /**
     * The map before a dungeon is chosen: each door offers a dungeon. {@code
     * lookup} resolves a dungeon id to its definition. {@code locked} lists
     * the open act's dungeons the leader's compass has not reached (D23),
     * each with its unlock level.
     */
    static List<Line> firstLines(TripDoors.Door[] doors, Function<String, DungeonDef> lookup) {
        return firstLines(doors, lookup, List.of());
    }

    static List<Line> firstLines(TripDoors.Door[] doors, Function<String, DungeonDef> lookup,
                                 List<DungeonDef> locked) {
        List<Line> out = new ArrayList<>();
        out.add(new Line("CHOOSE A DUNGEON", Tone.TITLE));
        out.add(new Line("The door you take picks the dungeon. Going home early banks what you cleared.",
                Tone.NOTE));
        for (int slot = 0; slot < doors.length; slot++) {
            TripDoors.Door door = doors[slot];
            DungeonDef def = lookup.apply(door.dungeonId());
            if (def == null) {
                continue;
            }
            DungeonDef.Node entry = def.node(door.nodeId());
            out.add(new Line("Door " + (slot + 1) + ": " + def.name() + ", " + stepWord(door.step()), Tone.HEADING));
            out.add(new Line("    Act " + def.act() + ", " + def.layers() + " layers, opens at "
                    + (entry == null ? door.nodeId() : entry.name()), Tone.NORMAL));
            List<String> finals = new ArrayList<>();
            for (DungeonDef.Node node : def.finals()) {
                finals.add(node.name());
            }
            if (!finals.isEmpty()) {
                out.add(new Line("    ends at " + String.join(", ", finals), Tone.FINAL));
            }
        }
        if (!locked.isEmpty()) {
            out.add(new Line("Locked", Tone.HEADING));
            for (DungeonDef def : locked) {
                out.add(new Line("    " + def.name() + " · compass " + def.unlockLevel(), Tone.NOTE));
            }
        }
        return out;
    }

    /**
     * The act checklist for the first staging map (D29): each dungeon of
     * {@code act} with a tick when finished or its unlock level when locked,
     * the Endless Mine's depth against the act's target, then nothing for a
     * complete act. Sorted by dungeon id so the order is stable.
     */
    static List<Line> actChecklist(int act, java.util.Collection<DungeonDef> all, Set<String> finished,
                                 int compass, int deepestMineFloor) {
        List<Line> out = new ArrayList<>();
        Set<String> done = new java.util.LinkedHashSet<>();
        for (String id : finished) {
            done.add(DungeonDef.qualify(id));
        }
        List<DungeonDef> dungeons = new ArrayList<>(ActProgress.actDungeons(act, all));
        dungeons.sort(java.util.Comparator.comparing(DungeonDef::id));
        if (dungeons.isEmpty()) {
            return out;
        }
        out.add(new Line("Act " + act + " checklist", Tone.HEADING));
        for (DungeonDef def : dungeons) {
            if (done.contains(DungeonDef.qualify(def.id()))) {
                out.add(new Line("    " + def.name() + " (done)", Tone.VISITED));
            } else if (def.unlockLevel() > compass) {
                out.add(new Line("    " + def.name() + " · compass " + def.unlockLevel(), Tone.NOTE));
            } else {
                out.add(new Line("    " + def.name(), Tone.NORMAL));
            }
        }
        int target = ActProgress.mineTarget(act);
        if (target > 0) {
            out.add(new Line("    Endless Mine · floor " + Math.min(deepestMineFloor, target)
                    + " of " + target + (deepestMineFloor >= target ? " (done)" : ""), Tone.NOTE));
        }
        return out;
    }

    private static int layerOf(DungeonDef def, String nodeId) {
        DungeonDef.Node node = def.node(nodeId);
        return node == null ? 0 : node.layer();
    }

    /** {@code "+2 scrap"}; {@code "+0 scrap"} only for a door dealt no step, which no deal produces now. */
    static String stepWord(int step) {
        return step <= 0 ? "+0 scrap" : "+" + step + " scrap";
    }

    static String scrap(int cost) {
        return cost + " scrap";
    }
}
