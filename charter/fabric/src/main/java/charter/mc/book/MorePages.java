package charter.mc.book;

import charter.gov.Standing;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * "More": the pages a player seldom needs, so Home stays short. A member's More holds War, the Chronicle, the
 * calendar, borders, the glossary and leaving; it also holds Where I stand and My faction when Home had no room for
 * them ({@link MemberPages#layout}). A newcomer's More holds the Realm Chronicle, the calendar and the glossary.
 * The glossary replaces every hover: a word is explained in a screen, never in a tooltip.
 */
public final class MorePages {

    /** The glossary terms, in order: a lang key stem each (book.words.NAME and book.words.NAME_text). */
    static final List<String> TERMS = List.of("lordship", "commune", "council", "keep", "outpost", "nexus", "marks", "held");

    private MorePages() {
    }

    public static void page(ServerPlayer player, String notice) {
        if (!CharterServer.running()) return;
        CharterRuntime rt = CharterServer.runtime();
        if (rt.state.membership(new charter.id.PlayerId(player.getUUID())).isEmpty()) {
            newcomer(player, notice);
            return;
        }
        MemberPages.Layout layout = MemberPages.layout(player);
        List<BookButton> buttons = new ArrayList<>();
        if (!layout.standOnHome()) buttons.add(BookDialogs.act(player, Text.get("book.button.stand"), null, p -> StandPages.page(p, null)).icon(BookIcons.STAND));
        if (!layout.factionOnHome()) buttons.add(BookDialogs.act(player, Text.get("book.button.faction"), null, p -> MemberPages.faction(p, null)).icon(BookIcons.FACTION));
        buttons.add(BookDialogs.act(player, Text.get("book.button.war"), null, p -> WarPages.page(p, null)).icon(BookIcons.WAR));
        buttons.add(BookDialogs.act(player, Text.get("book.button.chronicle"), null, MemberPages::chronicle).icon(BookIcons.CHRONICLE));
        buttons.add(BookDialogs.act(player, Text.get("book.button.calendar"), null, MorePages::calendar).icon(BookIcons.CALENDAR));
        buttons.add(BookDialogs.act(player, Text.get("book.button.borders"), null, p -> {
            boolean on = charter.mc.land.Signals.toggleBorders(p.getUUID());
            page(p, BookResult.ok(Text.get(on ? "book.borders.on" : "book.borders.off")));
        }).icon(BookIcons.BORDERS));
        buttons.add(BookDialogs.act(player, Text.get("book.button.words"), null, p -> glossary(p, MorePages::page)).icon(BookIcons.WORDS));
        buttons.add(BookDialogs.act(player, Text.get("book.leave.button"), null, MemberPages::leaveConfirm).icon(BookIcons.LEAVE));
        if (rt.knobs.playerFounding) buttons.add(BreakawayPages.button(player));
        BookDialogs.show(player, BookDialogs.list(Text.get("book.more.title"), BookDialogs.withNotice(notice, List.of()), buttons,
                BookDialogs.back(player, p -> MemberPages.home(p, null))));
    }

    /** A newcomer's More. */
    static void newcomer(ServerPlayer player, String notice) {
        List<BookButton> buttons = new ArrayList<>();
        buttons.add(BookDialogs.act(player, Text.get("book.button.realm"), null, MemberPages::realmChronicle).icon(BookIcons.CHRONICLE));
        buttons.add(BookDialogs.act(player, Text.get("book.button.calendar"), null, MorePages::calendar).icon(BookIcons.CALENDAR));
        buttons.add(BookDialogs.act(player, Text.get("book.button.words"), null, p -> glossary(p, NewcomerPages::more)).icon(BookIcons.WORDS));
        BookDialogs.show(player, BookDialogs.list(Text.get("book.more.title"), BookDialogs.withNotice(notice, List.of()), buttons,
                BookDialogs.back(player, p -> NewcomerPages.home(p, null))));
    }

    /** "What do these words mean?": a button per word, each opening one short explanation. */
    public static void glossary(ServerPlayer player, java.util.function.BiConsumer<ServerPlayer, String> parent) {
        List<BookButton> buttons = new ArrayList<>();
        for (String term : TERMS) {
            buttons.add(BookDialogs.act(player, Text.get("book.words." + term), null, p -> word(p, term, parent)));
        }
        BookDialogs.show(player, BookDialogs.list(Text.get("book.button.words"), List.of(BookDialogs.text(Text.get("book.words.intro"))), buttons,
                BookDialogs.back(player, p -> parent.accept(p, null))));
    }

    private static void word(ServerPlayer player, String term, java.util.function.BiConsumer<ServerPlayer, String> parent) {
        BookDialogs.show(player, BookDialogs.notice(Text.get("book.words." + term), List.of(BookDialogs.text(Text.get("book.words." + term + "_text"))),
                BookDialogs.back(player, p -> glossary(p, parent))));
    }

    /** The year in plain words. Reached from More; Back returns there. */
    static void calendar(ServerPlayer player) {
        List<Component> body = List.of(
                BookDialogs.text(Text.get("book.calendar.seasons", "seasons", MemberPages.seasonNames())),
                BookDialogs.text(Text.get("book.calendar.days")));
        boolean member = CharterServer.running()
                && CharterServer.runtime().state.membership(new charter.id.PlayerId(player.getUUID())).isPresent();
        BookDialogs.show(player, BookDialogs.notice(Text.get("book.button.calendar"), body,
                BookDialogs.back(player, p -> {
                    if (member) page(p, null);
                    else newcomer(p, null);
                })));
    }
}
