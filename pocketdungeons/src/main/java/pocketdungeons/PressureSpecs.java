package pocketdungeons;

import java.util.List;

/**
 * M45 seam: the pressure family's room templates.
 *
 * <p>Rooms that apply pressure: a clock, a rising hazard, a crowd that grows
 * while the party stands still.
 *
 * <p>Empty until M53 fills it. It exists now so that milestone can add rooms
 * without opening {@link RoomTemplateGenerator}, which every other template
 * milestone would be opening at the same time.
 */
final class PressureSpecs {

    private PressureSpecs() {}

    /** The pressure family's templates. Empty until M53. */
    static List<RoomSpec> list() {
        return List.of();
    }
}
