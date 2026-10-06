using System.Collections.Generic;
using UnityEngine;

namespace MrPinoys.Back
{
    /// <summary>Where a trip goes: a tombstone in the world, or a recorded death position.</summary>
    internal sealed class Destination
    {
        public Vector3 Position;
        public Quaternion Rotation;
        public ZDO Tombstone;      // null for a recorded death
        public DeathRecord Record; // null for a tombstone

        public static Destination Of(ZDO tomb) => new Destination
        {
            Position = tomb.GetPosition(),
            Rotation = tomb.GetRotation(),
            Tombstone = tomb,
        };

        public static Destination Of(DeathRecord r) => new Destination
        {
            Position = r.Position,
            Rotation = r.Rotation,
            Record = r,
        };
    }

    /// <summary>
    /// Resolving peers, choosing a destination, enforcing cooldowns, counting pings,
    /// and firing the vanilla teleport RPC.
    /// </summary>
    internal static class Teleporter
    {
        private static readonly Dictionary<long, float> LastTrip = new Dictionary<long, float>();
        private static readonly Dictionary<long, List<float>> Pings = new Dictionary<long, List<float>>();
        private static readonly Dictionary<ZDOID, int> TripsTaken = new Dictionary<ZDOID, int>();

        // ---- lookups ----

        internal static ZNetPeer PeerForUid(long uid)
        {
            return ZNet.instance?.GetPeer(uid);
        }

        /// <summary>The peer's character ZDO, which the server always holds (unlike a Player object).</summary>
        internal static ZDO CharacterZdo(ZNetPeer peer)
        {
            if (peer == null || peer.m_characterID.IsNone() || ZDOMan.instance == null) return null;
            return ZDOMan.instance.GetZDO(peer.m_characterID);
        }

        internal static long PlayerIdForPeer(ZNetPeer peer)
        {
            if (peer == null) return 0L;
            if (peer.m_playerID != 0L) return peer.m_playerID;
            // 1.0 clients no longer send the PlayerID RPC, but PlayerProfile stamps it on the character ZDO.
            var zdo = CharacterZdo(peer);
            if (zdo != null)
            {
                long id = zdo.GetLong(ZDOVars.s_playerID, 0L);
                if (id != 0L) return id;
            }
            foreach (var p in Player.GetAllPlayers())
            {
                if (p.GetZDOID() == peer.m_characterID) return p.GetPlayerID();
            }
            return 0L;
        }

        internal static bool IsDead(ZNetPeer peer)
        {
            var zdo = CharacterZdo(peer);
            return zdo != null && zdo.GetBool(ZDOVars.s_dead, false);
        }

        internal static Player PlayerForPeer(ZNetPeer peer)
        {
            if (peer == null || peer.m_characterID.IsNone()) return null;
            foreach (var p in Player.GetAllPlayers())
            {
                if (p.GetZDOID() == peer.m_characterID) return p;
            }
            return null;
        }

        internal static ZNetPeer PeerForName(string name)
        {
            if (string.IsNullOrEmpty(name) || ZNet.instance == null) return null;
            foreach (var peer in ZNet.instance.GetPeers())
            {
                if (string.Equals(peer.m_playerName, name, System.StringComparison.OrdinalIgnoreCase)) return peer;
            }
            return null;
        }

        /// <summary>Default destination: newest own tombstone, else the recorded death if enabled.</summary>
        internal static Destination Default(long playerId, string name)
        {
            var tomb = Tombstones.Newest(playerId, name);
            if (tomb != null) return Destination.Of(tomb);
            if (BackPlugin.FallbackToDeathPoint.Value)
            {
                var r = BackPlugin.Deaths.Get(playerId);
                if (r != null) return Destination.Of(r);
            }
            return null;
        }

        /// <summary>Destination near a pinged point, or null when nothing of the player's is close.</summary>
        internal static Destination Near(long playerId, string name, Vector3 point)
        {
            float radius = BackPlugin.PingRadius.Value;
            var tomb = Tombstones.Nearest(playerId, name, point, radius);
            if (tomb != null) return Destination.Of(tomb);
            if (BackPlugin.FallbackToDeathPoint.Value)
            {
                var r = BackPlugin.Deaths.Get(playerId);
                if (r != null && Tombstones.Flat(r.Position, point) <= radius) return Destination.Of(r);
            }
            return null;
        }

        // ---- the trip itself ----

