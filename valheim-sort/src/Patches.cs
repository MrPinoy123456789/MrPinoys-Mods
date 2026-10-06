using System;
using System.Collections.Generic;
using HarmonyLib;
using UnityEngine;

namespace MrPinoys.Sort
{
    /// <summary>What the server last saw on a chest, to tell an open, a close and a content change apart.</summary>
    internal sealed class ChestState
    {
        public int InUse;
        public ulong ItemsHash;
    }

    /// <summary>
    /// ZDO.Deserialize runs on the server only inside ZDOMan.RPC_ZDOData, and a client
    /// only sends the ZDOs it changed itself. So every call here for a chest means a
    /// client opened it, closed it, moved items in it or took ownership of it. That is
    /// the trigger; no polling.
    /// </summary>
    [HarmonyPatch(typeof(ZDO), nameof(ZDO.Deserialize))]
    internal static class Patch_ZDO_Deserialize
    {
        private static readonly Dictionary<ZDOID, ChestState> Seen = new Dictionary<ZDOID, ChestState>();
        private static readonly Dictionary<ZDOID, int> LastEmoteId = new Dictionary<ZDOID, int>();
        // Player key (profile ID, see ServerSide.PlayerKey) -> the chest that player opened most recently.
        private static readonly Dictionary<long, ZDOID> LastOpened = new Dictionary<long, ZDOID>();
        private static readonly int PlayerPrefab = "Player".GetStableHashCode();

        private static void Postfix(ZDO __instance)
        {
            try
            {
                if (!ServerSide.IsServer()) return;
                if (__instance.GetPrefab() == PlayerPrefab)
                {
                    WatchEmote(__instance);
                    return;
                }
                if (!SortPlugin.SortOnClose.Value) return;
                var kind = Chests.KindOf(__instance);
                if (kind == null) return;

                int inUse = __instance.GetInt(ZDOVars.s_inUse, 0);
                byte[] blob = __instance.GetByteArray(ZDOVars.s_items);
                ulong hash = ItemCodec.Hash(blob);
                long owner = __instance.GetOwner();
                var id = __instance.m_uid;

                if (!Seen.TryGetValue(id, out var state))
                {
                    state = new ChestState { InUse = -1, ItemsHash = 0 };
                    Seen[id] = state;
                }
                bool contentChanged = hash != state.ItemsHash;
                bool ourEcho = Sorter.LastWritten.TryGetValue(id, out ulong written) && written == hash;

                if (inUse == 1 && state.InUse != 1)
                {
                    ServerSide.Trace($"Chest {id} ({kind.Name}) opened by {ServerSide.PeerName(owner)}");
                    if (owner != 0L) LastOpened[ServerSide.PlayerKey(owner)] = id;
                    SortPlugin.Unschedule(id);
                }
                else if (inUse == 0 && state.InUse == 1)
                {
                    Summarize(blob, out int stacks, out int items);
                    ServerSide.Trace($"Chest {id} ({kind.Name}) closed; {stacks} stacks, {items} items; owner {ServerSide.PeerName(owner)}");
                    if (!ourEcho) SortPlugin.Schedule(id);
                }
                else if (inUse == 0 && contentChanged && !ourEcho)
                {
                    // Changed without an open/close pair the server saw: hover stack-all, take-all, or a close
                    // whose open we missed (first sight after a restart, or both inside one send cycle).
                    Summarize(blob, out int stacks, out int items);
                    ServerSide.Trace($"Chest {id} ({kind.Name}) changed while closed ({(state.InUse < 0 ? "first seen" : "no open seen")}); {stacks} stacks, {items} items; owner {ServerSide.PeerName(owner)}");
                    SortPlugin.Schedule(id);
                }
                else if (inUse == 0 && ourEcho && contentChanged)
                {
                    ServerSide.Trace($"Chest {id} ({kind.Name}): client echoed our sorted blob; ignored.");
                }

                state.InUse = inUse;
                state.ItemsHash = hash;
            }
            catch (Exception e)
            {
                SortPlugin.Log.LogWarning($"Chest watch failed: {e}");
            }
        }

