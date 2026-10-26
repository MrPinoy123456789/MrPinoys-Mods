#!/usr/bin/env python3
"""Turn Charter's siege log into a timeline per siege, for the playtest debrief.

The log is config/../siege-log/<yyyy-mm-dd>.jsonl on the server (PLAN.md section 8.3):
one JSON object per line with t (UTC), siegeT (siege clock, ms), ev, site, owner,
attacker and the event's own fields.

Usage:
    python siege_report.py <log file or folder> [more ...] [--state state.json] [--out report.md]
    python siege_report.py --selftest

For each keep that was besieged it prints when the siege began, how provisions
fell hour by hour (with the drain multiplier and mouths), every crossing counted
or refused with the reason, every deposit, and how it ended. Camps, holds, justice,
the operator's notes and admin commands follow. With --state, ids are shown as the
names found in Charter's state.json.

Standard library only.
"""

import argparse
import json
import os
import sys
from collections import Counter, OrderedDict, defaultdict

KEEP_EVENTS = {"keep_besieged", "keep_encircled", "keep_ring_broken", "keep_lifted", "keep_starving",
               "keep_fell", "fall_choice", "occupation_ended", "plunder", "truce_started",
               "drain_hour", "presence"}
CAMP_EVENTS = {"camp_planted", "camp_active", "camp_cut_off", "camp_empty", "camp_exposed", "camp_destroyed"}
JUSTICE_EVENTS = {"arrest", "sentence", "escape", "release", "execution", "banish_push"}
LAND_EVENTS = {"hold_empty", "claim_reassigned", "claim_orphaned", "grace_saved", "grace_expired"}
BAR = " .:-=+*#%@"


def read_lines(paths):
    """Every log line from the given files or folders, in time order. Bad lines are counted, not fatal."""
    files = []
    for path in paths:
        if os.path.isdir(path):
            files += sorted(os.path.join(path, f) for f in os.listdir(path) if f.endswith(".jsonl"))
        else:
            files.append(path)
    out, bad = [], 0
    for file in files:
        with open(file, encoding="utf-8") as fh:
            for raw in fh:
                raw = raw.strip()
                if not raw:
                    continue
                try:
                    out.append(json.loads(raw))
                except json.JSONDecodeError:
                    bad += 1
    out.sort(key=lambda e: (e.get("t", ""), e.get("siegeT", 0)))
    return out, bad


def names_from_state(path):
    """Id to name for everything in state.json that has both, walked without knowing its shape."""
    names = {}

    def walk(node):
        if isinstance(node, dict):
            ident, name = node.get("id"), node.get("name")
            centre = node.get("center")
            if isinstance(ident, str) and isinstance(name, str) and name:
                names[ident] = name
            elif isinstance(ident, str) and isinstance(centre, dict) and "x" in centre and "z" in centre:
                kind = str(node.get("kind", "site")).lower()
                names[ident] = "%s at %d, %d" % (kind, centre["x"] * 16 + 8, centre["z"] * 16 + 8)
            for v in node.values():
                walk(v)
        elif isinstance(node, list):
            for v in node:
                walk(v)

    with open(path, encoding="utf-8") as fh:
        walk(json.load(fh))
    return names


class Namer:
    def __init__(self, names):
        self.names = names or {}

    def __call__(self, ident):
        if ident is None:
            return "?"
        if isinstance(ident, list):
            return ", ".join(self(i) for i in ident) or "nobody"
        ident = str(ident)
        bare = ident.split(":", 1)[1] if ident.count(":") == 1 and len(ident) > 40 else ident
        # Charter's ids count up from a fixed prefix, so the tail is the part that differs.
        return self.names.get(bare, self.names.get(ident, "#" + bare[-6:]))


def hhmm(t):
    return t[11:19] if t and len(t) >= 19 else (t or "?")


def siege_hours(ms):
    return "%.1fh" % ((ms or 0) / 3_600_000)


def bar(percent):
    """A ten step bar for a provisions percentage."""
    p = max(0.0, min(100.0, float(percent or 0)))
    full = int(round(p / 10))
    return "[" + "#" * full + "." * (10 - full) + "]"


def carrier_kind(carrier):
    carrier = carrier or ""
    if carrier.startswith("cart:"):
        return "cart"
    if carrier.startswith("player:"):
        return "player"
    return carrier or "?"


