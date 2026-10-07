using System;
using System.Collections.Generic;
using BepInEx;
using BepInEx.Configuration;
using BepInEx.Logging;
using HarmonyLib;
using UnityEngine;

namespace MrPinoys.Float
{
    /// <summary>
    /// Server-side only. Items that sink below the water surface are replaced by the
    /// game's own floating cargo crate holding the same item. Clients stay vanilla:
    /// the plugin only creates a crate ZDO and destroys the item ZDO; the crate's
    /// physics, opening and self-destruction all run on whichever client owns it.
    /// </summary>
    [BepInPlugin(Guid, Name, Version)]
    public sealed class FloatPlugin : BaseUnityPlugin
    {
        public const string Guid = "mrpinoys.valheim.float";
        public const string Name = "MrPinoys Float";
        public const string Version = "0.1.0";

        internal static ManualLogSource Log;

        internal static ConfigEntry<bool> Enabled;
        internal static ConfigEntry<bool> AutoConvert;
        internal static ConfigEntry<string> Emote;
        internal static ConfigEntry<float> EmoteRadiusMeters;
        internal static ConfigEntry<bool> Notify;
        internal static ConfigEntry<string> CratePrefab;
        internal static ConfigEntry<float> TriggerDepthMeters;
        internal static ConfigEntry<float> SurfaceOffset;
        internal static ConfigEntry<float> MergeRadiusMeters;
        internal static ConfigEntry<float> BatchWindowSeconds;
        internal static ConfigEntry<string> IgnorePrefabs;
        internal static ConfigEntry<bool> ReleaseFishFromCrates;
        internal static ConfigEntry<bool> Verbose;

        private Harmony _harmony;

        // Sunk items waiting for the batch window to close, keyed by item ZDOID.
        private static readonly Dictionary<ZDOID, float> Pending = new Dictionary<ZDOID, float>();
        private static readonly List<ZDOID> Due = new List<ZDOID>();
        private float _nextHousekeeping;

        private void Awake()
        {
            Log = Logger;

            Enabled = Config.Bind("General", "Enabled", true,
                "Replace items that sink below the water surface with a floating cargo crate holding them.");
            AutoConvert = Config.Bind("General", "AutoConvert", false,
                "true: every item that sinks is converted on its own, and items found on the seabed near a player are converted by the periodic sweep. false: nothing happens until a player does the emote below.");
            Emote = Config.Bind("Trigger.Emote", "Emote", "wave",
                "Doing this emote (chat command without the slash: wave, point, cheer, ...) converts every sunk item within EmoteRadiusMeters of the player. Case insensitive. Emotes are written to the player's own ZDO, so this reaches the server even with one player online.");
            EmoteRadiusMeters = Config.Bind("Trigger.Emote", "EmoteRadiusMeters", 30f,
                "Radius around the player for the emote trigger (max 64). The player position is exact.");
            Notify = Config.Bind("Trigger.Emote", "Notify", true,
                "Show a small top-left message on the player's screen with how many items the emote found.");
            CratePrefab = Config.Bind("General", "CratePrefab", "CargoCrate",
                "Prefab name of the floating loot crate (the one a wrecked ship leaves behind). If the startup log says it was not found, pick one of the candidates it lists.");
            TriggerDepthMeters = Config.Bind("General", "TriggerDepthMeters", 0.5f,
                "An item counts as sunk once it is this far below the water level (30), so a splash at the surface does not trigger.");
            SurfaceOffset = Config.Bind("General", "SurfaceOffset", 0.2f,
                "The crate is created this far above the water level; the client's own buoyancy settles it.");
            MergeRadiusMeters = Config.Bind("General", "MergeRadiusMeters", 6f,
                "Put the item into an existing crate of ours within this distance when it has room, nobody has it open and nobody owns it yet (see NOTES.md for why an owned crate cannot be written to). Otherwise a new crate is made.");
            BatchWindowSeconds = Config.Bind("General", "BatchWindowSeconds", 1.0f,
                "Wait this long after an item is first seen under water before converting it, so several stacks dropped together end up in one crate. The item also has to still be under water when the window closes.");
            IgnorePrefabs = Config.Bind("General", "IgnorePrefabs", "",
                "Comma separated prefab names never to put in a crate, on top of the built-in rules (items that float, live fish, placed pieces). Read once at startup.");
            ReleaseFishFromCrates = Config.Bind("General", "ReleaseFishFromCrates", true,
                "One-time cleanup for crates made before fish were excluded: take any fish out of a crate of ours near a player and put them back in the water as live fish. Each crate is done once and stamped; safe to leave on.");
            Verbose = Config.Bind("General", "VerboseLog", true,
                "Trace every sunk item, crate created, item destroyed, crate opened and crate gone. Turn off once it works.");

            _harmony = new Harmony(Guid);
            _harmony.PatchAll(typeof(FloatPlugin).Assembly);
            Log.LogInfo($"{Name} {Version} loaded.");
        }

