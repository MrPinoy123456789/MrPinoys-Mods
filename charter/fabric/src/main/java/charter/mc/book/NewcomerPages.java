package charter.mc.book;

import charter.gov.Standing;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.cmd.Chat;
import charter.org.JoinRequest;
import charter.org.Organization;
import charter.org.actions.Apply;
import charter.org.actions.ChooseMercenaryRace;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The pages of the Charter book for a player who belongs to no faction: ask to
 * join one, or found one. Like the member pages, each is built from live state on
 * every open.
 */
public final class NewcomerPages {

    private static final int FACTIONS_MAX = 20;

    private NewcomerPages() {
    }

    private static CharterRuntime rt() {
        return CharterServer.runtime();
    }

    private static PlayerId me(ServerPlayer player) {
        return new PlayerId(player.getUUID());
    }

    private static BookButton go(ServerPlayer player, String label, Consumer<ServerPlayer> page) {
        return BookDialogs.act(player, label, null, page);
    }

    private static List<net.minecraft.network.chat.Component> lines(String notice, List<net.minecraft.network.chat.Component> body) {
        return BookDialogs.withNotice(notice, body);
    }

    /** A page that is for players without a faction: a player who has since joined one goes to their Home. */
    private static boolean alone(ServerPlayer player) {
        if (rt().state.membership(me(player)).isEmpty()) return true;
        MemberPages.home(player, null);
        return false;
    }

    // ---- Home ----

    /** Invitations shown as their own button on Home; with more, one "Invitations (N)" button opens Needs you. */
    private static final int INVITES_ON_HOME = 1;

    public static void home(ServerPlayer player, String notice) {
        if (!alone(player)) return;
        CharterRuntime rt = rt();
        List<net.minecraft.network.chat.Component> body = new ArrayList<>();
        Optional<JoinRequest> asked = rt.state.joinRequestOf(me(player));
        String line = asked.isPresent() ? Text.get("book.newcomer.waiting", "org", rt.describe.org(asked.get().org()))
                : Text.get("book.newcomer.line");
        Optional<OrgId> race = rt.state.raceOf(me(player));
        if (race.isPresent()) line += " " + Text.get("book.merc.status", "race", rt.state.raceLabel(race.get()));
        body.add(BookDialogs.text(line));
        // Travel first, as on a member's Home; a newcomer has no keep or outpost to go to.
        List<BookButton> buttons = new ArrayList<>(TravelButtons.of(player, false, NewcomerPages::home));
        // An invitation is accepted here: the chat link for it cannot be clicked on Bedrock.
        List<Needs.Need> invites = Needs.of(player);
        if (invites.size() <= INVITES_ON_HOME) {
            for (Needs.Need invite : invites) buttons.add(go(player, invite.label(), invite.open()).icon(BookIcons.JOIN));
        } else {
            buttons.add(go(player, Text.get("book.newcomer.invites", "n", invites.size()), p -> NeedsPages.page(p, null)).icon(BookIcons.NEEDS));
        }
        buttons.add(go(player, Text.get("book.button.join"), p -> join(p, null)).icon(BookIcons.FACTION));
        buttons.add(go(player, Text.get("book.button.merc"), p -> mercenary(p, null)).icon(BookIcons.WAR));
        // While the operators set the kingdoms, there is no founding to walk a newcomer through.
        if (rt.knobs.playerFounding) buttons.add(go(player, Text.get("book.button.found"), p -> found(p, null)).icon(BookIcons.KEEP));
        buttons.add(go(player, Text.get("book.button.more"), p -> more(p, null)).icon(BookIcons.MORE));
        BookDialogs.show(player, BookDialogs.list(Text.get("book.newcomer.title"), lines(notice, body), buttons, BookDialogs.close()));
    }

    /** A newcomer's More: the Realm Chronicle, the calendar, the glossary. */
    public static void more(ServerPlayer player, String notice) {
        MorePages.newcomer(player, notice);
    }

    /** One press accepts: the invitation itself was the question. A refusal comes back on {@code page}. */
    static void acceptInvite(ServerPlayer player, OrgId inviter, java.util.function.BiConsumer<ServerPlayer, String> page) {
        if (!alone(player)) return;
        String orgName = rt().describe.org(inviter);
        BookRouter.run(player, new charter.org.actions.AcceptInvite(me(player), inviter), line -> {
            if (line != null) page.accept(player, line);
            else MemberPages.home(player, BookResult.ok(Text.get("join.done", "org", orgName)));
        });
    }

    // ---- Mercenary ----