def summarise(lines, name):
    """The report as a list of markdown lines."""
    keeps = OrderedDict()
    camps = OrderedDict()
    crossings = defaultdict(list)
    deposits = defaultdict(list)
    justice, land, notes, admin, other = [], [], [], [], []
    for e in lines:
        ev = e.get("ev")
        site = e.get("site")
        if ev in KEEP_EVENTS and site:
            keeps.setdefault(site, []).append(e)
        elif ev in CAMP_EVENTS and site:
            camps.setdefault(site, []).append(e)
        elif ev == "crossing":
            crossings[site].append(e)
        elif ev == "deposit":
            deposits[site].append(e)
        elif ev in JUSTICE_EVENTS:
            justice.append(e)
        elif ev in LAND_EVENTS:
            land.append(e)
        elif ev == "note":
            notes.append(e)
        elif ev == "admin":
            admin.append(e)
        else:
            other.append(e)

    out = ["# Siege report", ""]
    if lines:
        out.append("Log from %s to %s UTC, %d lines." % (lines[0].get("t", "?"), lines[-1].get("t", "?"), len(lines)))
        out.append("")

    out.append("## Sieges")
    out.append("")
    if not keeps:
        out.append("No keep was besieged.")
        out.append("")
    for site, events in keeps.items():
        owner = next((e.get("owner") for e in events if e.get("owner")), None)
        out.append("### Keep %s (held by %s)" % (name(site), name(owner)))
        out.append("")
        began = next((e for e in events if e["ev"] == "keep_besieged"), None)
        if began:
            out.append("- %s, siege %s: besieged by %s" % (hhmm(began.get("t")), siege_hours(began.get("siegeT")),
                                                          name(began.get("besiegers") or began.get("attacker"))))
        hours = [e for e in events if e["ev"] == "drain_hour"]
        if hours:
            first, last = hours[0], hours[-1]
            mults = [e.get("multiplier", 1) for e in hours]
            out.append("- provisions %.0f%% to %.0f%% over %d siege hours; multiplier %.2f to %.2f; mouths %s" % (
                first.get("percent", 0), last.get("percent", 0), len(hours), min(mults), max(mults),
                "/".join(str(m) for m in sorted({e.get("mouths") for e in hours if e.get("mouths") is not None}))))
        for e in events:
            ev = e["ev"]
            when = "%s, siege %s" % (hhmm(e.get("t")), siege_hours(e.get("siegeT")))
            if ev == "keep_besieged" or ev == "presence":
                continue
            if ev == "drain_hour":
                out.append("  - %s %s %5.1f%% x%.2f %s%s" % (when, bar(e.get("percent")), e.get("percent", 0),
                                                           e.get("multiplier", 1), e.get("supply", "").lower(),
                                                           ", starving" if e.get("starving") else ""))
            elif ev == "fall_choice":
                out.append("- %s: the besieger chose to %s" % (when, "occupy" if "untilSiege" in e else "raze"))
            elif ev == "keep_fell":
                out.append("- %s: **fell** to %s" % (when, name(e.get("attacker"))))
            elif ev == "plunder":
                out.append("- %s: plunder %s" % (when, e.get("amounts")))
            elif ev == "occupation_ended":
                out.append("- %s: occupation ended (%s)" % (when, e.get("why", "?")))
            else:
                out.append("- %s: %s" % (when, ev.replace("_", " ")))
        presence = [e for e in events if e["ev"] == "presence"]
        if presence:
            out.append("- presence moved the multiplier %d times: %s" % (
                len(presence), ", ".join("%.2f" % e.get("multiplier", 1) for e in presence[:12])
                + (" ..." if len(presence) > 12 else "")))
        cross = crossings.get(site, [])
        if cross:
            counted = Counter(carrier_kind(c.get("carrier")) for c in cross if c.get("result") == "counted")
            refused = Counter(c.get("reason", "?") for c in cross if c.get("result") == "refused")
            out.append("- crossings: %d counted (%s), %d refused (%s)" % (
                sum(counted.values()), ", ".join("%s %d" % kv for kv in counted.items()) or "none",
                sum(refused.values()), ", ".join("%s %d" % kv for kv in refused.items()) or "none"))
        dep = deposits.get(site, [])
        if dep:
            food = sum(d.get("nourishment", 0) for d in dep)
            out.append("- deposits: %d, %.0f nourishment in all, %d under siege" % (
                len(dep), food, sum(1 for d in dep if d.get("underSiege"))))
        out.append("")

    if camps:
        out.append("## Camps")
        out.append("")
        for site, events in camps.items():
            out.append("- camp %s of %s: %s" % (name(site), name(events[0].get("owner")), ", ".join(
                "%s %s" % (e["ev"].replace("camp_", ""), hhmm(e.get("t"))) for e in events)))
        out.append("")

    def section(title, events, render):
        if not events:
            return
        out.append("## " + title)
        out.append("")
        for e in events:
            out.append("- %s: %s" % (hhmm(e.get("t")), render(e)))
        out.append("")

    section("Land", land, lambda e: "%s %s" % (e["ev"].replace("_", " "), name(e.get("owner"))))
    def justice_line(e):
        who = e.get("prisoner") or e.get("player") or (e.get("subject") or {}).get("player")
        terms = ", ".join("%s %s" % (t.get("type", "?").lower(), siege_hours(t.get("durationMillis")) if "durationMillis" in t else "")
                          for t in e.get("terms", []))
        return "%s %s%s" % (e["ev"].replace("_", " "), name(who), (": " + terms.strip()) if terms else "")

    section("Justice", justice, justice_line)
    section("Operator notes", notes, lambda e: "%s: %s" % (e.get("who", "?"), e.get("text", "")))
    section("Admin commands", admin, lambda e: "%s: /%s" % (e.get("who", "?"), e.get("command", "")))
    if other:
        out.append("## Other events")
        out.append("")
        for ev, n in Counter(e.get("ev") for e in other).most_common():
            out.append("- %s: %d" % (ev, n))
        out.append("")
    return out


