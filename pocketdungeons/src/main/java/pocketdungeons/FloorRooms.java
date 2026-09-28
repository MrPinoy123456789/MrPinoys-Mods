package pocketdungeons;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A floor's cell to room map, built once from the committed plan's placed
 * rooms and kept on {@link FloorState#rooms}, so any position on the floor
 * resolves to a room id. The rooms around a floor that are not part of its
 * plan answer with named pseudo-rooms: {@link #SAFE_ROOM}, {@link #STAGING}
 * and {@link #PREVIEW}.
 *
 * <p>A room id is the placed room's manifest name ({@code pocketdungeons:hold_the_plate}).
 * Two cells can place the same room; the {@code cell} key tells them apart.
 */
final class FloorRooms {

    static final String SAFE_ROOM = "safe_room";
    static final String STAGING = "staging_room";
    static final String PREVIEW = "preview";

    /** One placed room: its manifest name, its plan role, and the cell it stands in. */
    record Room(String id, String role, PlanCell cell) {
        /** The cell as the journal writes it: {@code "x,z"}. */
        String cellKey() {
            return cell.x() + "," + cell.z();
        }
    }

    private FloorRooms() {}

    /**
     * The map for a committed plan, ordered along the plan: the critical path
     * from the entrance first, then every other cell by position, so a
     * listing reads in the order a party walks it.
     */
    static Map<PlanCell, Room> of(DungeonPlan plan) {
        Map<PlanCell, Room> out = new LinkedHashMap<>();
        if (plan == null) {
            return out;
        }
        List<PlanCell> order = new ArrayList<>();
        if (plan.criticalPath() != null) {
            order.addAll(plan.criticalPath());
        }
        List<PlanCell> rest = new ArrayList<>(plan.cells());
        rest.removeAll(order);
        rest.sort(Comparator.comparingInt(PlanCell::x).thenComparingInt(PlanCell::z));
        order.addAll(rest);
        for (PlanCell cell : order) {
            DungeonPlan.PlacedRoom placed = plan.rooms().get(cell);
            if (placed == null || out.containsKey(cell)) {
                continue;
            }
            String role = plan.roles() == null ? null : plan.roles().get(cell);
            out.put(cell, new Room(placed.name(), role == null ? "" : role, cell));
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * The room at {@code pos} in this record's instance: a pseudo-room for the
     * safe room, the staging room and a door preview, otherwise the plan room
     * of the floor cell under it, or {@code ""} for anywhere else (a liminal
     * cell left behind, or outside the instance).
     */
    static String roomAt(InstanceRecord record, BlockPos pos) {
        Room room = placedAt(record, pos);
        if (room != null) {
            return room.id();
        }
        if (record == null) {
            return "";
        }
        if (inCell(pos, record.roomCellOrigin)) {
            return SAFE_ROOM;
        }
        if (inCell(pos, record.stagingCellOrigin)) {
            return STAGING;
        }
        if (inCell(pos, record.floor.previewCellOrigin)) {
            return PREVIEW;
        }
        return "";
    }

    /**
     * The plan room of the floor cell under {@code pos}, or null. The safe
     * room, staging room and preview cell are never plan rooms, even when a
     * lobby layout's geometry covers them.
     */
    static Room placedAt(InstanceRecord record, BlockPos pos) {
        if (record == null || record.layout == null || record.layout.geometry() == null
                || record.floor.rooms.isEmpty()) {
            return null;
        }
        if (inCell(pos, record.roomCellOrigin) || inCell(pos, record.stagingCellOrigin)
                || inCell(pos, record.floor.previewCellOrigin)) {
            return null;
        }
        PlanCell cell = record.layout.geometry().cellAt(pos);
        return cell == null ? null : record.floor.rooms.get(cell);
    }

    private static boolean inCell(BlockPos pos, BlockPos cellOrigin) {
        return cellOrigin != null
                && pos.getX() >= cellOrigin.getX() && pos.getX() < cellOrigin.getX() + RoomGeometry.CELL
                && pos.getZ() >= cellOrigin.getZ() && pos.getZ() < cellOrigin.getZ() + RoomGeometry.CELL;
    }
}