    /** Take the race of an ordinary kingdom without joining it. Kingdoms the operators keep for themselves are not listed. */
    public static void mercenary(ServerPlayer player, String notice) {
        if (!alone(player)) return;
        CharterRuntime rt = rt();
        Optional<OrgId> current = rt.state.raceOf(me(player));
        List<net.minecraft.network.chat.Component> body = new ArrayList<>();
        body.add(BookDialogs.text(Text.get("book.merc.intro")));
        body.add(BookDialogs.text(Text.get("book.merc.rules")));
        if (current.isPresent()) body.add(BookDialogs.text(Text.get("book.merc.status", "race", rt.state.raceLabel(current.get()))));
        List<BookButton> buttons = new ArrayList<>();
        // Someone who already has a race can be a mercenary of it; someone with none chooses from the ordinary kingdoms.
        List<Organization> offered = current.isPresent() ? rt.state.org(current.get()).stream().toList()
                : rt.state.orgs().stream().filter(o -> o.isTopLevel() && o.exists() && !o.npc && !o.adminKingdom)
                        .sorted(Comparator.comparing(Organization::raceLabel)).toList();
        for (Organization org : offered) {
            OrgId id = org.id;
            buttons.add(go(player, BookDialogs.clip(org.raceLabel(), BookScreen.MAX_LABEL), p -> mercConfirm(p, id)).icon(BookIcons.WAR));
        }
        if (current.isEmpty() && buttons.isEmpty()) body.add(BookDialogs.text(Text.get("book.merc.none")));
        BookDialogs.show(player, BookDialogs.list(Text.get("book.merc.title"), lines(notice, body), buttons,
                BookDialogs.back(player, p -> home(p, null))));
    }

    /** The race is for good, so it asks once, with the race as the verb. */
    public static void mercConfirm(ServerPlayer player, OrgId id) {
        if (!alone(player)) return;
        Optional<Organization> org = rt().state.org(id);
        if (org.isEmpty()) {
            mercenary(player, Text.get("book.join.gone"));
            return;
        }
        String race = org.get().raceLabel();
        BookButton yes = go(player, Text.get("book.merc.verb", "race", BookDialogs.clip(race, 14)), p -> BookRouter.run(p,
                new ChooseMercenaryRace(me(p), id),
                line -> mercenary(p, BookResult.of(line, Text.get("book.merc.done", "race", race))))).icon(BookIcons.YES);
        BookDialogs.show(player, BookDialogs.confirm(Text.get("book.merc.title"),
                List.of(BookDialogs.text(Text.get("book.merc.confirm", "race", race))), yes,
                BookDialogs.back(player, p -> mercenary(p, null))));
    }

    // ---- Join a faction ----

    public static void join(ServerPlayer player, String notice) {
        if (!alone(player)) return;
        CharterRuntime rt = rt();
        List<Organization> orgs = rt.state.orgs().stream().filter(o -> o.isTopLevel() && o.exists() && !o.npc && !o.adminKingdom)
                .sorted(Comparator.comparingInt((Organization o) -> rt.state.members(o.id).size()).reversed()).toList();
        List<net.minecraft.network.chat.Component> body = new ArrayList<>();
        List<BookButton> buttons = new ArrayList<>();
        if (orgs.isEmpty()) {
            body.add(BookDialogs.text(Text.get("book.join.none")));
            if (rt.knobs.playerFounding) buttons.add(go(player, Text.get("book.button.found"), p -> found(p, null)));
        }
        for (Organization org : orgs.stream().limit(FACTIONS_MAX).toList()) {
            OrgId id = org.id;
            String label = Text.get("book.join.entry", "org", BookDialogs.clip(org.name(), 10), "race", BookDialogs.clip(org.raceLabel(), 8));
            buttons.add(go(player, label, p -> faction(p, id, null)).icon(BookIcons.FACTION));
        }
        if (orgs.size() > FACTIONS_MAX) body.add(BookDialogs.text(Text.get("book.join.more")));
        BookDialogs.show(player, BookDialogs.list(Text.get("book.join.title"), lines(notice, body), buttons,
                BookDialogs.back(player, p -> home(p, null))));
    }

    private static String formText(Standing.Form form) {
        return Text.get(switch (form) {
            case COMMUNE -> "book.form.commune";
            case LORDSHIP -> "book.form.lordship";
            case CO_LORDSHIP -> "book.form.co_lordship";
            case COUNCIL -> "book.form.council_short";
        });
    }

    // ---- One faction ----

