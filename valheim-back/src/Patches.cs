using System;
using System.Collections.Generic;
using System.Text;
using HarmonyLib;
using UnityEngine;

namespace MrPinoys.Back
{
    /// <summary>
    /// Player.OnDeath runs on the owning client and broadcasts "OnDeath" to everybody,
    /// which includes the server's non-owner copy of that player. Record where it stood
    /// as the fallback for deaths that leave no tombstone.
    /// </summary>
    [HarmonyPatch(typeof(Player), "RPC_OnDeath")]
    internal static class Patch_Player_RPC_OnDeath
    {
        private static void Postfix(Player __instance)
        {
            if (!ServerSide.IsServer()) return;
            if (__instance == null) return;
            var nview = __instance.GetComponent<ZNetView>();
            if (nview == null || !nview.IsValid()) return;

            long playerId = __instance.GetPlayerID();
            if (playerId == 0L)
            {
                BackPlugin.Log.LogWarning($"Death of '{__instance.GetPlayerName()}' seen but the player ID is not known yet; not recorded.");
                return;
            }

            Vector3 pos = __instance.transform.position;
            float yaw = __instance.transform.rotation.eulerAngles.y;
            BackPlugin.Deaths.Record(playerId, __instance.GetPlayerName(), pos, yaw);
            Teleporter.OnDeath(playerId);
            BackPlugin.Log.LogInfo($"{__instance.GetPlayerName()} died at {Teleporter.Fmt(pos)}.");
        }
    }

    /// <summary>
    /// Peek at every routed RPC that passes through the server. Since 1.0 chat is sent
    /// per recipient, so a solo player's chat never reaches us, but map pings still go
    /// to everybody and multi-player chat is relayed through here.
    /// </summary>
    [HarmonyPatch(typeof(ZRoutedRpc), "RPC_RoutedRPC")]
    internal static class Patch_ZRoutedRpc_RPC_RoutedRPC
    {
        private static readonly int ChatMessageHash = "ChatMessage".GetStableHashCode();
        private static readonly int SayHash = "Say".GetStableHashCode();
        private const int TalkerPing = 3;

        // The same chat line arrives once per recipient; collapse those copies.
        private static readonly Dictionary<long, (string text, float at)> LastChat = new Dictionary<long, (string, float)>();

        private static void Prefix(ZRpc rpc, ZPackage pkg)
        {
            if (!ServerSide.IsServer() || pkg == null) return;
            if (!BackPlugin.ChatTrigger.Value && !BackPlugin.PingTrigger.Value) return;

            int pos = pkg.GetPos();
            try
            {
                var data = new ZRoutedRpc.RoutedRPCData();
                data.Deserialize(pkg);

                if (data.m_methodHash == ChatMessageHash && data.m_targetZDO.IsNone())
                {
                    var p = data.m_parameters;
                    Vector3 where = p.ReadVector3();
                    int type = p.ReadInt();
                    var info = new UserInfo();
                    info.Deserialize(ref p);
                    string text = p.ReadString();
                    ServerSide.Trace($"ChatMessage from uid {data.m_senderPeerID} to {data.m_targetPeerID}: type {type} at {Teleporter.Fmt(where)} '{text}'");
                    Dispatch(data.m_senderPeerID, type, where, text);
                }
                else if (data.m_methodHash == SayHash && !data.m_targetZDO.IsNone())
                {
                    var p = data.m_parameters;
                    int type = p.ReadInt();
                    var info = new UserInfo();
                    info.Deserialize(ref p);
                    string text = p.ReadString();
                    ServerSide.Trace($"Say from uid {data.m_senderPeerID} to {data.m_targetPeerID}: type {type} '{text}'");
                    Dispatch(data.m_senderPeerID, type, Vector3.zero, text);
                }
            }
            catch (Exception e)
            {
                BackPlugin.Log.LogWarning($"Could not inspect routed RPC: {e}");
            }
            finally
            {
                pkg.SetPos(pos);
            }
        }