        /// <summary>
        /// Send the peer to the destination. Returns false with a player-readable reason
        /// when nothing happened.
        /// </summary>
        internal static bool TryGo(ZNetPeer peer, Destination dest, bool admin, out string why)
        {
            why = null;
            if (peer == null || !peer.IsReady())
            {
                why = "Player is not connected.";
                return false;
            }
            if (dest == null)
            {
                why = "No tombstone to return to.";
                return false;
            }

            long playerId = PlayerIdForPeer(peer);
            if (IsDead(peer))
            {
                why = "Respawn first.";
                return false;
            }

            if (!admin)
            {
                if (LastTrip.TryGetValue(playerId, out float last))
                {
                    float wait = BackPlugin.CooldownSeconds.Value - (Time.realtimeSinceStartup - last);
                    if (wait > 0f)
                    {
                        why = $"Wait {Mathf.CeilToInt(wait)}s before travelling again.";
                        return false;
                    }
                }
                int limit = BackPlugin.TripsPerTombstone.Value;
                if (dest.Tombstone != null && limit > 0)
                {
                    TripsTaken.TryGetValue(dest.Tombstone.m_uid, out int taken);
                    if (taken >= limit)
                    {
                        why = limit == 1 ? "You already travelled to that tombstone." : $"You already travelled to that tombstone {taken} times.";
                        return false;
                    }
                }
            }

            Send(peer, dest.Position, dest.Rotation);
            LastTrip[playerId] = Time.realtimeSinceStartup;
            if (dest.Tombstone != null)
            {
                TripsTaken.TryGetValue(dest.Tombstone.m_uid, out int taken);
                TripsTaken[dest.Tombstone.m_uid] = taken + 1;
            }
            else if (dest.Record != null)
            {
                BackPlugin.Deaths.Forget(dest.Record.PlayerId);
            }
            Message(peer, dest.Tombstone != null ? "Returning to your tombstone..." : "Returning to where you fell...");
            BackPlugin.Log.LogInfo($"Sent {peer.m_playerName} to {Fmt(dest.Position)}{(dest.Tombstone != null ? " (tombstone)" : " (death point)")}.");
            return true;
        }

        private static void Send(ZNetPeer peer, Vector3 pos, Quaternion rot)
        {
            ServerSide.Trace($"Invoking RPC_TeleportPlayer on uid {peer.m_uid} ({peer.m_playerName}) -> {Fmt(pos)}");
            // Chat.RPC_TeleportPlayer on the vanilla client calls Player.m_localPlayer.TeleportTo.
            // It performs no sender check, so the server may invoke it directly.
            ZRoutedRpc.instance.InvokeRoutedRPC(peer.m_uid, "RPC_TeleportPlayer", pos, rot, true);
        }

        /// <summary>Show a centre-screen message on the peer's client, no chat spam.</summary>
        internal static void Message(ZNetPeer peer, string text)
        {
            if (peer == null || peer.m_characterID.IsNone()) return;
            // Player.RPC_Message is registered on the owning client; route it straight at the character ZDO.
            ZRoutedRpc.instance.InvokeRoutedRPC(peer.m_uid, peer.m_characterID, "Message", (int)MessageHud.MessageType.Center, text, 0);
        }

        internal static string Fmt(Vector3 v) => $"{v.x:0},{v.y:0},{v.z:0}";

        // ---- triggers ----

        internal static void OnChat(ZNetPeer sender, string text)
        {
            if (!BackPlugin.ChatTrigger.Value || sender == null || string.IsNullOrEmpty(text)) return;
            string want = BackPlugin.ChatCommand.Value;
            if (string.IsNullOrEmpty(want)) return;
            if (!string.Equals(text.Trim(), want, System.StringComparison.OrdinalIgnoreCase)) return;

            long playerId = PlayerIdForPeer(sender);
            ServerSide.Trace($"Chat command from {sender.m_playerName} (player id {playerId}); {Tombstones.Mine(playerId, sender.m_playerName).Count} tombstone(s) theirs of {Tombstones.All().Count}.");
            var dest = Default(playerId, sender.m_playerName);
            if (!TryGo(sender, dest, admin: false, out string why)) Message(sender, why);
        }

        internal static void OnPing(ZNetPeer sender, Vector3 where)
        {
            if (!BackPlugin.PingTrigger.Value || sender == null) return;
            long playerId = PlayerIdForPeer(sender);
            var mine = Tombstones.Mine(playerId, sender.m_playerName);
            var all = Tombstones.All();
            ServerSide.Trace($"Ping by {sender.m_playerName} (player id {playerId}, character {sender.m_characterID}) at {Fmt(where)}; {all.Count} tombstone(s) in world, {mine.Count} theirs.");
            foreach (var t in all)
            {
                ServerSide.Trace($"  tombstone owner {Tombstones.OwnerOf(t)} '{Tombstones.OwnerNameOf(t)}' at {Fmt(t.GetPosition())}, {Tombstones.Flat(t.GetPosition(), where):0}m from ping");
            }
            var dest = Near(playerId, sender.m_playerName, where);
            if (dest == null)
            {
                ServerSide.Trace($"Nothing of theirs within {BackPlugin.PingRadius.Value}m of the ping.");
                return;
            }

            float now = Time.realtimeSinceStartup;
            float window = Mathf.Max(0.5f, BackPlugin.PingWindowSeconds.Value);
            if (!Pings.TryGetValue(playerId, out var times))
            {
                times = new List<float>();
                Pings[playerId] = times;
            }
            times.RemoveAll(t => now - t > window);
            times.Add(now);

            int need = Mathf.Max(1, BackPlugin.PingsRequired.Value);
            if (times.Count < need)
            {
                Message(sender, $"Ping your tombstone {need - times.Count} more time(s) to travel to it.");
                return;
            }
            times.Clear();
            if (!TryGo(sender, dest, admin: false, out string why)) Message(sender, why);
        }

        /// <summary>Called from the death patch: a fresh death resets any half-finished double ping.</summary>
        internal static void OnDeath(long playerId)
        {
            Pings.Remove(playerId);
        }
    }
}
