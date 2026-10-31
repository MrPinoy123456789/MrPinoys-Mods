package charter.mc;

import charter.config.Knobs;
import charter.engine.ActionEngine;
import charter.engine.Actor;
import charter.engine.Outcome;
import charter.gov.Preset;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.mc.discord.DiscordConfig;
import charter.mc.discord.DiscordInteractions;
import charter.mc.discord.DiscordMessages;
import charter.mc.discord.JoinDesk;
import charter.mc.discord.LinkBook;
import charter.mc.store.Persistence;
import charter.org.actions.AcceptInvite;
import charter.org.actions.Apply;
import charter.org.actions.Found;
import charter.org.actions.InviteMember;
import charter.state.CharterState;
import charter.time.Clock;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static charter.mc.FabricSuite.check;

/**
 * Discord: linking an account by code, the join requests a member may answer, and
 * the interactions the bot gets, driven as Discord would send them, through the
 * real engine. The network (gateway, HTTP) is not exercised here.
 */
final class DiscordTest {

    private static final long MINUTE = 60_000L;

    private DiscordTest() {
    }

    static void run() throws Exception {
        codesLinkOnceAndRunOut();
        wrongCodesSlowAnAccountDown();
        oneLinkEachWay();
        linksSurviveARestart();
        aLordSeesAndAnswersRequests();
        aMemberWithoutThePowerCannotAnswer();
        interactionsLinkAndAnswer();
        aLongQueueShowsFiveRows();
        configIsOffUntilTurnedOn();
    }

    // ---- the link book ----

    private static LinkBook book() {
        return new LinkBook(new Random(7), 10 * MINUTE);
    }

    private static PlayerId player(String name) {
        return new PlayerId(UUID.nameUUIDFromBytes(("player:" + name).getBytes(StandardCharsets.UTF_8)));
    }

    static void codesLinkOnceAndRunOut() {
        LinkBook links = book();
        PlayerId alice = player("alice");
        String code = links.issue(alice, 0);
        check("link: a code is six characters from the plain alphabet", code.matches("[A-HJ-NP-Z2-9]{6}"));
        LinkBook.Result linked = links.redeem("111", code.toLowerCase().substring(0, 3) + "-" + code.substring(3), MINUTE);
        check("link: the code links, typed in lower case with a dash", linked.kind() == LinkBook.Redeemed.LINKED && alice.equals(linked.player()));
        check("link: both ways", links.discordOf(alice).orElse("").equals("111") && links.playerOf("111").orElse(null).equals(alice));
        check("link: a code works once", links.redeem("222", code, MINUTE).kind() == LinkBook.Redeemed.NO_SUCH_CODE);

        String late = links.issue(alice, 0);
        check("link: a code runs out", links.redeem("333", late, 10 * MINUTE).kind() == LinkBook.Redeemed.EXPIRED);
        check("link: a code that ran out links nobody", links.playerOf("333").isEmpty());

        String first = links.issue(alice, 0);
        String second = links.issue(alice, 0);
        check("link: a new code replaces the player's old one", links.redeem("444", first, 1).kind() == LinkBook.Redeemed.NO_SUCH_CODE);
        check("link: the new code works", links.redeem("444", second, 1).kind() == LinkBook.Redeemed.LINKED);
    }

    static void wrongCodesSlowAnAccountDown() {
        LinkBook links = book();
        String code = links.issue(player("bob"), 0);
        for (int i = 0; i < 5; i++) links.redeem("666", "WRONG" + i, 1);
        check("link: after five wrong codes even the right one waits", links.redeem("666", code, 2).kind() == LinkBook.Redeemed.SLOW_DOWN);
        check("link: another account is not held up", links.redeem("777", "NOPE", 2).kind() == LinkBook.Redeemed.NO_SUCH_CODE);
        check("link: after the window the account may try again", links.redeem("666", code, 2 + 15 * MINUTE).kind() == LinkBook.Redeemed.EXPIRED);
        String fresh = links.issue(player("bob"), 20 * MINUTE);
        check("link: and a fresh code links", links.redeem("666", fresh, 20 * MINUTE).kind() == LinkBook.Redeemed.LINKED);
    }

