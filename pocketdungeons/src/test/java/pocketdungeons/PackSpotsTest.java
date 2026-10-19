package pocketdungeons;

import java.util.List;

/**
 * Pure-JDK regression for {@link PackSpots}: the door lanes are never a pot spot, and the fallback spots
 * beside a door come nearest first (decision 2026-10-18, rule 2).
 */
public class PackSpotsTest {

    public static void main(String[] args) {
        testLanes();
        testCandidates();
        System.out.println("PackSpotsTest passed");
    }

    private static void testLanes() {
        for (int lz = 0; lz < PackSpots.LANE_DEPTH; lz++) {
            for (int lx = 6; lx <= 9; lx++) {
                check(PackSpots.inLane(lx, lz), "north lane (" + lx + "," + lz + ")");
                check(PackSpots.inLane(lx, 15 - lz), "south lane (" + lx + "," + (15 - lz) + ")");
                check(PackSpots.inLane(lz, lx), "west lane (" + lz + "," + lx + ")");
                check(PackSpots.inLane(15 - lz, lx), "east lane (" + (15 - lz) + "," + lx + ")");
            }
        }
        check(!PackSpots.inLane(5, 1), "beside the lane is free");
        check(!PackSpots.inLane(10, 2), "the other side of the lane is free");
        check(!PackSpots.inLane(7, 4), "past the lane's depth is free");
        check(!PackSpots.inLane(8, 8), "the middle of the room is free");
        check(!PackSpots.inLane(2, 2), "a corner is free");
    }

    private static void testCandidates() {
        List<int[]> near = PackSpots.candidatesNearDoor(7, 1);
        check(!near.isEmpty(), "there are candidates");
        for (int[] c : near) {
            check(!PackSpots.inLane(c[0], c[1]), "no candidate is in a lane: " + c[0] + "," + c[1]);
            check(c[0] >= 1 && c[0] <= 14 && c[1] >= 1 && c[1] <= 14, "candidates are interior");
        }
        int[] first = near.get(0);
        check(first[1] <= 3, "the nearest door is the north one, so the first spot hugs its lane: " + first[1]);
        check(first[0] == 5 || first[0] == 10, "and stands just beside the lane: " + first[0]);
        int[] far = near.get(near.size() - 1);
        check(far[1] >= 8, "the last spot is far from the north door");
        List<int[]> south = PackSpots.candidatesNearDoor(8, 14);
        check(south.get(0)[1] >= 12, "a fall by the south door gets a spot beside the south door");
        List<int[]> clamped = PackSpots.candidatesNearDoor(-20, 99);
        check(!clamped.isEmpty(), "a point outside the cell is clamped, not an error");
        List<int[]> again = PackSpots.candidatesNearDoor(7, 1);
        for (int i = 0; i < near.size(); i++) {
            check(near.get(i)[0] == again.get(i)[0] && near.get(i)[1] == again.get(i)[1], "the order is stable");
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
