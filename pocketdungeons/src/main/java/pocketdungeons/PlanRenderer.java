package pocketdungeons;

import java.util.Map;
import java.util.Set;

/**
 * ASCII renderer for resolved dungeon plans and resolution failures. No
 * Minecraft imports.
 */
final class PlanRenderer {

    private PlanRenderer() {}

    static String renderAscii(DungeonPlan plan) {
        return render(plan.cells(), plan.roles(), plan.doors(), null);
    }

    static String renderFailure(DungeonShape shape, RoomSelector.Failure failure) {
        StringBuilder sb = new StringBuilder();
        sb.append(render(shape.cells(), shape.roles(), shape.openEdges(), failure.cell()));
        sb.append("\nUnresolvable cell ").append(failure.cell());
        sb.append(" requires mask ").append(DoorMask.toLetters(failure.mask()));
        if (!"missing role".equals(failure.role())) {
            sb.append(" and role ").append(failure.role());
        } else {
            sb.append(" (").append(failure.role()).append(")");
        }
        return sb.toString();
    }

    private static String render(Set<PlanCell> cells, Map<PlanCell, String> roles,
                                 Set<PlanEdge> doors, PlanCell mark) {
        if (cells.isEmpty()) {
            return "(empty shape)";
        }

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (PlanCell c : cells) {
            if (c.x() < minX) minX = c.x();
            if (c.x() > maxX) maxX = c.x();
            if (c.z() < minZ) minZ = c.z();
            if (c.z() > maxZ) maxZ = c.z();
        }

        StringBuilder out = new StringBuilder();
        for (int z = minZ; z <= maxZ; z++) {
            StringBuilder cellLine = new StringBuilder();
            StringBuilder connLine = new StringBuilder();
            for (int x = minX; x <= maxX; x++) {
                PlanCell c = new PlanCell(x, z);
                if (c.equals(mark)) {
                    cellLine.append('?');
                } else if (cells.contains(c)) {
                    cellLine.append(initial(roles.get(c)));
                } else {
                    cellLine.append('.');
                }

                if (x < maxX) {
                    PlanCell right = new PlanCell(x + 1, z);
                    cellLine.append(hasDoor(doors, c, right) ? '-' : ' ');
                }
                if (z < maxZ) {
                    PlanCell down = new PlanCell(x, z + 1);
                    connLine.append(hasDoor(doors, c, down) ? '|' : ' ');
                    if (x < maxX) {
                        connLine.append(' ');
                    }
                }
            }
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(cellLine);
            if (z < maxZ) {
                out.append('\n').append(connLine);
            }
        }
        return out.toString();
    }

    private static char initial(String role) {
        return switch (role == null ? "" : role) {
            case "entrance" -> 'e';
            case "encounter" -> 'n';
            case "loot" -> 'l';
            case "corridor" -> 'c';
            case "exit" -> 'x';
            default -> '?';
        };
    }

    private static boolean hasDoor(Set<PlanEdge> doors, PlanCell a, PlanCell b) {
        return doors.contains(new PlanEdge(a, b));
    }
}