def selftest():
    keep, camp, a, b = "k" * 36, "c" * 36, "a" * 36, "b" * 36
    log = [
        {"t": "2026-10-25T20:00:00Z", "siegeT": 0, "ev": "admin", "who": "op", "command": "charter admin scenario run encirclement"},
        {"t": "2026-10-25T20:00:01Z", "siegeT": 7_200_000, "ev": "camp_active", "site": camp, "owner": b, "targets": [a]},
        {"t": "2026-10-25T20:00:01Z", "siegeT": 7_200_000, "ev": "keep_besieged", "site": keep, "owner": a, "besiegers": [b]},
        {"t": "2026-10-25T20:00:01Z", "siegeT": 7_200_000, "ev": "drain_hour", "site": keep, "owner": a, "percent": 30.0,
         "multiplier": 1.0, "mouths": 3, "supply": "ENCIRCLED"},
        {"t": "2026-10-25T20:02:30Z", "siegeT": 10_800_000, "ev": "drain_hour", "site": keep, "owner": a, "percent": 28.8,
         "multiplier": 1.2, "mouths": 3, "supply": "ENCIRCLED", "starving": False},
        {"t": "2026-10-25T20:02:31Z", "siegeT": 10_800_000, "ev": "presence", "site": keep, "owner": a, "multiplier": 1.2},
        {"t": "2026-10-25T20:03:00Z", "siegeT": 11_000_000, "ev": "crossing", "site": keep, "owner": a, "carrier": "player:x",
         "result": "refused", "reason": "not_open"},
        {"t": "2026-10-25T20:03:10Z", "siegeT": 11_100_000, "ev": "note", "who": "op", "text": "tried a tunnel"},
        {"t": "2026-10-25T20:30:00Z", "siegeT": 90_000_000, "ev": "keep_fell", "site": keep, "owner": a, "attacker": b},
        "not json",
    ]
    import tempfile
    with tempfile.TemporaryDirectory() as d:
        with open(os.path.join(d, "2026-10-25.jsonl"), "w", encoding="utf-8") as fh:
            for e in log:
                fh.write((e if isinstance(e, str) else json.dumps(e)) + "\n")
        lines, bad = read_lines([d])
        report = "\n".join(summarise(lines, Namer({keep: "Ashfall Keep", a: "Ashfall", b: "Brinewatch"})))
    checks = [
        (bad == 1, "a bad line is counted, not fatal"),
        ("### Keep Ashfall Keep (held by Ashfall)" in report, "the keep is named from the state"),
        ("besieged by Brinewatch" in report, "the besieger is named"),
        ("provisions 30% to 29% over 2 siege hours" in report, "the provisions summary"),
        ("1 refused (not_open 1)" in report, "refused crossings with their reason"),
        ("**fell** to Brinewatch" in report, "the fall"),
        ("tried a tunnel" in report, "operator notes"),
        ("/charter admin scenario run encirclement" in report, "admin commands"),
        (chr(0x2014) not in report and " " + "-" * 2 + " " not in report, "no dashes in the report"),
    ]
    failed = [why for ok, why in checks if not ok]
    for why in failed:
        print("FAIL", why)
    print("siege_report selftest: %d passed, %d failed" % (len(checks) - len(failed), len(failed)))
    if failed:
        print(report)
    return 1 if failed else 0


def main(argv):
    parser = argparse.ArgumentParser(description="Charter siege log to a timeline per siege.")
    parser.add_argument("paths", nargs="*", help="log files or siege-log folders")
    parser.add_argument("--state", help="Charter's state.json, to show names instead of ids")
    parser.add_argument("--out", help="write the report here instead of printing it")
    parser.add_argument("--selftest", action="store_true", help="run the built-in check and exit")
    args = parser.parse_args(argv)
    if args.selftest:
        return selftest()
    if not args.paths:
        parser.error("give at least one log file or folder")
    lines, bad = read_lines(args.paths)
    name = Namer(names_from_state(args.state) if args.state else None)
    report = summarise(lines, name)
    if bad:
        report.append("(%d lines could not be read)" % bad)
    text = "\n".join(report) + "\n"
    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            fh.write(text)
        print("Wrote %s (%d lines)." % (args.out, len(report)))
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
