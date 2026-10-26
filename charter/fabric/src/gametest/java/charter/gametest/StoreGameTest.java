package charter.gametest;

import charter.engine.Actor;
import charter.gov.Preset;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Mail;
import charter.mc.store.CharterStore;
import charter.mc.store.Persistence;
import charter.org.actions.Found;
import charter.state.CharterState;
import charter.treasury.Currency;
import charter.treasury.actions.Deposit;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import java.nio.file.Files;
import java.util.UUID;

/**
 * Test H, gametest half: the live server's {@code CharterStore} writes the running
 * world's state into the world folder, and a second store over the same folder
 * reads back the same thing.
 */
public final class StoreGameTest {

    @GameTest
    public void saveAndReloadThroughTheRealStore(GameTestHelper helper) {
        helper.assertTrue(CharterServer.running(), "Charter did not open a state when the server started");
        CharterRuntime rt = CharterServer.runtime();

        PlayerId founder = new PlayerId(UUID.randomUUID());
        rt.state.touch(founder, "Storekeeper", rt.clock.now());
        String name = "Store " + Long.toString(founder.value().getLeastSignificantBits() & 0xFFFFFF, 36);
        helper.assertTrue(rt.engine.run(new Actor.Admin("gametest"), new Found(founder, name, Preset.LORDSHIP)).done(),
                "Could not found an organization through the live engine");
        OrgId org = rt.state.orgNamed(name).orElseThrow().id;
        rt.engine.run(new Actor.Admin("gametest"), new Deposit(org, Currency.DIAMOND, 7));
        rt.mail.post(founder, rt.clock.now(), "a waiting message");
        rt.save();

        helper.assertTrue(Files.exists(rt.store.stateFile()), "No state.json in " + rt.store.dir());
        helper.assertTrue(rt.store.dir().equals(helper.getLevel().getServer().getWorldPath(
                net.minecraft.world.level.storage.LevelResource.ROOT).resolve("charter")), "The store is not in the world folder");

        Mail mail = new Mail(20);
        CharterStore second = new CharterStore(CharterServer.charterDir(helper.getLevel().getServer()),
                Persistence.codec(rt.json, mail.block()), s -> { }, s -> { });
        CharterStore.Loaded loaded = second.load(rt.knobs, rt.clock.now());
        CharterState back = loaded.state();

        helper.assertTrue(loaded.source() == CharterStore.Source.STATE, "The reload did not come from state.json");
        helper.assertTrue(back.org(org).map(o -> o.name().equals(name)).orElse(false), "The organization did not reload");
        helper.assertTrue(back.org(org).orElseThrow().ledger.balance(Currency.DIAMOND) == 7, "The treasury did not reload");
        helper.assertTrue(back.officeNamed(org, "Lord").map(o -> founder.equals(o.holder())).orElse(false),
                "The Lord office did not reload with its holder");
        helper.assertTrue(back.grants().size() == rt.state.grants().size(), "Grants did not all reload");
        helper.assertTrue(back.siegeClock.now() == rt.state.siegeClock.now(), "The siege clock did not reload");
        helper.assertTrue(!mail.peek(founder).isEmpty(), "Offline messages did not reload");
        String reloaded = rt.json.gson.toJson(second.codec().write(back));
        String live = rt.json.gson.toJson(rt.store.codec().write(rt.state));
        if (!reloaded.equals(live)) {
            // Keep both for a diff: the first different line alone rarely says which block moved.
            try {
                Files.writeString(rt.store.dir().resolve("mismatch-live.json"), live);
                Files.writeString(rt.store.dir().resolve("mismatch-reloaded.json"), reloaded);
            } catch (java.io.IOException ignored) {
            }
        }
        helper.assertTrue(reloaded.equals(live), "The reloaded state does not write the same snapshot as the live one: " + firstDifference(live, reloaded));
        helper.succeed();
    }

    /** The first line where two snapshots differ, so a failure says what moved. */
    static String firstDifference(String a, String b) {
        String[] x = a.split("\n");
        String[] y = b.split("\n");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String l = i < x.length ? x[i].strip() : "<none>";
            String r = i < y.length ? y[i].strip() : "<none>";
            if (!l.equals(r)) return "line " + i + ": live [" + l + "] reloaded [" + r + "]";
        }
        return "same lines";
    }
}