        private static void Dispatch(long senderUid, int type, Vector3 where, string text)
        {
            var sender = Teleporter.PeerForUid(senderUid);
            if (sender == null)
            {
                ServerSide.Trace($"No peer with uid {senderUid}; ignoring.");
                return;
            }

            if (type == TalkerPing)
            {
                Teleporter.OnPing(sender, where);
                return;
            }

            if (string.IsNullOrEmpty(text)) return;
            float now = Time.realtimeSinceStartup;
            if (LastChat.TryGetValue(senderUid, out var last) && last.text == text && now - last.at < 1.5f) return;
            LastChat[senderUid] = (text, now);
            Teleporter.OnChat(sender, text);
        }
    }

    /// <summary>Server console commands: back &lt;player&gt;, back list, back forget &lt;player&gt;.</summary>
    [HarmonyPatch(typeof(Terminal), "InitTerminal")]
    internal static class Patch_Terminal_InitTerminal
    {
        private static void Postfix()
        {
            new Terminal.ConsoleCommand("back",
                "back <player> | back list | back forget <player>  (send a player to their newest tombstone)",
                Run, isCheat: false, isNetwork: false, onlyServer: false, isSecret: false,
                allowInDevBuild: false, hideBehindDevCommands: false,
                optionsFetcher: () =>
                {
                    var names = new List<string> { "list", "forget" };
                    if (ZNet.instance != null)
                    {
                        foreach (var peer in ZNet.instance.GetPeers()) names.Add(peer.m_playerName);
                    }
                    return names;
                });
        }

        private static void Run(Terminal.ConsoleEventArgs args)
        {
            var term = args.Context;
            if (!ServerSide.IsServer())
            {
                term.AddString("back: only works on the server.");
                return;
            }
            if (args.Length < 2)
            {
                term.AddString("usage: back <player> | back list | back forget <player>");
                return;
            }

            string verb = args[1];
            if (verb.Equals("list", StringComparison.OrdinalIgnoreCase))
            {
                var sb = new StringBuilder();
                int n = 0;
                foreach (var tomb in Tombstones.All())
                {
                    n++;
                    double age = Tombstones.AgeDays(tomb);
                    string ageText = age < 0 ? "age unknown" : $"{age:0.0} game days old";
                    sb.AppendLine($"tombstone  {Tombstones.OwnerNameOf(tomb)}: {Teleporter.Fmt(tomb.GetPosition())} ({ageText})");
                }
                foreach (var r in BackPlugin.Deaths.All())
                {
                    n++;
                    sb.AppendLine($"death point  {r.PlayerName}: {Teleporter.Fmt(r.Position)} ({(DateTime.UtcNow - r.When).TotalMinutes:0}m ago, fallback only)");
                }
                term.AddString(n == 0 ? "back: no tombstones or death points." : sb.ToString().TrimEnd());
                return;
            }

            if (verb.Equals("forget", StringComparison.OrdinalIgnoreCase))
            {
                string who = Join(args, 2);
                var r = BackPlugin.Deaths.FindByName(who);
                if (r == null) { term.AddString($"back: no fallback death point for '{who}' (tombstones cannot be forgotten, loot them)."); return; }
                BackPlugin.Deaths.Forget(r.PlayerId);
                term.AddString($"back: forgot {r.PlayerName}'s death point.");
                return;
            }

            string name = Join(args, 1);
            var peer = Teleporter.PeerForName(name);
            if (peer == null)
            {
                term.AddString($"back: no connected player named '{name}'.");
                return;
            }
            var dest = Teleporter.Default(Teleporter.PlayerIdForPeer(peer), peer.m_playerName);
            if (Teleporter.TryGo(peer, dest, admin: true, out string why))
                term.AddString($"back: sent {peer.m_playerName} to {Teleporter.Fmt(dest.Position)}.");
            else
                term.AddString($"back: {peer.m_playerName}: {why}");
        }

        private static string Join(Terminal.ConsoleEventArgs args, int from)
        {
            var parts = new List<string>();
            for (int i = from; i < args.Length; i++) parts.Add(args[i]);
            return string.Join(" ", parts);
        }
    }
}
