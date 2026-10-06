using System.Collections.Concurrent;
using System.Threading;
using HarmonyLib;
using UnityEngine;

namespace MrPinoys.Back
{
    /// <summary>
    /// The vanilla dedicated server never reads its own console window, and Terminal
    /// output goes to a UI text box that does not exist headless. This reads stdin on a
    /// background thread, runs each line through the game's Terminal on the main
    /// thread, and echoes Terminal output to the log so it shows in the console.
    /// </summary>
    internal static class ConsoleInput
    {
        private static readonly ConcurrentQueue<string> Lines = new ConcurrentQueue<string>();
        private static Thread _reader;

        internal static bool Headless => Application.isBatchMode || SystemInfo.graphicsDeviceType == UnityEngine.Rendering.GraphicsDeviceType.Null;

        internal static void Start()
        {
            if (!Headless || _reader != null) return;
            _reader = new Thread(ReadLoop) { IsBackground = true, Name = "MrPinoys.Back console input" };
            _reader.Start();
            BackPlugin.Log.LogInfo($"Console input reader started (stdin redirected: {System.Console.IsInputRedirected}).");
        }

        private static void ReadLoop()
        {
            try
            {
                while (true)
                {
                    string line = System.Console.In.ReadLine();
                    if (line == null)
                    {
                        BackPlugin.Log.LogWarning("Console input: stdin reached end of stream; no console input is attached to this process.");
                        break;
                    }
                    line = line.Trim();
                    if (line.Length > 0) Lines.Enqueue(line);
                }
            }
            catch (System.Exception e)
            {
                BackPlugin.Log.LogWarning($"Console input unavailable: {e.Message}");
            }
        }

        /// <summary>Main thread: run any lines typed since the last frame.</summary>
        internal static void Pump()
        {
            while (Lines.TryDequeue(out string line))
            {
                var term = Console.instance;
                if (term == null)
                {
                    BackPlugin.Log.LogWarning($"Console not ready yet, ignoring '{line}'.");
                    continue;
                }
                BackPlugin.Log.LogInfo($"> {line}");
                term.TryRunCommand(line);
            }
        }
    }

    /// <summary>Headless: mirror everything the Terminal would have shown into the log.</summary>
    [HarmonyPatch(typeof(Terminal), nameof(Terminal.AddString), typeof(string))]
    internal static class Patch_Terminal_AddString
    {
        private static void Prefix(string text)
        {
            if (!ConsoleInput.Headless || string.IsNullOrEmpty(text)) return;
            BackPlugin.Log.LogInfo(text);
        }
    }
}