        /// <summary>
        /// Player.StartEmote bumps "emoteID" and writes the emote name to the player's own
        /// ZDO; StopEmote bumps it again with an empty name. A new ID with the configured
        /// name is one deliberate emote by that player.
        /// </summary>
        private static void WatchEmote(ZDO player)
        {
            if (!SortPlugin.EmoteTrigger.Value) return;
            int emoteId = player.GetInt(ZDOVars.s_emoteID, 0);
            LastEmoteId.TryGetValue(player.m_uid, out int last);
            if (emoteId == last) return;
            bool firstSight = !LastEmoteId.ContainsKey(player.m_uid);
            LastEmoteId[player.m_uid] = emoteId;
            if (firstSight) return;

            string emote = player.GetString(ZDOVars.s_emote, "");
            if (emote.Length == 0) return;
            string want = (SortPlugin.Emote.Value ?? "").Trim();
            long uid = player.GetOwner();
            if (!string.Equals(emote, want, StringComparison.OrdinalIgnoreCase))
            {
                ServerSide.Trace($"Emote '{emote}' by {ServerSide.PeerName(uid)}; not '{want}', ignored.");
                return;
            }
            if (!SortPlugin.EmoteLastOpened.Value)
            {
                SortNear(uid, player.GetPosition(), Mathf.Clamp(SortPlugin.EmoteRadius.Value, 1f, 64f), $"emote {emote}");
                return;
            }
            long key = ServerSide.PlayerKey(uid);
            if (!LastOpened.TryGetValue(key, out var chestId))
            {
                ServerSide.Trace($"Emote '{emote}' by {ServerSide.PeerName(uid)}: no chest opened by them since the server started; nothing to sort.");
                if (SortPlugin.Notify.Value) ServerSide.Message(uid, "Open a chest first, then do the emote to sort it");
                return;
            }
            var chest = ZDOMan.instance?.GetZDO(chestId);
            if (chest == null)
            {
                ServerSide.Trace($"Emote '{emote}' by {ServerSide.PeerName(uid)}: last opened chest {chestId} no longer exists.");
                LastOpened.Remove(key);
                if (SortPlugin.Notify.Value) ServerSide.Message(uid, "Your last opened chest is gone");
                return;
            }
            float distance = Chests.Flat(chest.GetPosition(), player.GetPosition());
            ServerSide.Trace($"Emote '{emote}' by {ServerSide.PeerName(uid)}: last opened chest {chestId} ({Chests.KindOf(chest)?.Name}) at {ServerSide.Fmt(chest.GetPosition())}, {distance:0}m away, in use {Chests.InUse(chest)}.");
            float maxDistance = SortPlugin.EmoteMaxDistance.Value;
            if (maxDistance > 0f && distance > maxDistance)
            {
                if (SortPlugin.Notify.Value) ServerSide.Message(uid, $"Your last opened chest is {distance:0}m away, too far to sort");
                return;
            }
            bool inUseBefore = Chests.InUse(chest);
            bool sorted = Sorter.SortChest(chest, $"emote {emote}");
            if (SortPlugin.Notify.Value)
            {
                ServerSide.Message(uid, sorted ? "Chest sorted" : (inUseBefore ? "Chest is in use" : "Chest already sorted"));
            }
        }

        /// <summary>Sort every closed chest within radius of a point; used by the emote and the map ping.</summary>
        internal static void SortNear(long uid, Vector3 where, float radius, string reason)
        {
            var chests = Chests.Near(where, radius);
            ServerSide.Trace($"{reason} by {ServerSide.PeerName(uid)} at {ServerSide.Fmt(where)}: {chests.Count} chest(s) within {radius:0}m.");
            int sorted = 0;
            foreach (var zdo in chests)
            {
                ServerSide.Trace($"  {Chests.KindOf(zdo)?.Name} {zdo.m_uid} at {ServerSide.Fmt(zdo.GetPosition())}, {Chests.Flat(zdo.GetPosition(), where):0}m away, in use {Chests.InUse(zdo)}, owner {ServerSide.PeerName(zdo.GetOwner())}");
                if (Sorter.SortChest(zdo, reason)) sorted++;
            }
            if (SortPlugin.Notify.Value && chests.Count > 0)
            {
                ServerSide.Message(uid, sorted == 0 ? $"{chests.Count} chest(s) nearby, nothing to sort" : $"Sorted {sorted} of {chests.Count} chest(s) nearby");
            }
        }

        private static void Summarize(byte[] blob, out int stacks, out int items)
        {
            stacks = 0;
            items = 0;
            if (blob == null || blob.Length == 0) return;
            try
            {
                var list = ItemCodec.Parse(blob, out _);
                stacks = list.Count;
                foreach (var r in list) items += r.Stack;
            }
            catch (Exception e)
            {
                ServerSide.Trace($"Could not summarize items blob ({blob.Length} bytes): {e.Message}");
            }
        }
    }

    /// <summary>
    /// Map pings reach the server even from a solo player (Chat.SendPing targets everybody).
    /// Same sniff as valheim-back: deserialize a copy of the routed RPC, restore the read position.
    /// </summary>
    [HarmonyPatch(typeof(ZRoutedRpc), "RPC_RoutedRPC")]
    internal static class Patch_ZRoutedRpc_RPC_RoutedRPC
    {
        private static readonly int ChatMessageHash = "ChatMessage".GetStableHashCode();
        private const int TalkerPing = 3;

        private static void Prefix(ZRpc rpc, ZPackage pkg)
        {
            if (!ServerSide.IsServer() || pkg == null) return;
            if (!SortPlugin.PingTrigger.Value) return;

            int pos = pkg.GetPos();
            try
            {
                var data = new ZRoutedRpc.RoutedRPCData();
                data.Deserialize(pkg);
                if (data.m_methodHash != ChatMessageHash || !data.m_targetZDO.IsNone()) return;

                var p = data.m_parameters;
                Vector3 where = p.ReadVector3();
                int type = p.ReadInt();
                if (type != TalkerPing) return;

                OnPing(data.m_senderPeerID, where);
            }
            catch (Exception e)
            {
                SortPlugin.Log.LogWarning($"Could not inspect routed RPC: {e}");
            }
            finally
            {
                pkg.SetPos(pos);
            }
        }

        private static void OnPing(long senderUid, Vector3 where)
        {
            Patch_ZDO_Deserialize.SortNear(senderUid, where, Mathf.Clamp(SortPlugin.PingRadius.Value, 1f, 64f), "ping");
        }
    }
}