        private void Update()
        {
            if (!ServerSide.IsServer()) return;
            float now = Time.realtimeSinceStartup;

            // Prefab checks need ObjectDB and ZNetScene, which only exist once the world is up.
            if (!Crates.Ready && !Crates.TryInit()) return;

            if (Pending.Count > 0)
            {
                Due.Clear();
                foreach (var kv in Pending)
                {
                    if (kv.Value <= now) Due.Add(kv.Key);
                }
                foreach (var id in Due)
                {
                    Pending.Remove(id);
                    try { Converter.Convert(id); }
                    catch (Exception e) { Log.LogWarning($"Converting item {id} failed: {e}"); }
                }
            }

            if (now >= _nextHousekeeping)
            {
                _nextHousekeeping = now + 2f;
                try { Crates.Housekeeping(); }
                catch (Exception e) { Log.LogWarning($"Crate housekeeping failed: {e}"); }
            }
        }

        /// <summary>Queue a sunk item; already queued items keep their original due time so the window is not stretched forever.</summary>
        internal static bool Schedule(ZDOID id)
        {
            if (Pending.ContainsKey(id)) return false;
            Pending[id] = Time.realtimeSinceStartup + Mathf.Max(0f, BatchWindowSeconds.Value);
            return true;
        }

        internal static bool IsPending(ZDOID id) => Pending.ContainsKey(id);

        internal static int PendingCount => Pending.Count;

        private void OnDestroy()
        {
            _harmony?.UnpatchSelf();
        }
    }

    internal static class ServerSide
    {
        internal static void Trace(string text)
        {
            if (FloatPlugin.Verbose != null && FloatPlugin.Verbose.Value) FloatPlugin.Log.LogInfo("[trace] " + text);
        }

        /// <summary>True on a dedicated server or a hosting client; everything in this plugin is gated on it.</summary>
        internal static bool IsServer()
        {
            return ZNet.instance != null && ZNet.instance.IsServer();
        }

        /// <summary>
        /// Stable identity for a connected peer: the profile's player ID off the character
        /// ZDO (survives a reconnect, unlike the session uid). Falls back to the uid.
        /// </summary>
        internal static long PlayerKey(long uid)
        {
            var peer = ZNet.instance?.GetPeer(uid);
            if (peer == null || peer.m_characterID.IsNone() || ZDOMan.instance == null) return uid;
            var zdo = ZDOMan.instance.GetZDO(peer.m_characterID);
            long id = zdo != null ? zdo.GetLong(ZDOVars.s_playerID, 0L) : 0L;
            return id != 0L ? id : uid;
        }

        internal static string Fmt(Vector3 v) => $"{v.x:0},{v.y:0.#},{v.z:0}";

        internal static string PeerName(long uid)
        {
            if (uid == 0L) return "nobody";
            if (ZDOMan.instance != null && uid == ZDOMan.GetSessionID()) return "the server";
            var peer = ZNet.instance?.GetPeer(uid);
            return peer != null ? peer.m_playerName : $"uid {uid}";
        }

        /// <summary>Show a message on the peer's client, no chat spam. Same routed RPC valheim-back uses.</summary>
        internal static void Message(long uid, string text, bool center = false)
        {
            var peer = ZNet.instance?.GetPeer(uid);
            if (peer == null || peer.m_characterID.IsNone()) return;
            int type = (int)(center ? MessageHud.MessageType.Center : MessageHud.MessageType.TopLeft);
            ZRoutedRpc.instance.InvokeRoutedRPC(peer.m_uid, peer.m_characterID, "Message", type, text, 0);
        }

        /// <summary>Horizontal distance, the only one that matters for "near a crate".</summary>
        internal static float Flat(Vector3 a, Vector3 b)
        {
            float dx = a.x - b.x, dz = a.z - b.z;
            return Mathf.Sqrt(dx * dx + dz * dz);
        }
    }
}