    static void oneLinkEachWay() {
        LinkBook links = book();
        PlayerId alice = player("alice");
        PlayerId bob = player("bob");
        links.link(alice, "1");
        links.link(alice, "2");
        check("link: a player's new account replaces the old", links.playerOf("1").isEmpty() && links.discordOf(alice).orElse("").equals("2"));
        links.link(bob, "2");
        check("link: an account linked to someone new leaves the old player", links.discordOf(alice).isEmpty() && links.playerOf("2").orElse(null).equals(bob));
        check("link: unlink from either side", links.unlinkDiscord("2") && links.discordOf(bob).isEmpty() && !links.unlink(bob));
    }

    static void linksSurviveARestart() throws Exception {
        Path dir = Files.createTempDirectory("charter-discord");
        LinkBook links = book();
        links.link(player("alice"), "1");
        links.link(player("bob"), "2");
        links.save(dir, w -> { });
        LinkBook back = book();
        back.load(dir, w -> { });
        check("link: links come back after a restart", back.size() == 2 && back.playerOf("2").orElse(null).equals(player("bob")));

        Files.writeString(dir.resolve(LinkBook.FILE_NAME), "{ not json");
        List<String> warned = new ArrayList<>();
        LinkBook broken = book();
        broken.load(dir, warned::add);
        check("link: an unreadable file starts empty, says so and is left alone",
                broken.size() == 0 && warned.size() == 1 && Files.readString(dir.resolve(LinkBook.FILE_NAME)).equals("{ not json"));
    }

    // ---- the join desk ----

    /** A Lordship with its Lord and one plain member, and two players with no faction. */
    private static final class World {
        final Knobs knobs = new Knobs();
        final CharterState state = new CharterState();
        final Clock.Manual clock = new Clock.Manual(1_800_000_000_000L);
        final ActionEngine engine = new ActionEngine(state, knobs, clock);
        final Describe describe;
        final PlayerId lord = player("lord");
        final PlayerId member = player("member");
        final PlayerId carol = player("carol");
        final PlayerId dave = player("dave");
        final OrgId org;

        World() {
            Persistence.register();
            describe = new Describe(state, Persistence.ACTIONS);
            for (PlayerId p : List.of(lord, member, carol, dave)) state.touch(p, name(p), clock.now());
            must(engine.run(new Actor.Player(lord), new Found(lord, "Oakvale", Preset.LORDSHIP)));
            org = state.orgNamed("Oakvale").orElseThrow().id;
            must(engine.run(new Actor.Player(lord), new InviteMember(org, member)));
            must(engine.run(new Actor.Player(member), new AcceptInvite(member, org)));
        }

        String name(PlayerId p) {
            return p.equals(lord) ? "Lord" : p.equals(member) ? "Member" : p.equals(carol) ? "Carol" : "Dave";
        }

        void apply(PlayerId p) {
            must(engine.run(new Actor.Player(p), new Apply(p, org)));
        }

        JoinDesk desk() {
            return new JoinDesk(state, engine, describe);
        }

        static void must(Outcome o) {
            if (!o.done()) throw new IllegalStateException("setup failed: " + o);
        }
    }

    static void aLordSeesAndAnswersRequests() {
        World w = new World();
        w.apply(w.carol);
        w.clock.advance(1);
        w.apply(w.dave);
        JoinDesk desk = w.desk();
        List<JoinDesk.Entry> seen = desk.answerable(w.lord);
        check("desk: the Lord sees both requests, oldest first",
                seen.size() == 2 && seen.get(0).player().equals(w.carol) && seen.get(1).player().equals(w.dave));
        check("desk: with the names to show", seen.get(0).playerName().equals("Carol") && seen.get(0).orgName().equals("Oakvale"));
        check("desk: the Lord is told when someone asks", desk.answerers(w.org).equals(List.of(w.lord)));

        JoinDesk.Answer admitted = desk.answer(w.lord, w.org, w.carol, true);
        check("desk: admitting takes the player in", admitted.done() && w.state.isMember(w.carol, w.org));
        check("desk: and says so", admitted.line().equals(Text.get("book.faction.admitted", "player", "Carol")));

        JoinDesk.Answer rejected = desk.answer(w.lord, w.org, w.dave, false);
        check("desk: turning away ends the request", rejected.done() && w.state.joinRequestOf(w.dave).isEmpty() && !w.state.isMember(w.dave, w.org));
        check("desk: nothing is left to answer", desk.answerable(w.lord).isEmpty());

        JoinDesk.Answer again = desk.answer(w.lord, w.org, w.dave, true);
        check("desk: a settled request cannot be answered twice", !again.done() && !w.state.isMember(w.dave, w.org));
    }

