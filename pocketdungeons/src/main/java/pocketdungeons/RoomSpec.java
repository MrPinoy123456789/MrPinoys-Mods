package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * One authored room template: its name, the walls that carry a door, and the
 * furniture {@code RoomTemplateGenerator.buildAndQueue} places into the shell
 * before capturing it.
 *
 * <p>M45 lifted this out of {@code RoomTemplateGenerator}, where it was a
 * private inner class, so the situation families can each author their own
 * spec list in their own file. Behaviour is unchanged: same fields, same
 * builder methods, same defaults.
 */
final class RoomSpec {
    final String name;
    final Set<Direction> doors;
    List<BlockPos> chests = List.of();
    List<BlockPos> spawns = List.of();
    BlockPos spawner;
    boolean exitPad;
    BiConsumer<ServerLevel, BlockPos> decor;
    RoomBuilder.ShellPalette shellPalette;
    /**
     * M61: the room's vertical span in cell-sized volumes. Defaults to 1 (no
     * lower story). {@code 2} means the generator captures the cell plus the
     * 16 x 16 x 9 volume directly beneath it. See {@link DungeonRoomMeta#spanY}
     * and spec 13.
     */
    int spanY = 1;
    /**
     * Vertical floors phase 2: the doors of the story below the cell (a stair room's way on). Empty for every
     * other room. Only meaningful with {@code spanY >= 2}.
     */
    Set<Direction> lowerDoors = Set.of();

    RoomSpec(String name, Set<Direction> doors) {
        this.name = name;
        this.doors = doors;
    }

    RoomSpec chests(BlockPos... positions) {
        this.chests = List.of(positions);
        return this;
    }

    RoomSpec spawns(BlockPos... positions) {
        this.spawns = List.of(positions);
        return this;
    }

    RoomSpec spawner(BlockPos position) {
        this.spawner = position;
        return this;
    }

    RoomSpec exitPad() {
        this.exitPad = true;
        return this;
    }

    RoomSpec decor(BiConsumer<ServerLevel, BlockPos> decor) {
        this.decor = decor;
        return this;
    }

    RoomSpec palette(RoomBuilder.ShellPalette palette) {
        this.shellPalette = palette;
        return this;
    }

    /**
     * M61: declares this room's vertical span. {@code 1} is the default and
     * changes nothing; {@code 2} makes the generator capture the cell plus the
     * volume beneath it. The room JSON is written alongside the template by
     * {@link RoomTemplateGenerator}.
     */
    RoomSpec spanY(int spanY) {
        this.spanY = Math.clamp(spanY, 1, RoomGeometry.MAX_SPAN_Y);
        return this;
    }

    /**
     * Vertical floors phase 2: puts door jigsaws on the lower story's walls too. The room is a stair room: a
     * two story room whose top story holds the way in ({@link #doors}) and whose lower story holds the way on.
     */
    RoomSpec lowerDoors(Direction... walls) {
        this.lowerDoors = Set.of(walls);
        return this;
    }
}
