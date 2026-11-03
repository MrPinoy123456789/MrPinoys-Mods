package charter.gametest;

import charter.engine.Actor;
import charter.engine.Outcome;
import charter.grant.AuthorityResolver;
import charter.grant.Power;
import charter.grant.ScopeNeed;
import charter.grant.Who;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.mc.CharterRuntime;
import charter.mc.Mail;
import charter.mc.Tags;
import charter.mc.book.BedrockForms;
import charter.mc.book.BookDialogs;
import charter.mc.book.BookScreen;
import charter.mc.book.RankPages;
import charter.mc.store.Persistence;
import charter.mc.store.Repairs;
import charter.mc.store.StateCodec;
import charter.org.Ranks;
import charter.org.actions.AcceptInvite;
import charter.org.actions.AddRank;
import charter.org.actions.GiveRank;
import charter.org.actions.InviteMember;
import charter.org.actions.RenameRank;
import charter.org.actions.SetRankPower;
import charter.state.CharterState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.ChatType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Ranks (titles) on a live server: a Lord renames and gives ranks through the commands and the book, a plain member
 * is refused, the name tag shows "[Kingdom] Rank Name", a Bedrock player (a Floodgate UUID and a dotted name) is
 * handled, an old save with no ranks loads as the default ladder, rank powers resolve live and never include a
 * forbidden one, and everything survives a save and reload. None of it needs Floodgate.
 */
public final class RankGameTest {

    private static PlayerId id(ServerPlayer p) {
        return new PlayerId(p.getUUID());
    }

    private static void asOp(LandKit kit, String command) {
        kit.server.getCommands().performPrefixedCommand(kit.server.createCommandSourceStack(), command);
    }

    private static void asPlayer(LandKit kit, ServerPlayer player, String command) {
        kit.server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
    }

    private static ServerPlayer member(LandKit kit, OrgId org) {
        ServerPlayer p = kit.outsider();
        kit.rt.state.touch(id(p), p.getPlainTextName(), kit.rt.clock.now());
        kit.rt.engine.run(new Actor.Admin("gametest"), new InviteMember(org, id(p)));
        kit.rt.engine.run(new Actor.Player(id(p)), new AcceptInvite(id(p), org));
        return p;
    }

    private static Optional<String> rankName(CharterRuntime rt, PlayerId player) {
        return rt.state.ranks.rankOf(rt.state, player).map(Ranks.Rank::name);
    }

    private static String rankId(CharterRuntime rt, OrgId kingdom, String name) {
        return rt.state.ranks.find(kingdom, name).orElseThrow().id();
    }

    private static boolean holds(CharterState state, long now, PlayerId who, OrgId org, Power power) {
        return new AuthorityResolver(state, now).resolve(new Who.AsPlayer(who), org, power, ScopeNeed.NONE, false).ok();
    }

    private static String kingdomName(CharterRuntime rt, OrgId org) {
        return rt.state.org(org).orElseThrow().name();
    }

