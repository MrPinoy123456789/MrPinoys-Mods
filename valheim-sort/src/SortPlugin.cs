using System.Collections.Generic;
using BepInEx;
using BepInEx.Configuration;
using BepInEx.Logging;
using HarmonyLib;
using UnityEngine;

namespace MrPinoys.Sort
{
    /// <summary>
    /// Server-side only. Sorts the contents of player-built chests. Clients stay
    /// vanilla: the plugin rewrites the "items" byte array on the chest ZDO, and an
    /// unmodified client reloads it the next second because the data revision moved.
    /// </summary>
    [BepInPlugin(Guid, Name, Version)]
    public sealed class SortPlugin : BaseUnityPlugin
    {
        public const string Guid = "mrpinoys.valheim.sort";
        public const string Name = "MrPinoys Sort";
        public const string Version = "0.1.0";

        internal static ManualLogSource Log;

        internal static ConfigEntry<bool> SortOnClose;
        internal static ConfigEntry<float> SortDelaySeconds;
        internal static ConfigEntry<bool> Notify;
        internal static ConfigEntry<string> TypeOrder;
        internal static ConfigEntry<bool> MergeStacks;
        internal static ConfigEntry<string> Prefabs;
        internal static ConfigEntry<bool> PingTrigger;
        internal static ConfigEntry<float> PingRadius;
        internal static ConfigEntry<bool> EmoteTrigger;
        internal static ConfigEntry<string> Emote;
        internal static ConfigEntry<float> EmoteRadius;
        internal static ConfigEntry<bool> EmoteLastOpened;
        internal static ConfigEntry<float> EmoteMaxDistance;
        internal static ConfigEntry<bool> Verbose;

        private Harmony _harmony;

        // Chests a client changed while nobody had them open, waiting for the settle delay.
        private static readonly Dictionary<ZDOID, float> Pending = new Dictionary<ZDOID, float>();
        private static readonly List<ZDOID> Due = new List<ZDOID>();

        private void Awake()
        {
            Log = Logger;

            SortOnClose = Config.Bind("General", "SortOnClose", true,
                "Sort a chest whenever a client changes its contents while nobody has it open: closing it, hover stack-all, take-all.");
            SortDelaySeconds = Config.Bind("General", "SortDelaySeconds", 0.5f,
                "Wait this long after the last change before sorting, so a chest closed and reopened at once is left alone.");
            Notify = Config.Bind("General", "Notify", true,
                "Show a small top-left message on the client that last touched the chest when it was sorted.");
            MergeStacks = Config.Bind("General", "MergeStacks", true,
                "Combine partial stacks of the same item up to the item's max stack size.");
            TypeOrder = Config.Bind("General", "TypeOrder",
                "Material,Consumable,Fish,OneHandedWeapon,TwoHandedWeapon,TwoHandedWeaponLeft,Bow,Ammo,AmmoNonEquipable,Shield,Helmet,Chest,Legs,Shoulder,Hands,Utility,Trinket,Tool,Torch,Trophy,Customization,Attach_Atgeir,Misc,None",
                "Item types in the order they appear in the chest (ItemDrop.ItemData.ItemType names, comma separated). Types not listed go last. Within a type: name, then quality (highest first).");
            Prefabs = Config.Bind("General", "Prefabs", "",
                "Comma separated prefab names to treat as sortable chests. Empty means auto-detect: every prefab with a Container and a Piece component that is not a cart (the detected list is logged once).");

            PingTrigger = Config.Bind("Trigger.Ping", "Enabled", true,
                "A map ping (middle-click on the map) sorts every closed chest within PingRadiusMeters of the ping.");
            PingRadius = Config.Bind("Trigger.Ping", "PingRadiusMeters", 40f,
                "Radius around the ping. Map pings are coarse, tens of meters off; keep this generous.");

            EmoteTrigger = Config.Bind("Trigger.Emote", "Enabled", true,
                "Doing the emote below sorts the chest the player last opened (or every closed chest nearby, see LastOpenedOnly). Emotes are written to the player's own ZDO, so this reaches the server even with one player online.");
            Emote = Config.Bind("Trigger.Emote", "Emote", "point",
                "Which emote (chat command without the slash: point, wave, cheer, thumbsup, nononono, ...). Case insensitive.");
            EmoteLastOpened = Config.Bind("Trigger.Emote", "LastOpenedOnly", true,
                "true: the emote sorts only the chest this player opened most recently (since the server started). false: every closed chest within EmoteRadiusMeters.");
            EmoteMaxDistance = Config.Bind("Trigger.Emote", "MaxDistanceMeters", 0f,
                "With LastOpenedOnly: refuse when the last opened chest is farther than this from the player. 0 means no limit (sorting a far chest is harmless; the client picks it up when it gets there).");
            EmoteRadius = Config.Bind("Trigger.Emote", "EmoteRadiusMeters", 10f,
                "Radius around the player when LastOpenedOnly is false. The player position is exact, so this can be small.");

            Verbose = Config.Bind("General", "VerboseLog", true,
                "Trace every chest open, close, sort and skip with the reason. Turn off once it works.");

            _harmony = new Harmony(Guid);
            _harmony.PatchAll(typeof(SortPlugin).Assembly);
            Log.LogInfo($"{Name} {Version} loaded.");
        }

        private void Update()
        {
            if (Pending.Count == 0) return;
            float now = Time.realtimeSinceStartup;
            Due.Clear();
            foreach (var kv in Pending)
            {
                if (kv.Value <= now) Due.Add(kv.Key);
            }
            foreach (var id in Due)
            {
                Pending.Remove(id);
                try { Sorter.SortChest(id, "closed"); }
                catch (System.Exception e) { Log.LogWarning($"Sorting chest {id} failed: {e}"); }
            }
        }

        /// <summary>Queue a chest; a second change before the delay elapses restarts the timer.</summary>
        internal static void Schedule(ZDOID id)
        {
            Pending[id] = Time.realtimeSinceStartup + Mathf.Max(0f, SortDelaySeconds.Value);
        }

        internal static void Unschedule(ZDOID id)
        {
            Pending.Remove(id);
        }

        private void OnDestroy()
        {
            _harmony?.UnpatchSelf();
        }
    }

    internal static class ServerSide
    {
        internal static void Trace(string text)
        {
            if (SortPlugin.Verbose != null && SortPlugin.Verbose.Value) SortPlugin.Log.LogInfo("[trace] " + text);
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

        internal static string Fmt(Vector3 v) => $"{v.x:0},{v.y:0},{v.z:0}";

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
    }
}
