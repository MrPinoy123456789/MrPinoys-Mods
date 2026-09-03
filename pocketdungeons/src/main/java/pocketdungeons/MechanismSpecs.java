package pocketdungeons;

import java.util.List;

/**
 * M45 seam: the mechanism family's room templates.
 *
 * <p>Rooms whose situation is a mechanism: levers, signals, a door that wants
 * a circuit before it opens.
 *
 * <p>Empty until M51 fills it. It exists now so that milestone can add rooms
 * without opening {@link RoomTemplateGenerator}, which every other template
 * milestone would be opening at the same time.
 */
final class MechanismSpecs {

    private MechanismSpecs() {}

    /** The mechanism family's templates. Empty until M51. */
    static List<RoomSpec> list() {
        return List.of();
    }
}