    @GameTest(maxTicks = 100)
    public void theLordRenamesTheTopRankAndAPlainMemberIsRefused(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer lord = kit.faction("Rk");
            OrgId k = kit.orgOf(lord);
            ServerPlayer gil = member(kit, k);
            helper.assertTrue(rankName(kit.rt, id(lord)).equals(Optional.of("Lord")), "A new Lord does not hold the top rank");
            helper.assertTrue(rankName(kit.rt, id(gil)).equals(Optional.of("Commoner")), "A new member is not a Commoner");
            helper.assertTrue(holds(kit.rt.state, kit.rt.clock.now(), id(lord), k, Power.MANAGE_RANKS), "A new Lord may not manage ranks");

            asPlayer(kit, gil, "charter rank rename Lord King");
            helper.assertTrue(!kit.rt.state.ranks.edited(k), "A plain member renamed the top rank by command");
            Outcome refused = kit.rt.engine.run(new Actor.Player(id(gil)), new RenameRank(k, Ranks.TOP, "King"));
            helper.assertTrue(refused.refused(), "A plain member was not refused: " + refused);
            BookDialogs.LAST.remove(gil.getUUID());
            RankPages.ranks(gil, null);
            BookScreen shown = BookDialogs.LAST.get(gil.getUUID());
            helper.assertTrue(shown == null || BookDialogs.buttons(shown).stream().noneMatch(b -> b.label().equals("Give a rank")),
                    "A plain member was shown the Ranks page");

            asPlayer(kit, lord, "charter rank rename Lord Queen");
            helper.assertTrue(kit.rt.state.ranks.ladder(k).get(0).name().equals("Queen"), "The Lord could not rename the top rank: "
                    + kit.rt.state.ranks.ladder(k));
            helper.assertTrue(rankName(kit.rt, id(lord)).equals(Optional.of("Queen")), "The Lord is not called Queen");
            helper.assertTrue(kit.rt.state.history.entries().stream().anyMatch(e -> e.event() instanceof charter.org.RankEvents.RankRenamed r
                    && r.org().equals(k) && r.to().equals("Queen")), "The rename was not recorded");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void aKingdomWithAShortLabelShowsItInTheTag(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            CharterRuntime rt = kit.rt;
            ServerPlayer lord = kit.faction("Sl");
            OrgId k = kit.orgOf(lord);
            String name = kingdomName(rt, k);
            String key = name.toLowerCase(Locale.ROOT);
            java.util.Map<String, String> colours = rt.knobs.tagColors;
            java.util.Map<String, String> labels = rt.knobs.tagLabels;
            rt.knobs.tagColors = new java.util.LinkedHashMap<>(colours);
            rt.knobs.tagColors.put(key, "#388E3C");
            try {
                String who = lord.getScoreboardName();
                helper.assertTrue(Tags.tagText(rt, id(lord), who).equals("[" + name + "] " + who), "No label: " + Tags.tagText(rt, id(lord), who));
                rt.knobs.tagLabels = new java.util.LinkedHashMap<>(labels);
                rt.knobs.tagLabels.put(key, "Moss");
                helper.assertTrue(Tags.tagText(rt, id(lord), who).equals("[Moss] " + who), "With a label: " + Tags.tagText(rt, id(lord), who));
                helper.assertTrue(rt.state.orgNamed(key).isPresent(), "The kingdom lost its own name");
            } finally {
                rt.knobs.tagColors = colours;
                rt.knobs.tagLabels = labels;
            }
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void theLordGivesARankAndTheTagShowsIt(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            CharterRuntime rt = kit.rt;
            ServerPlayer lord = kit.faction("Tg");
            OrgId k = kit.orgOf(lord);
            ServerPlayer gil = member(kit, k);
            String name = kingdomName(rt, k);
            String key = name.toLowerCase(Locale.ROOT);
            boolean chat = rt.knobs.rankInChat;
            java.util.Map<String, String> colours = rt.knobs.tagColors;
            rt.knobs.tagColors = new java.util.LinkedHashMap<>(colours);
            rt.knobs.tagColors.put(key, "#A020F0");
            try {
                String gilName = gil.getScoreboardName();
                helper.assertTrue(Tags.tagText(rt, id(gil), gilName).equals("[" + name + "] " + gilName),
                        "Before any edit the tag shows a rank: " + Tags.tagText(rt, id(gil), gilName));
                Tags.applyTo(gil, rt);
                String plainTeam = kit.server.getScoreboard().getPlayersTeam(gilName).getName();

                asPlayer(kit, lord, "charter rank add Royal Commander");
                helper.assertTrue(kit.rt.state.ranks.find(k, "Royal Commander").isPresent(), "The Lord could not add a rank: " + rt.state.ranks.ladder(k));
                // Mock players all share one name, so the member is named by UUID; the quotes are what tab completion types.
                asPlayer(kit, lord, "charter rank set " + gil.getUUID() + " \"Royal Commander\"");
                helper.assertTrue(rankName(rt, id(gil)).equals(Optional.of("Royal Commander")), "The Lord could not give the rank: " + rankName(rt, id(gil)));
                helper.assertTrue(Tags.tagText(rt, id(gil), gilName).equals("[" + name + "] Royal Commander " + gilName),
                        "The tag: " + Tags.tagText(rt, id(gil), gilName));

                Tags.applyTo(gil, rt);
                PlayerTeam team = kit.server.getScoreboard().getPlayersTeam(gilName);
                helper.assertTrue(team != null && team.getName().startsWith("ct_") && !team.getName().equals(plainTeam),
                        "The member is not on a rank team: " + (team == null ? null : team.getName()));
                helper.assertTrue(team.getPlayerPrefix().getString().equals("[" + name + "] Royal Commander "), "The prefix: " + team.getPlayerPrefix().getString());
                helper.assertTrue(team.getName().length() <= 16, "The team name is long: " + team.getName());
                var parts = team.getPlayerPrefix().toFlatList();
                helper.assertTrue(parts.size() == 2 && parts.get(0).getStyle().getColor() != null
                        && parts.get(0).getStyle().getColor().getValue() == 0xA020F0
                        && parts.get(1).getStyle().getColor() != null
                        && parts.get(1).getStyle().getColor().getValue() == 0xAAAAAA, "The colours: " + parts);
                Tags.apply(kit.server, rt);
                PlayerTeam old = kit.server.getScoreboard().getPlayerTeam(plainTeam);
                helper.assertTrue(old == null || !old.getPlayers().isEmpty(), "An empty plain team was left behind");

                // rankInChat off: chat shows the kingdom tag and the name, the rank stays over the head.
                rt.knobs.rankInChat = false;
                ChatType.Bound bound = ChatType.bind(ChatType.CHAT, gil);
                ChatType.Bound swapped = Tags.chatBound(gil, bound);
                helper.assertTrue(swapped != null && swapped.name().getString().equals("[" + name + "] " + gil.getName().getString()),
                        "With rankInChat off the chat name was " + (swapped == null ? null : swapped.name().getString()));
                rt.knobs.rankInChat = true;
                helper.assertTrue(Tags.chatBound(gil, bound) == null, "With rankInChat on the chat name was changed");
            } finally {
                rt.knobs.rankInChat = chat;
                rt.knobs.tagColors = colours;
                Tags.applyTo(gil, rt);
            }
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void aBedrockPlayerIsGivenARankByAnOperatorAndRenamesByForm(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            CharterRuntime rt = kit.rt;
            ServerPlayer lord = kit.faction("Bd");
            OrgId k = kit.orgOf(lord);
            String kingdom = kingdomName(rt, k);
            // Floodgate: a UUID with a zero high half, and a name with a dot in front.
            PlayerId xbox = new PlayerId(new UUID(0, 0x0009_0000_0000_0000L | (System.nanoTime() & 0xFFFFFFFFL)));
            String dotted = ".Dorito" + Long.toString(System.nanoTime() & 0xFFFFF, 36);
            rt.state.touch(xbox, dotted, rt.clock.now());
            rt.engine.run(new Actor.Admin("gametest"), new InviteMember(k, xbox));
            helper.assertTrue(rt.engine.run(new Actor.Player(xbox), new AcceptInvite(xbox, k)).done(), "The Bedrock player could not join");
            asOp(kit, "charter admin faction title \"" + kingdom + "\" add Princess");
            asOp(kit, "charter admin faction title \"" + kingdom + "\" set " + dotted + " Princess");
            helper.assertTrue(rankName(rt, xbox).equals(Optional.of("Princess")), "The operator could not name the Bedrock player Princess: " + rankName(rt, xbox));

            // The Lord on a Bedrock client renames the top rank from the dropdown, never typing.
            List<BedrockForms.Form> forms = new ArrayList<>();
            List<BedrockForms.Handler> handlers = new ArrayList<>();
            BedrockForms.install(new BedrockForms.Sender() {
                @Override
                public boolean isBedrock(UUID who) {
                    return who.equals(lord.getUUID());
                }

                @Override
                public boolean send(UUID who, BedrockForms.Form form, BedrockForms.Handler handler) {
                    forms.add(form);
                    handlers.add(handler);
                    return true;
                }
            });
            BedrockForms.executor(Runnable::run);
            try {
                RankPages.rename(lord, Ranks.TOP, null);
                helper.assertTrue(!forms.isEmpty() && forms.get(forms.size() - 1) instanceof BedrockForms.Form.Custom,
                        "Rename was not a custom form: " + forms);
                // The dropdown's entries: my own, then the suggestions in order; 2 is the second suggestion, Queen.
                handlers.get(handlers.size() - 1).submitted(List.of("2", "Lord"));
                helper.assertTrue(rt.state.ranks.ladder(k).get(0).name().equals("Queen"), "The form did not rename the top rank: " + rt.state.ranks.ladder(k));
            } finally {
                BedrockForms.install(null);
                BedrockForms.executor(null);
                BedrockForms.forget(lord.getUUID());
            }
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void rankPowersResolveLiveAndForbiddenOnesAreRefused(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            CharterRuntime rt = kit.rt;
            ServerPlayer lord = kit.faction("Pw");
            OrgId k = kit.orgOf(lord);
            ServerPlayer gil = member(kit, k);
            Actor asLord = new Actor.Player(id(lord));
            rt.engine.run(asLord, new AddRank(k, "Knight"));
            String knight = rankId(rt, k, "Knight");
            rt.engine.run(asLord, new GiveRank(k, id(gil), knight));
            long now = rt.clock.now();
            helper.assertTrue(!holds(rt.state, now, id(gil), k, Power.CLAIM), "A new rank carries a power");
            helper.assertTrue(rt.engine.run(asLord, new SetRankPower(k, knight, Power.CLAIM, true)).done(), "The Lord could not give the rank a power");
            helper.assertTrue(holds(rt.state, rt.clock.now(), id(gil), k, Power.CLAIM), "The Knight cannot claim");
            rt.engine.run(asLord, new GiveRank(k, id(gil), Ranks.DEFAULT));
            helper.assertTrue(!holds(rt.state, rt.clock.now(), id(gil), k, Power.CLAIM), "Off the rank, the power stayed");
            for (Power forbidden : List.of(Power.DISSOLVE, Power.AMEND, Power.APPOINT, Power.MANAGE_RANKS)) {
                Outcome o = rt.engine.run(new Actor.Admin("gametest"), new SetRankPower(k, knight, forbidden, true));
                helper.assertTrue(o.refused(), "A rank was given " + forbidden + ": " + o);
            }
            // The leader locks guard offices and governance, not ranks: with both on, the Lord still manages ranks.
            boolean appoint = rt.knobs.leadersAppointOffices;
            boolean governance = rt.knobs.leadersChangeGovernance;
            rt.knobs.leadersAppointOffices = false;
            rt.knobs.leadersChangeGovernance = false;
            try {
                helper.assertTrue(rt.engine.run(asLord, new RenameRank(k, knight, "Knight Errant")).done(), "The leader locks stopped a rename");
                helper.assertTrue(rt.engine.run(asLord, new SetRankPower(k, knight, Power.APPOINT, true)).refused(), "A locked power reached a rank");
            } finally {
                rt.knobs.leadersAppointOffices = appoint;
                rt.knobs.leadersChangeGovernance = governance;
            }
            asPlayer(kit, gil, "charter rank power \"Knight Errant\" spend on");
            helper.assertTrue(!Ranks.powers(rt.state, rt.clock.now(), k, knight).contains(Power.SPEND), "A plain member set a rank's power");

            // The book offers only what the Lord holds and never a forbidden power.
            BookDialogs.LAST.remove(lord.getUUID());
            RankPages.powers(lord, knight, null);
            BookScreen page = BookDialogs.LAST.get(lord.getUUID());
            helper.assertTrue(page != null && page.violations().isEmpty(), "The powers page: " + (page == null ? null : page.violations()));
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void theRankPagesKeepTheScreenRules(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            CharterRuntime rt = kit.rt;
            ServerPlayer lord = kit.faction("Pg");
            OrgId k = kit.orgOf(lord);
            ServerPlayer gil = member(kit, k);
            Actor asLord = new Actor.Player(id(lord));
            for (int i = 0; i < Ranks.MAX_RANKS + 2; i++) rt.engine.run(asLord, new AddRank(k, "Rank " + i));
            helper.assertTrue(rt.state.ranks.ladder(k).size() == Ranks.MAX_RANKS, "The ladder passed its cap: " + rt.state.ranks.ladder(k).size());
            String middle = rt.state.ranks.ladder(k).get(3).id();
            List<Runnable> pages = List.of(
                    () -> RankPages.ranks(lord, null), () -> RankPages.rank(lord, middle, null), () -> RankPages.rank(lord, Ranks.TOP, null),
                    () -> RankPages.give(lord, null), () -> RankPages.add(lord, null), () -> RankPages.rename(lord, middle, null),
                    () -> RankPages.list(lord, null), () -> RankPages.list(gil, null), () -> charter.mc.book.LeaderPages.desk(lord, null));
            for (Runnable page : pages) {
                BookDialogs.LAST.remove(lord.getUUID());
                BookDialogs.LAST.remove(gil.getUUID());
                page.run();
                BookScreen shown = BookDialogs.LAST.getOrDefault(lord.getUUID(), BookDialogs.LAST.get(gil.getUUID()));
                helper.assertTrue(shown != null, "A rank page showed nothing");
                helper.assertTrue(shown.violations().isEmpty(), "A rank page breaks the screen rules: " + shown.violations());
            }
            BookDialogs.LAST.remove(gil.getUUID());
            RankPages.list(gil, null);
            helper.assertTrue(BookDialogs.LAST.get(gil.getUUID()).bodyText().stream().anyMatch(l -> l.contains("Your rank: Commoner")),
                    "My faction's rank list does not say the member's rank");
            helper.succeed();
        }
    }

    /** The codec a second store would use, so reading back never touches the live store. */
    private static StateCodec codec(CharterRuntime rt) {
        return Persistence.codec(rt.json, new Mail(20).block());
    }

    @GameTest(maxTicks = 100)
    public void anOldSaveLoadsWithTheDefaultLadder(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            CharterRuntime rt = kit.rt;
            ServerPlayer lord = kit.faction("Ol");
            OrgId k = kit.orgOf(lord);
            ServerPlayer gil = member(kit, k);
            StateCodec codec = codec(rt);
            JsonObject root = codec.write(rt.state).deepCopy();
            // A save from before ranks: no ranks block and no Lord holding the power to manage ranks.
            root.remove("ranks");
            JsonArray grants = root.getAsJsonObject("grants").getAsJsonArray("grants");
            JsonArray kept = new JsonArray();
            for (JsonElement g : grants) {
                if (!"MANAGE_RANKS".equals(g.getAsJsonObject().get("power").getAsString())) kept.add(g);
            }
            root.getAsJsonObject("grants").add("grants", kept);
            Repairs repairs = new Repairs(rt.clock.now());
            CharterState back = codec.read(root, rt.knobs, repairs);
            helper.assertTrue(!back.ranks.edited(k), "An old save has an edited ladder");
            helper.assertTrue(back.ranks.rankOf(back, id(lord)).map(Ranks.Rank::id).equals(Optional.of(Ranks.TOP)), "The Lord is not at the top rank");
            helper.assertTrue(back.ranks.rankOf(back, id(gil)).map(Ranks.Rank::name).equals(Optional.of("Commoner")), "A member is not a Commoner");
            helper.assertTrue(holds(back, rt.clock.now(), id(lord), k, Power.MANAGE_RANKS), "The Lord of an old kingdom was not given the power to manage ranks");
            helper.assertTrue(!holds(back, rt.clock.now(), id(gil), k, Power.MANAGE_RANKS), "A member was given the power to manage ranks");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void ranksSurviveASaveAndReload(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            CharterRuntime rt = kit.rt;
            ServerPlayer lord = kit.faction("Rt");
            OrgId k = kit.orgOf(lord);
            ServerPlayer gil = member(kit, k);
            Actor asLord = new Actor.Player(id(lord));
            rt.engine.run(asLord, new RenameRank(k, Ranks.TOP, "Queen"));
            rt.engine.run(asLord, new AddRank(k, "Royal Commander"));
            String commander = rankId(rt, k, "Royal Commander");
            rt.engine.run(asLord, new GiveRank(k, id(gil), commander));
            helper.assertTrue(rt.engine.run(asLord, new SetRankPower(k, commander, Power.DECLARE_WAR, true)).done(), "Could not give the rank a power");

            StateCodec codec = codec(rt);
            JsonObject written = codec.write(rt.state);
            CharterState back = codec.read(written.deepCopy(), rt.knobs, new Repairs(rt.clock.now()));
            helper.assertTrue(back.ranks.ladder(k).stream().map(Ranks.Rank::name).toList().equals(List.of("Queen", "Royal Commander", "Commoner")),
                    "The ladder did not reload: " + back.ranks.ladder(k));
            helper.assertTrue(back.ranks.rankOf(back, id(gil)).map(Ranks.Rank::id).equals(Optional.of(commander)), "The member's rank did not reload");
            helper.assertTrue(back.ranks.rankOf(back, id(lord)).map(Ranks.Rank::name).equals(Optional.of("Queen")), "The Lord is not Queen after reload");
            helper.assertTrue(holds(back, rt.clock.now(), id(gil), k, Power.DECLARE_WAR), "The rank's power did not survive the reload");
            String again = rt.json.gson.toJson(codec.write(back).get("ranks"));
            helper.assertTrue(again.equals(rt.json.gson.toJson(written.get("ranks"))), "The ranks block does not write the same after reload");
            helper.succeed();
        }
    }

    /** An operator sees every kingdom's ranks: who holds each and what each may do, typed and in the Admin Book. */
    @GameTest(maxTicks = 100)
    public void anOperatorSeesAKingdomsRanksAndWhoHoldsThem(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            CharterRuntime rt = kit.rt;
            ServerPlayer lord = kit.faction("OpRk");
            OrgId k = kit.orgOf(lord);
            ServerPlayer gil = member(kit, k);
            List<String> plain = charter.mc.cmd.OpCommands.rankLines(rt, k);
            helper.assertTrue(plain.get(0).equals(charter.mc.Text.get("admin.ranks.unedited")) && plain.size() == 3,
                    "An unedited kingdom does not show the note and its two ranks: " + plain);
            Actor asLord = new Actor.Player(id(lord));
            rt.engine.run(asLord, new AddRank(k, "Knight"));
            String knight = rankId(rt, k, "Knight");
            rt.engine.run(asLord, new GiveRank(k, id(gil), knight));
            rt.engine.run(asLord, new SetRankPower(k, knight, Power.CLAIM, true));
            List<String> lines = charter.mc.cmd.OpCommands.rankLines(rt, k);
            helper.assertTrue(lines.size() == 3, "An edited kingdom with three ranks does not show three lines: " + lines);
            String gilName = rt.describe.player(id(gil));
            helper.assertTrue(lines.get(1).startsWith("Knight (1): " + gilName + ". ")
                    && lines.get(1).contains(charter.mc.Text.get("book.power.claim")), "The Knight line is wrong: " + lines.get(1));
            helper.assertTrue(lines.get(0).contains(rt.describe.player(id(lord))), "The top rank does not name the Lord: " + lines.get(0));
            helper.assertTrue(lines.get(0).endsWith(charter.mc.Text.get("book.ranks.top_powers")), "The top rank says the Lord has no powers: " + lines.get(0));
            List<String> lordPage = charter.mc.cmd.OpCommands.playerLines(rt, new charter.mc.cmd.Resolve.Who(id(lord), rt.describe.player(id(lord)), lord));
            helper.assertTrue(lordPage.stream().noneMatch(l -> l.contains(charter.mc.Text.get("book.ranks.no_powers"))),
                    "The Lord's player page says they carry no powers: " + lordPage);
            // The player's own page (the typed player command and the Admin Book) says their rank.
            List<String> page = charter.mc.cmd.OpCommands.playerLines(rt, new charter.mc.cmd.Resolve.Who(id(gil), gilName, gil));
            helper.assertTrue(page.stream().anyMatch(l -> l.startsWith(charter.mc.Text.get("admin.player.rank", "kingdom",
                    rt.describe.org(k), "rank", "Knight", "powers", "").strip())), "The player page does not show the rank: " + page);
            String name = rt.state.org(k).orElseThrow().name();
            int shown = kit.server.getCommands().getDispatcher().execute("charter admin faction ranks \"" + name + "\"",
                    kit.server.createCommandSourceStack());
            helper.assertTrue(shown == 3, "The typed command did not print the three ranks: " + shown);
            helper.succeed();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new AssertionError("The ranks command did not parse: " + e.getMessage());
        }
    }
}