    static void aMemberWithoutThePowerCannotAnswer() {
        World w = new World();
        w.apply(w.carol);
        JoinDesk desk = w.desk();
        check("desk: a plain member of a Lordship sees no requests", desk.answerable(w.member).isEmpty());
        check("desk: a player with no faction sees none", desk.answerable(w.dave).isEmpty());
        JoinDesk.Answer tried = desk.answer(w.member, w.org, w.carol, true);
        check("desk: a plain member cannot admit", !tried.done() && !w.state.isMember(w.carol, w.org));
        check("desk: the request still waits for the Lord", w.state.joinRequest(w.org, w.carol).isPresent());
    }

    // ---- interactions ----

    private static JsonObject command(String user, boolean inGuild, String name, Map<String, String> options) {
        JsonObject i = new JsonObject();
        i.addProperty("id", "9");
        i.addProperty("token", "secret");
        i.addProperty("type", 2);
        JsonObject u = new JsonObject();
        u.addProperty("id", user);
        if (inGuild) {
            JsonObject member = new JsonObject();
            member.add("user", u);
            i.add("member", member);
        } else {
            i.add("user", u);
        }
        JsonObject data = new JsonObject();
        data.addProperty("name", name);
        JsonArray opts = new JsonArray();
        options.forEach((k, v) -> {
            JsonObject o = new JsonObject();
            o.addProperty("name", k);
            o.addProperty("value", v);
            opts.add(o);
        });
        data.add("options", opts);
        i.add("data", data);
        return i;
    }

    private static JsonObject click(String user, String customId) {
        JsonObject i = command(user, false, "", Map.of());
        i.addProperty("type", 3);
        JsonObject data = new JsonObject();
        data.addProperty("custom_id", customId);
        i.add("data", data);
        return i;
    }

    private static List<String> buttonIds(JsonObject message) {
        List<String> out = new ArrayList<>();
        for (JsonElement row : message.getAsJsonArray("components")) {
            for (JsonElement b : row.getAsJsonObject().getAsJsonArray("components")) {
                out.add(b.getAsJsonObject().get("custom_id").getAsString());
            }
        }
        return out;
    }

    private static String content(JsonObject message) {
        return message.get("content").getAsString();
    }

    static void interactionsLinkAndAnswer() {
        World w = new World();
        LinkBook links = book();
        int[] saved = {0};
        DiscordInteractions bot = new DiscordInteractions(links, w::desk, w.describe::player, w.clock, () -> saved[0]++);

        JsonObject notLinked = bot.handle(command("100", true, "requests", Map.of()));
        check("bot: an unlinked account is told how to link", content(notLinked).equals(Text.get("discord.not_linked")));

        String code = links.issue(w.lord, w.clock.now());
        JsonObject linked = bot.handle(command("100", true, "link", Map.of("code", code)));
        check("bot: /link links and names the player", content(linked).equals(Text.get("discord.linked", "player", "Lord")));
        check("bot: and the links are saved", saved[0] == 1);
        links.link(w.member, "200");

        JsonObject empty = bot.handle(command("100", false, "requests", Map.of()));
        check("bot: in a DM, with nobody asking", content(empty).equals(Text.get("discord.requests.none")) && buttonIds(empty).isEmpty());

        w.apply(w.carol);
        w.apply(w.dave);
        JsonObject list = bot.handle(command("100", true, "requests", Map.of()));
        List<String> ids = buttonIds(list);
        check("bot: the Lord gets Admit and Turn away for each request", ids.size() == 4);
        check("bot: every button id fits Discord's 100 characters", ids.stream().allMatch(id -> id.length() <= 100));
        DiscordMessages.Click admitCarol = DiscordMessages.parse(ids.get(0));
        check("bot: the first button admits Carol to Oakvale",
                admitCarol != null && admitCarol.admit() && admitCarol.player().equals(w.carol) && admitCarol.org().equals(w.org));
        check("bot: a stranger's id is not a click", DiscordMessages.parse("charter:join:x:1:2") == null && DiscordMessages.parse("other") == null);

        JsonObject forged = bot.handle(click("200", ids.get(0)));
        check("bot: a member without the power pressing the Lord's button admits nobody",
                !w.state.isMember(w.carol, w.org) && w.state.joinRequest(w.org, w.carol).isPresent());
        check("bot: and is told why", !content(forged).contains(Text.get("book.faction.admitted", "player", "Carol")));

        JsonObject after = bot.handle(click("100", ids.get(0)));
        check("bot: the Lord's click admits Carol", w.state.isMember(w.carol, w.org));
        check("bot: the list comes back with the line and Dave's buttons",
                content(after).startsWith(Text.get("book.faction.admitted", "player", "Carol")) && buttonIds(after).size() == 2);

        JsonObject confirm = bot.handle(click("100", buttonIds(after).get(1)));
        check("bot: Turn away asks first", content(confirm).equals(Text.get("book.reject.confirm", "player", "Dave"))
                && w.state.joinRequest(w.org, w.dave).isPresent());
        DiscordMessages.Click yes = DiscordMessages.parse(buttonIds(confirm).get(0));
        check("bot: the confirm button turns away", yes != null && yes.reject());
        bot.handle(click("100", buttonIds(confirm).get(0)));
        check("bot: Dave is turned away", w.state.joinRequestOf(w.dave).isEmpty() && !w.state.isMember(w.dave, w.org));

        JsonObject unlinked = bot.handle(command("100", true, "unlink", Map.of()));
        check("bot: /unlink", content(unlinked).equals(Text.get("discord.unlinked")) && links.discordOf(w.lord).isEmpty() && saved[0] == 2);
        JsonObject stale = bot.handle(click("100", ids.get(2)));
        check("bot: an old button does nothing once unlinked", content(stale).equals(Text.get("discord.not_linked")));

        DiscordInteractions down = new DiscordInteractions(links, () -> null, w.describe::player, w.clock, () -> { });
        check("bot: while Charter is not running it says so",
                content(down.handle(command("200", true, "requests", Map.of()))).equals(Text.get("discord.not_running")));
    }

