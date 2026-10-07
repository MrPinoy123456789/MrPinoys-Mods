using System;
using System.Collections.Generic;
using System.Reflection;
using HarmonyLib;
using UnityEngine;

namespace MrPinoys.Float
{
    /// <summary>
    /// ZDO.Deserialize runs on the server only inside ZDOMan.RPC_ZDOData, and a client
    /// only sends the ZDOs it changed. A falling item bumps its data revision on every
    /// position change (ZDO.InternalSetPosition on the owner), so a sinking item lands
    /// here by itself. Position is already applied when the postfix runs.
    /// </summary>
    [HarmonyPatch(typeof(ZDO), nameof(ZDO.Deserialize))]
    internal static class Patch_ZDO_Deserialize
    {
        private static readonly int PlayerPrefab = "Player".GetStableHashCode();
        private static readonly Dictionary<ZDOID, int> LastEmoteId = new Dictionary<ZDOID, int>();

        private static void Postfix(ZDO __instance)
        {
            try
            {
                if (!ServerSide.IsServer() || !Crates.Ready) return;
                int prefab = __instance.GetPrefab();
                if (prefab == PlayerPrefab)
                {
                    WatchEmote(__instance);
                    return;
                }
                if (prefab == Crates.PrefabHash)
                {
                    if (Crates.IsOurs(__instance)) Crates.OnClientChange(__instance);
                    return;
                }
                if (FloatPlugin.AutoConvert.Value) Sweep.Consider(__instance, "client update");
            }
            catch (Exception e)
            {
                FloatPlugin.Log.LogWarning($"Item watch failed: {e}");
            }
        }

        /// <summary>
        /// Player.StartEmote bumps "emoteID" and writes the emote name to the player's own
        /// ZDO; StopEmote bumps it again with an empty name. A new ID with the configured
        /// name is one deliberate emote: convert every sunk item around the player.
        /// /point stays a diagnostic. Same pattern as valheim-sort's WatchEmote.
        /// </summary>
        private static void WatchEmote(ZDO player)
        {
            int emoteId = player.GetInt(ZDOVars.s_emoteID, 0);
            LastEmoteId.TryGetValue(player.m_uid, out int last);
            if (emoteId == last) return;
            bool firstSight = !LastEmoteId.ContainsKey(player.m_uid);
            LastEmoteId[player.m_uid] = emoteId;
            if (firstSight) return;
            string emote = player.GetString(ZDOVars.s_emote, "");
            if (emote.Length == 0) return;
            long uid = player.GetOwner();
            var pos = player.GetPosition();
            string want = (FloatPlugin.Emote.Value ?? "").Trim();
            if (want.Length > 0 && string.Equals(emote, want, StringComparison.OrdinalIgnoreCase))
            {
                Sweep.ConvertAround(uid, pos, Mathf.Clamp(FloatPlugin.EmoteRadiusMeters.Value, 1f, 64f), $"emote {emote}");
                return;
            }
            if (string.Equals(emote, "point", StringComparison.OrdinalIgnoreCase))
            {
                FloatPlugin.Log.LogInfo($"Diagnostic (/point by {ServerSide.PeerName(uid)} at {ServerSide.Fmt(pos)}): {Converter.Stats()}; {Crates.Nearest(pos)}");
            }
        }
    }

    /// <summary>
    /// Items already resting on the seabed never send anything. The server runs
    /// ZDOMan.ReleaseNearbyZDOS per peer every two seconds over exactly the ZDOs in that
    /// peer's near sectors (kept in the private m_tempNearObjects list), which makes it a
    /// free periodic sweep. The list is still populated when the postfix runs.
    /// </summary>
    [HarmonyPatch(typeof(ZDOMan), "ReleaseNearbyZDOS", typeof(Vector3), typeof(long))]
    internal static class Patch_ZDOMan_ReleaseNearbyZDOS
    {
        private static readonly FieldInfo NearField = AccessTools.Field(typeof(ZDOMan), "m_tempNearObjects");
        private static bool _warned;