    /** A kingdom in three lines: what it is, what joining makes you, and who rules. Then Ask to join. */
    public static void faction(ServerPlayer player, OrgId id, String notice) {
        if (!alone(player)) return;
        CharterRuntime rt = rt();
        if (rt.state.org(id).isEmpty()) {
            join(player, Text.get("book.join.gone"));
            return;
        }
        long now = rt.clock.now();
        Standing.View v = Standing.asNewcomer(rt.state, rt.engine, id, now);
        Organization org = rt.state.org(id).orElseThrow();
        List<net.minecraft.network.chat.Component> body = new ArrayList<>();
        long keeps = rt.state.coresOf(id).stream().filter(c -> c.isKeep()).count();
        body.add(BookDialogs.text(Component.literal(Text.get("book.join.about", "form", formText(v.form()),
                "members", rt.state.members(id).size(), "keeps", keeps)).withStyle(ChatFormatting.GOLD)));
        body.add(BookDialogs.text(Text.get(org.mixedRace ? "book.freedoms.mixed_open" : "book.freedoms.mixed_closed",
                "org", org.name(), "race", org.raceLabel())));
        // Say plainly what a member can and cannot do about their ruler.
        String rule = Text.get(v.form() == Standing.Form.COMMUNE ? "book.join.commune_rule"
                : rt.knobs.playerFounding ? "book.join.no_vote" : "book.join.no_vote_set");
        List<BookButton> buttons = new ArrayList<>();
        Optional<JoinRequest> asked = rt.state.joinRequestOf(me(player));
        if (asked.isPresent() && asked.get().org().equals(id)) {
            rule += " " + Text.get("book.join.waiting");
        } else if (asked.isEmpty()) {
            buttons.add(go(player, Text.get("book.button.ask"), p -> BookRouter.run(p, new Apply(me(p), id),
                    line -> faction(p, id, BookResult.of(line, Text.get("book.join.asked", "org", rt.describe.org(id)))))).icon(BookIcons.JOIN));
        }
        body.add(BookDialogs.text(rule));
        BookDialogs.show(player, BookDialogs.list(v.orgName(), lines(notice, body), buttons,
                BookDialogs.back(player, p -> join(p, null))));
    }

    // ---- Found your own ----

    private static final int STEPS = 6;

    /** Which steps of founding are done by what the player carries now. The last is never done here. */
    public static boolean[] foundSteps(ServerPlayer player) {
        CharterRuntime rt = rt();
        boolean banner = false, named = false, lodestone = false, nexus = false;
        int diamonds = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            if (NexusItems.foundingName(stack).isPresent()) nexus = true;
            else if (stack.is(ItemTags.BANNERS) && !stack.has(DataComponents.CUSTOM_DATA)) {
                banner = true;
                if (stack.has(DataComponents.CUSTOM_NAME)) named = true;
            } else if (stack.is(Items.LODESTONE) && !stack.has(DataComponents.CUSTOM_DATA)) lodestone = true;
            if (stack.is(Items.DIAMOND)) diamonds += stack.getCount();
        }
        // A crafted founding Nexus has already used the banner and the lodestone up.
        return new boolean[]{banner || nexus, named || nexus, lodestone || nexus, nexus, diamonds >= rt.knobs.keepPriceDiamonds, false};
    }

    static int diamondsCarried(ServerPlayer player) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(Items.DIAMOND)) n += stack.getCount();
        }
        return n;
    }

    public static void found(ServerPlayer player, String notice) {
        if (!alone(player)) return;
        CharterRuntime rt = rt();
        boolean[] done = foundSteps(player);
        String[] labels = {
                Text.get("book.found.step_banner"), Text.get("book.found.step_name"), Text.get("book.found.step_lodestone"),
                Text.get("book.found.step_craft"),
                Text.get("book.found.step_diamonds", "price", rt.knobs.keepPriceDiamonds, "have", diamondsCarried(player)),
                Text.get("book.found.step_place")};
        List<net.minecraft.network.chat.Component> body = new ArrayList<>();
        body.add(BookDialogs.text(Text.get("book.found.intro")));
        boolean current = true;
        for (int i = 0; i < STEPS; i++) {
            Component line;
            if (done[i]) {
                line = Component.literal("✔ " + labels[i]).withStyle(ChatFormatting.GREEN);
            } else if (current) {
                line = Component.literal(labels[i]).withStyle(ChatFormatting.YELLOW);
                current = false;
            } else {
                line = Component.literal(labels[i]).withStyle(ChatFormatting.GRAY);
            }
            body.add(BookDialogs.text(line));
        }
        long lastFounded = rt.state.knownPlayer(me(player)).map(r -> r.lastFoundedAt).orElse(0L);
        long left = lastFounded == 0 ? 0 : lastFounded + charter.time.Durations.days(rt.knobs.foundSecedeCooldownDays) - rt.clock.now();
        if (left > 0) body.add(BookDialogs.text(Text.get("book.found.cooldown", "time", Chat.duration(left))));
        BookDialogs.show(player, BookDialogs.list(Text.get("book.found.title"), lines(notice, body), List.of(),
                BookDialogs.back(player, p -> home(p, null))));
    }
}
