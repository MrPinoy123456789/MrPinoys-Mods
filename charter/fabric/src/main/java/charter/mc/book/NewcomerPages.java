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
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.body.DialogBody;
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

    private static ActionButton go(ServerPlayer player, String label, Consumer<ServerPlayer> page) {
        return BookDialogs.act(player, label, null, page);
    }

    private static List<DialogBody> lines(String notice, List<DialogBody> body) {
        return BookDialogs.withNotice(notice, body);
    }

    /** A page that is for players without a faction: a player who has since joined one goes to their Home. */
    private static boolean alone(ServerPlayer player) {
        if (rt().state.membership(me(player)).isEmpty()) return true;
        MemberPages.home(player, null);
        return false;
    }

    // ---- Home ----

    public static void home(ServerPlayer player, String notice) {
        if (!alone(player)) return;
        CharterRuntime rt = rt();
        List<DialogBody> body = new ArrayList<>();
        Optional<JoinRequest> asked = rt.state.joinRequestOf(me(player));
        if (asked.isPresent()) {
            body.add(BookDialogs.text(Text.get("book.newcomer.waiting", "org", rt.describe.org(asked.get().org()))));
        } else {
            body.add(BookDialogs.text(Text.get("book.newcomer.intro")));
        }
        body.add(BookDialogs.text(Text.get("book.calendar.seasons", "seasons", MemberPages.seasonNames())));
        body.add(BookDialogs.text(Text.get("book.calendar.days")));
        List<ActionButton> buttons = List.of(
                go(player, Text.get("book.button.join"), p -> join(p, null)),
                go(player, Text.get("book.button.found"), p -> found(p, null)),
                go(player, Text.get("book.button.realm"), MemberPages::realmChronicle));
        BookDialogs.show(player, BookDialogs.list(Text.get("book.newcomer.title"), lines(notice, body), buttons, BookDialogs.close()));
    }

    // ---- Join a faction ----

    public static void join(ServerPlayer player, String notice) {
        if (!alone(player)) return;
        CharterRuntime rt = rt();
        List<Organization> orgs = rt.state.orgs().stream().filter(o -> o.isTopLevel() && o.exists() && !o.npc)
                .sorted(Comparator.comparingInt((Organization o) -> rt.state.members(o.id).size()).reversed()).toList();
        List<DialogBody> body = new ArrayList<>();
        List<ActionButton> buttons = new ArrayList<>();
        if (orgs.isEmpty()) {
            body.add(BookDialogs.text(Text.get("book.join.none")));
            buttons.add(go(player, Text.get("book.button.found"), p -> found(p, null)));
        }
        for (Organization org : orgs.stream().limit(FACTIONS_MAX).toList()) {
            OrgId id = org.id;
            String label = Text.get("book.join.entry", "org", org.name(), "members", rt.state.members(id).size(),
                    "form", formText(Standing.form(rt.state, id)));
            buttons.add(go(player, label, p -> faction(p, id, null)));
        }
        if (orgs.size() > FACTIONS_MAX) body.add(BookDialogs.text(Text.get("book.join.more")));
        BookDialogs.show(player, BookDialogs.list(Text.get("book.join.title"), lines(notice, body), buttons,
                BookDialogs.back(player, p -> home(p, null))));
    }

    private static String formText(Standing.Form form) {
        return Text.get(switch (form) {
            case COMMUNE -> "book.form.commune";
            case LORDSHIP -> "book.form.lordship";
            case COUNCIL -> "book.form.council_short";
        });
    }

    // ---- One faction ----

    public static void faction(ServerPlayer player, OrgId id, String notice) {
        if (!alone(player)) return;
        CharterRuntime rt = rt();
        if (rt.state.org(id).isEmpty()) {
            join(player, Text.get("book.join.gone"));
            return;
        }
        long now = rt.clock.now();
        Standing.View v = Standing.asNewcomer(rt.state, rt.engine, id, now);
        List<DialogBody> body = new ArrayList<>();
        long keeps = rt.state.coresOf(id).stream().filter(c -> c.isKeep()).count();
        body.add(BookDialogs.text(Text.get("book.join.about", "form", formText(v.form()),
                "members", rt.state.members(id).size(), "keeps", keeps)));
        // Only what an ordinary member lives under: who takes members in and removes them, who spends,
        // who declares war and signs treaties, who changes or ends the charter. The rest is one closing line.
        body.add(BookDialogs.text(Text.get("book.join.who_decides")));
        java.util.Set<charter.gov.PowerGroup> shown = java.util.EnumSet.of(charter.gov.PowerGroup.MEMBERS,
                charter.gov.PowerGroup.TREASURY, charter.gov.PowerGroup.WAR, charter.gov.PowerGroup.DIPLOMACY);
        List<Standing.Line> mine = v.freedoms().stream().filter(l -> shown.contains(l.group())).toList();
        for (String line : StandingText.joined(mine, true)) body.add(BookDialogs.text(line));
        boolean restByHands = v.freedoms().stream().anyMatch(l -> !shown.contains(l.group())
                && l.kind() != Standing.HolderKind.MEMBERS_BY_VOTE);
        if (restByHands) body.add(BookDialogs.text(Text.get("book.join.runs_keeps")));
        // Say plainly what a member can and cannot do about their ruler.
        body.add(BookDialogs.text(Text.get(v.form() == Standing.Form.COMMUNE ? "book.join.commune_rule" : "book.join.no_vote")));
        List<ActionButton> buttons = new ArrayList<>();
        Optional<JoinRequest> asked = rt.state.joinRequestOf(me(player));
        if (asked.isPresent() && asked.get().org().equals(id)) {
            body.add(BookDialogs.text(Text.get("book.join.waiting")));
        } else if (asked.isEmpty()) {
            buttons.add(go(player, Text.get("book.button.ask"), p -> BookRouter.run(p, new Apply(me(p), id),
                    line -> faction(p, id, line != null ? line : Text.get("book.join.asked", "org", rt.describe.org(id))))));
        }
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
        List<DialogBody> body = new ArrayList<>();
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