        private static void Postfix(ZDOMan __instance, Vector3 refPosition, long uid)
        {
            try
            {
                if (!ServerSide.IsServer() || !Crates.Ready) return;
                // The server also runs this for its own session at the world origin; nothing to sweep there.
                if (uid == ZDOMan.GetSessionID()) return;
                if (NearField == null)
                {
                    if (!_warned) { _warned = true; FloatPlugin.Log.LogWarning("ZDOMan.m_tempNearObjects not found; the seabed sweep is off, only falling items are caught."); }
                    return;
                }
                var list = NearField.GetValue(__instance) as List<ZDO>;
                if (list == null) return;
                string how = "sweep near " + ServerSide.PeerName(uid);
                bool auto = FloatPlugin.AutoConvert.Value;
                foreach (var zdo in list)
                {
                    if (zdo.GetPrefab() == Crates.PrefabHash)
                    {
                        if (Crates.IsOurs(zdo)) FishRelease.Consider(zdo);
                        continue;
                    }
                    if (auto) Sweep.Consider(zdo, how);
                }
            }
            catch (Exception e)
            {
                FloatPlugin.Log.LogWarning($"Seabed sweep failed: {e}");
            }
        }
    }

    /// <summary>The one decision both hooks share: is this a sunk item we have not dealt with yet?</summary>
    internal static class Sweep
    {
        private static readonly HashSet<ZDOID> Noted = new HashSet<ZDOID>();
        private static readonly List<ZDO> Near = new List<ZDO>();

        /// <summary>The emote trigger: queue every sunk item within radius of a point (3x3 zones around it are searched).</summary>
        internal static void ConvertAround(long uid, Vector3 where, float radius, string reason)
        {
            if (!FloatPlugin.Enabled.Value || ZDOMan.instance == null) return;
            Near.Clear();
            ZDOMan.instance.FindSectorObjects(ZoneSystem.GetZone(where), new SimulationDistance(1, 0, classic: true), Near);
            int found = 0, queued = 0, skipped = 0;
            foreach (var zdo in Near)
            {
                if (!Items.IsItemPrefab(zdo.GetPrefab())) continue;
                if (ServerSide.Flat(zdo.GetPosition(), where) > radius) continue;
                if (!Items.IsSunkItem(zdo, out string why))
                {
                    if (why != null) { skipped++; ServerSide.Trace($"  {zdo.m_uid} ({Items.PrefabName(zdo.GetPrefab())}) at {ServerSide.Fmt(zdo.GetPosition())} left alone: {why}"); }
                    continue;
                }
                found++;
                if (Converter.WasRefused(zdo.m_uid)) { skipped++; continue; }
                if (FloatPlugin.Schedule(zdo.m_uid))
                {
                    queued++;
                    ServerSide.Trace($"Item {zdo.m_uid} ({Items.PrefabName(zdo.GetPrefab())}) at {ServerSide.Fmt(zdo.GetPosition())} seen {Items.Depth(zdo.GetPosition()):0.0} m under water ({reason}); converting in {FloatPlugin.BatchWindowSeconds.Value:0.#} s");
                }
            }
            ServerSide.Trace($"{reason} by {ServerSide.PeerName(uid)} at {ServerSide.Fmt(where)}: {found} sunk item(s) within {radius:0} m, {queued} queued, {skipped} left alone; converting in {FloatPlugin.BatchWindowSeconds.Value:0.#} s");
            if (FloatPlugin.Notify.Value)
            {
                ServerSide.Message(uid, found == 0 ? $"No sunk items within {radius:0} m" : $"Crating {found} sunk item(s) nearby");
            }
        }

        internal static void Consider(ZDO zdo, string how)
        {
            if (!FloatPlugin.Enabled.Value) return;
            if (!Items.IsSunkItem(zdo, out string why))
            {
                // Only worth a line when it is an item under water that we are deliberately leaving.
                if (why != null && Noted.Add(zdo.m_uid))
                    ServerSide.Trace($"Item {zdo.m_uid} ({Items.PrefabName(zdo.GetPrefab())}) at {ServerSide.Fmt(zdo.GetPosition())} is under water but left alone: {why}");
                return;
            }
            if (Converter.WasRefused(zdo.m_uid)) return;
            if (FloatPlugin.Schedule(zdo.m_uid))
                ServerSide.Trace($"Item {zdo.m_uid} ({Items.PrefabName(zdo.GetPrefab())}) at {ServerSide.Fmt(zdo.GetPosition())} seen {Items.Depth(zdo.GetPosition()):0.0} m under water ({how}); converting in {FloatPlugin.BatchWindowSeconds.Value:0.#} s");
        }
    }
}
