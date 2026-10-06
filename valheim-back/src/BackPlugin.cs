using BepInEx;
using BepInEx.Configuration;
using BepInEx.Logging;
using HarmonyLib;

namespace MrPinoys.Back
{
    /// <summary>
    /// Server-side only. Remembers where each player last died and sends them back
    /// there on request. Clients stay vanilla: the teleport rides the game's own
    /// RPC_TeleportPlayer routed RPC, which any unmodified client honours.
    /// </summary>
    [BepInPlugin(Guid, Name, Version)]
    public sealed class BackPlugin : BaseUnityPlugin
    {
        public const string Guid = "mrpinoys.valheim.back";
        public const string Name = "MrPinoys Back";
        public const string Version = "0.1.0";

        internal static ManualLogSource Log;
        internal static DeathBook Deaths;

        internal static ConfigEntry<float> CooldownSeconds;
        internal static ConfigEntry<int> TripsPerTombstone;
        internal static ConfigEntry<bool> FallbackToDeathPoint;
        internal static ConfigEntry<bool> PingTrigger;
        internal static ConfigEntry<float> PingRadius;
        internal static ConfigEntry<int> PingsRequired;
        internal static ConfigEntry<float> PingWindowSeconds;
        internal static ConfigEntry<bool> ChatTrigger;
        internal static ConfigEntry<bool> Verbose;
        internal static ConfigEntry<string> ChatCommand;

        private Harmony _harmony;

        private void Awake()
        {
            Log = Logger;

            CooldownSeconds = Config.Bind("General", "CooldownSeconds", 60f,
                "Minimum seconds between two trips for the same player. Console use ignores this.");
            TripsPerTombstone = Config.Bind("General", "TripsPerTombstone", 0,
                "How many times a player may travel to the same tombstone. 0 means unlimited (it disappears when looted anyway).");
            FallbackToDeathPoint = Config.Bind("General", "FallbackToDeathPoint", true,
                "When a player has no tombstone (keep-inventory world, empty inventory), fall back to the position where the server saw them die. Consumed on use.");

            PingTrigger = Config.Bind("Trigger.Ping", "Enabled", true,
                "Travel when a player pings the map near one of their own tombstones. This is the only trigger that reaches the server when the player is alone.");
            PingRadius = Config.Bind("Trigger.Ping", "PingRadiusMeters", 150f,
                "How close (in meters, horizontal) a ping must be to the tombstone to count. Map pings are coarse; 150 is comfortable and still tiny on the world map.");
            PingsRequired = Config.Bind("Trigger.Ping", "PingsRequired", 2,
                "How many qualifying pings are needed. 2 means double ping the skull; 1 means a single ping sends you.");
            PingWindowSeconds = Config.Bind("Trigger.Ping", "WindowSeconds", 5f,
                "The qualifying pings must all land inside this many seconds.");

            ChatTrigger = Config.Bind("Trigger.Chat", "Enabled", true,
                "Travel to the newest tombstone when a player types the chat command. Note: since 1.0 the server only sees chat when two or more players are online.");
            ChatCommand = Config.Bind("Trigger.Chat", "Command", "/back",
                "Chat text that triggers the trip (shout or normal chat).");

            Verbose = Config.Bind("General", "VerboseLog", true,
                "Log every chat packet, ping and lookup the plugin handles. Useful while setting up; turn off once it works.");

            Deaths = new DeathBook(System.IO.Path.Combine(Paths.ConfigPath, "mrpinoys.valheim.back.deaths.txt"));
            Deaths.Load();

            _harmony = new Harmony(Guid);
            _harmony.PatchAll(typeof(BackPlugin).Assembly);
            ConsoleInput.Start();
            Log.LogInfo($"{Name} {Version} loaded. {Deaths.Count} fallback death point(s) remembered.");
        }

        private void Update()
        {
            ConsoleInput.Pump();
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
            if (BackPlugin.Verbose != null && BackPlugin.Verbose.Value) BackPlugin.Log.LogInfo("[trace] " + text);
        }

        /// <summary>True on a dedicated server or a hosting client; everything in this plugin is gated on it.</summary>
        internal static bool IsServer()
        {
            return ZNet.instance != null && ZNet.instance.IsServer();
        }
    }
}
