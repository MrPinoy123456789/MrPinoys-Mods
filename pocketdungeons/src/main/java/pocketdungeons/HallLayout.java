package pocketdungeons;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The Astrolabe Room's door row and door states (design pass 2026-10-09, Q1; PD-181), with no Minecraft
 * imports so it is testable with plain {@code javac}. The first staging room shows one act at a time: a
 * row of up to six 1 by 2 doors along its selector wall, one per dungeon of the act, on the pattern
 * {@code OXOXOXOOXOXOXO} (O a gap, X a door; spaces 1 to 14 of the wall's interior), with the 2 wide
 * doorway slot at spaces 7 and 8 left empty. Doors that belong to no act (the Endless Mine) stand at the
 * two ends of the same row.
 *
 * <p>The row fills from the middle outward, so an act with two or three dungeons sits in the middle
 * rather than at one end, and doors read left to right in order of the compass they need.
 */
final class HallLayout {

    private HallLayout() {}

    /** The interior spaces an act's doors may stand on, left to right: the {@code X}s of the pattern. */
    static final int[] DOOR_SPACES = {2, 4, 6, 9, 11, 13};
    /** Whether {@code along} (absolute, on a wall that mirrors or not) is a place a hall door can stand. */
    static boolean isHallAlong(boolean mirrored, int along) {
        for (int rel : DOOR_SPACES) {
            if ((mirrored ? 15 - rel : rel) == along) {
                return true;
            }
        }
        for (int rel : SPECIAL_SPACES) {
            if ((mirrored ? 15 - rel : rel) == along) {
                return true;
            }
        }
        return false;
    }

    /**
     * The places the DESCEND lever and its sign may stand beside a selected door (owner, 2026-10-09: they
     * appear only next to the door that is selected). Absolute positions along the wall; the set is the same
     * mirrored, and none is a door space, so the lever never stands in a door's place.
     */
    static final int[] LEVER_SPACES = {3, 5, 10, 12};

    /** Whether {@code along} is one of the places the lever may stand. */
    static boolean isLeverAlong(int along) {
        for (int space : LEVER_SPACES) {
            if (space == along) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where the lever stands for the door at {@code doorAlong}: the free lever place directly beside it
     * (the next one along first, then the one before), otherwise the nearest free lever place. {@code occupied}
     * holds the places doors stand on. Returns 0 only if no place is free.
     */
    static int leverAlongFor(int doorAlong, Set<Integer> occupied) {
        for (int beside : new int[]{doorAlong + 1, doorAlong - 1}) {
            if (isLeverAlong(beside) && !occupied.contains(beside)) {
                return beside;
            }
        }
        int best = 0;
        for (int space : LEVER_SPACES) {
            if (occupied.contains(space)) {
                continue;
            }
            if (best == 0 || Math.abs(space - doorAlong) < Math.abs(best - doorAlong)) {
                best = space;
            }
        }
        return best;
    }

    /** The order the spaces fill, middle outward. */
    static final int[] FILL_ORDER = {6, 9, 4, 11, 2, 13};
    /** The ends of the row, where doors that belong to no act stand, in the order they appear. */
    static final int[] SPECIAL_SPACES = {14, 1};
    /** The most dungeons one act can show. */
    static final int MAX_ACT_DOORS = DOOR_SPACES.length;
    /** The most special doors the row can show. */
    static final int MAX_SPECIALS = SPECIAL_SPACES.length;

    /** Whether a dungeon stands in an act's row or on its own. */
    static final String HALL_ACT = "act";
    static final String HALL_SPECIAL = "special";

    /** What a door looks like and does. */
    enum State {
        /** Not entered yet, or entered before: a lit copper bulb, the dungeon's own door. */
        OPEN,
        /** Finished before: an oxidized bulb, still lit and still enterable. */
        FINISHED,
        /** An act capstone whose act is not finished: an iron door and an unlit bulb. */
        CAPSTONE_LOCKED,
        /** A dungeon the compass has not reached: an iron door and an unlit bulb. */
        COMPASS_LOCKED
    }

    /** One door's state plus the two flags that decorate an open one. */
    record Status(State state, boolean startHere, boolean capstoneReady) {

        boolean locked() {
            return state == State.CAPSTONE_LOCKED || state == State.COMPASS_LOCKED;
        }
    }

    /** What the layout needs to know about one dungeon, so no dungeon definition is loaded here. */
    record Entry(String id, int unlockLevel, boolean capstone, boolean finished, boolean capstoneOpen) {}

    /**
     * The spaces for {@code actDoors} act doors followed by {@code specials} special doors, in door-slot
     * order: the act doors left to right, then the specials. Slot {@code i} (0-based) stands at
     * element {@code i}.
     */
    static int[] alongs(int actDoors, int specials) {
        int n = Math.max(0, Math.min(MAX_ACT_DOORS, actDoors));
        int s = Math.max(0, Math.min(MAX_SPECIALS, specials));
        int[] chosen = Arrays.copyOf(FILL_ORDER, n);
        Arrays.sort(chosen);
        int[] out = new int[n + s];
        System.arraycopy(chosen, 0, out, 0, n);
        for (int i = 0; i < s; i++) {
            out[n + i] = SPECIAL_SPACES[i];
        }
        return out;
    }

    /** The row's pattern for a layout, 14 characters of {@code O} and {@code X}, for tests and docs. */
    static String pattern(int actDoors, int specials) {
        char[] row = new char[14];
        Arrays.fill(row, 'O');
        for (int along : alongs(actDoors, specials)) {
            row[along - 1] = 'X';
        }
        return new String(row);
    }

    /** Orders an act's dungeons for the row: the lowest compass first, ties by id. */
    static List<Entry> ordered(List<Entry> act) {
        List<Entry> out = new ArrayList<>(act);
        out.sort(Comparator.comparingInt(Entry::unlockLevel).thenComparing(Entry::id));
        return out;
    }

    /**
     * The state of every door in {@code act} (already {@link #ordered}), in order. A capstone is locked until
     * its act says it is open ({@link Entry#capstoneOpen}); any other dungeon is locked until the compass
     * reaches its unlock level; a finished one wears patina. {@code startHere} marks the door to take next.
     */
    static List<Status> statuses(List<Entry> act, int compass) {
        int start = startHere(act, compass);
        List<Status> out = new ArrayList<>();
        for (int i = 0; i < act.size(); i++) {
            Entry e = act.get(i);
            State state;
            boolean ready = false;
            if (e.capstone() && !e.capstoneOpen()) {
                state = State.CAPSTONE_LOCKED;
            } else if (e.unlockLevel() > compass) {
                state = State.COMPASS_LOCKED;
            } else {
                state = e.finished() ? State.FINISHED : State.OPEN;
                ready = e.capstone() && !e.finished();
            }
            out.add(new Status(state, i == start, ready));
        }
        return out;
    }

    /**
     * The index of the door to take next: a ready capstone first, otherwise the lowest-compass unfinished
     * dungeon the compass has reached; -1 when everything in the act is finished or locked.
     */
    private static int startHere(List<Entry> act, int compass) {
        for (int i = 0; i < act.size(); i++) {
            Entry e = act.get(i);
            if (e.capstone() && e.capstoneOpen() && !e.finished() && e.unlockLevel() <= compass) {
                return i;
            }
        }
        for (int i = 0; i < act.size(); i++) {
            Entry e = act.get(i);
            if (!e.capstone() && !e.finished() && e.unlockLevel() <= compass) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The act the astrolabe shows after {@code current} among the open acts, wrapping; {@code backwards}
     * turns the other way. Returns {@code current} when it is the only open act.
     */
    static int nextAct(Set<Integer> unlocked, int current, boolean backwards) {
        List<Integer> acts = new ArrayList<>(unlocked);
        acts.sort(Comparator.naturalOrder());
        if (acts.isEmpty()) {
            return current;
        }
        int at = acts.indexOf(current);
        if (at < 0) {
            return acts.get(0);
        }
        int step = backwards ? acts.size() - 1 : 1;
        return acts.get((at + step) % acts.size());
    }

    /**
     * The act the room opens on: the act holding the door to take next, which is the highest open act with
     * something unfinished; the highest open act when everything is finished.
     */
    static int openingAct(Set<Integer> unlocked, java.util.function.IntPredicate hasUnfinished) {
        int best = -1;
        int highest = -1;
        for (int act : unlocked) {
            highest = Math.max(highest, act);
            if (hasUnfinished.test(act)) {
                best = Math.max(best, act);
            }
        }
        return best > 0 ? best : Math.max(1, highest);
    }
}
