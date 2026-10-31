# Charter Economy

**Strike coin, earn Valor, raise your kingdom's charter.**

A server-side Fabric economy, land claims and kingdom progression mod for the Kingdoms (charter)
server. Minecraft 26.2, JDK 25. Vanilla clients need nothing installed.

> **Status:** design only, 2026-10-31. No code yet.

| Currency | For | In short |
|---|---|---|
| Coin (Copper Mark 1, Iron Crown 8, Gold Sovereign 64) | Players | Money. Struck from metal at a capped Mint, traded freely |
| Valor | Players | A medal. Earned in dungeons and events, never traded |
| Renown | Kingdoms | Reputation. Earned alongside Valor, spent only on the charter |

- [SPEC.md](SPEC.md): the design.
- [REVIEW.md](REVIEW.md): the adversarial lead designer review and what it changed.
- [PLAN.md](PLAN.md): the build phases.

A sibling of [Cobble Economy](../cobbleeconomy), forked from its bank and shop. It
does not depend on Cobble Economy, and the two never run on the same server.
