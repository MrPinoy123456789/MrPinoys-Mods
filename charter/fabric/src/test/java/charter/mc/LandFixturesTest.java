package charter.mc;

import charter.mc.land.Fixtures;
import charter.mc.store.CharterStore;
import charter.mc.store.Persistence;
import charter.state.CharterState;

import java.nio.file.Files;
import java.nio.file.Path;

import static charter.mc.FabricSuite.check;

/**
 * M8's saved block: where a keep's nexus and a camp's banner and signal fire stand
 * survives a restart, comes back in a stable order, and a fixture whose core is gone
 * is dropped by repair rather than kept pointing at nothing.
 */
final class LandFixturesTest {

    private LandFixturesTest() {
    }

    static void run() throws Exception {
        Persistence.register();
        Fixtures.registerBlock();
        fixturesSurviveASave();
        aFixtureOfAGoneCoreIsDropped();
    }

    private static CharterStore store(Path dir) {
        return new CharterStore(dir, Persistence.codec(Persistence.json()), s -> { }, s -> { });
    }

    static void fixturesSurviveASave() throws Exception {
        FullState f = FullState.build();
        Fixtures live = Fixtures.of(f.state);
        live.add(new Fixtures.Fixture(Fixtures.Kind.NEXUS, FullState.OW, 8, 70, 8, f.keep.id));
        live.add(new Fixtures.Fixture(Fixtures.Kind.BANNER, FullState.OW, 100, 64, 8, f.camp.id));
        live.add(new Fixtures.Fixture(Fixtures.Kind.CAMPFIRE, FullState.OW, 101, 65, 8, f.camp.id));
        Path dir = Files.createTempDirectory("charter-fixtures");
        store(dir).save(f.state, f.clock.now());
        String first = Files.readString(dir.resolve("state.json"));

        CharterStore.Loaded loaded = store(dir).load(f.knobs, f.clock.now());
        CharterState back = loaded.state();
        Fixtures read = Fixtures.of(back);
        check("fixtures: all three read back", read.size() == 3);
        check("fixtures: the nexus keeps its position", read.at(FullState.OW, 8, 70, 8) != null
                && read.at(FullState.OW, 8, 70, 8).kind() == Fixtures.Kind.NEXUS);
        check("fixtures: the camp's banner and fire stay with the camp", read.ofCore(f.camp.id).size() == 2);
        check("fixtures: nothing needed repair", loaded.repairs().count() == 0);

        store(dir).save(back, f.clock.now());
        check("fixtures: the same state writes the same snapshot", first.equals(Files.readString(dir.resolve("state.json"))));
    }

    static void aFixtureOfAGoneCoreIsDropped() throws Exception {
        FullState g = FullState.build();
        Fixtures.of(g.state).add(new Fixtures.Fixture(Fixtures.Kind.NEXUS, FullState.OW, 8, 70, 8, g.keep.id));
        Path dir = Files.createTempDirectory("charter-fixtures-gone");
        store(dir).save(g.state, g.clock.now());
        // Point the fixture at a core that does not exist, as a hand edited or half restored file would.
        String text = Files.readString(dir.resolve("state.json"));
        int at = text.indexOf("land_fixtures");
        check("fixtures: the saved file names the block", at > 0);
        String broken = text.substring(0, at) + text.substring(at).replace(g.keep.id.value().toString(), "00000000-0000-0000-0000-000000000001");
        Files.writeString(dir.resolve("state.json"), broken);

        CharterStore.Loaded loaded = store(dir).load(g.knobs, g.clock.now());
        check("fixtures: a fixture pointing at a gone core is dropped", Fixtures.of(loaded.state()).size() == 0);
        check("fixtures: the drop is recorded as a repair", loaded.repairs().count() >= 1);
    }
}