    static void aLongQueueShowsFiveRows() {
        List<JoinDesk.Entry> many = new ArrayList<>();
        OrgId org = new OrgId(new UUID(1, 1));
        for (int i = 0; i < 7; i++) {
            many.add(new JoinDesk.Entry(org, "Oakvale", player("p" + i), "Player" + i + "WithAVeryLongNameIndeedForALabel", i));
        }
        JsonObject m = DiscordMessages.requests(many, null);
        check("messages: at most five rows", m.getAsJsonArray("components").size() == DiscordMessages.ROWS_MAX);
        check("messages: the rest are counted", content(m).contains(Text.get("discord.requests.more", "count", 2)));
        boolean labelsFit = true;
        for (JsonElement row : m.getAsJsonArray("components")) {
            for (JsonElement b : row.getAsJsonObject().getAsJsonArray("components")) {
                labelsFit &= b.getAsJsonObject().get("label").getAsString().length() <= 80;
            }
        }
        check("messages: every label fits Discord's 80 characters", labelsFit);
        JsonArray commands = DiscordMessages.commands();
        List<String> names = new ArrayList<>();
        commands.forEach(c -> names.add(c.getAsJsonObject().get("name").getAsString()));
        check("messages: the bot's commands", names.equals(List.of("link", "unlink", "requests")));
    }

    static void configIsOffUntilTurnedOn() throws Exception {
        Path dir = Files.createTempDirectory("charter-discord-config");
        DiscordConfig fresh = DiscordConfig.readOrCreate(dir, i -> { }, w -> { });
        check("config: off by default, and the file is written", !fresh.enabled && Files.exists(dir.resolve(DiscordConfig.FILE_NAME)));
        check("config: the token can come from the environment", fresh.token(k -> k.equals(DiscordConfig.TOKEN_ENV) ? " abc " : null).equals("abc"));
        Files.writeString(dir.resolve(DiscordConfig.FILE_NAME), "{\"enabled\": true, \"guildId\": \"my server\"}");
        List<String> warned = new ArrayList<>();
        DiscordConfig bad = DiscordConfig.readOrCreate(dir, i -> { }, warned::add);
        check("config: a bad guild id keeps Discord off and says why", !bad.enabled && !warned.isEmpty());
        Files.writeString(dir.resolve(DiscordConfig.FILE_NAME), "{\"enabled\": true, \"botToken\": \"t\", \"guildId\": \"123456789012345678\"}");
        DiscordConfig good = DiscordConfig.readOrCreate(dir, i -> { }, w -> { });
        check("config: a good file turns it on", good.enabled && good.token(k -> null).equals("t"));
    }
}
