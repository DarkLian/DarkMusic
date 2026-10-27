using System.Globalization;
using System.Text.Json;
using Windows.Media;
using Windows.Media.Playback;
using Windows.Storage;
using Windows.Storage.Streams;

namespace DarkMusic.Media;

/// <summary>
/// Connects DarkMusic to Windows' media controls: keyboard media keys, headset buttons and the media flyout.
/// DarkMusic writes one JSON state object per line to standard input; button presses come back on standard output,
/// one word per line. The helper exits when standard input closes, so it never outlives Minecraft.
/// </summary>
internal static class Program
{
    private static readonly object Output = new();
    private static string _artwork = "";

    private static async Task<int> Main(string[] args)
    {
        if (args is ["--version"])
        {
            Console.WriteLine(Protocol.Version);
            return 0;
        }

        // DarkMusic writes UTF-8; the console's own code page would garble song titles.
        Console.InputEncoding = new System.Text.UTF8Encoding(false);
        Console.OutputEncoding = new System.Text.UTF8Encoding(false);
        var input = new StreamReader(Console.OpenStandardInput(), new System.Text.UTF8Encoding(false), detectEncodingFromByteOrderMarks: true);
        var player = new MediaPlayer();
        // DarkMusic plays the audio itself; this player only owns the Windows media session.
        player.CommandManager.IsEnabled = false;
        var controls = player.SystemMediaTransportControls;
        controls.IsEnabled = true;
        controls.IsPlayEnabled = controls.IsPauseEnabled = controls.IsNextEnabled = controls.IsPreviousEnabled = controls.IsStopEnabled = true;
        controls.ButtonPressed += (_, e) =>
        {
            var word = e.Button switch
            {
                SystemMediaTransportControlsButton.Play => "play",
                SystemMediaTransportControlsButton.Pause => "pause",
                SystemMediaTransportControlsButton.Next => "next",
                SystemMediaTransportControlsButton.Previous => "previous",
                SystemMediaTransportControlsButton.Stop => "stop",
                _ => null
            };
            if (word != null) Send(word);
        };
        controls.PlaybackPositionChangeRequested += (_, e) =>
            Send("seek " + e.RequestedPlaybackPosition.TotalSeconds.ToString("0.###", CultureInfo.InvariantCulture));
        Send("ready " + Protocol.Version);

        while (await input.ReadLineAsync() is { } line)
        {
            try
            {
                await Apply(controls, Protocol.Parse(line));
            }
            catch (Exception failure)
            {
                // One bad update must not end the session; DarkMusic logs standard error.
                Console.Error.WriteLine(failure.Message);
            }
        }

        controls.IsEnabled = false;
        player.Dispose();
        return 0;
    }

    private static void Send(string line)
    {
        lock (Output)
        {
            Console.Out.WriteLine(line);
            Console.Out.Flush();
        }
    }

    private static async Task Apply(SystemMediaTransportControls controls, Protocol.State state)
    {
        var display = controls.DisplayUpdater;
        display.Type = MediaPlaybackType.Music;
        display.MusicProperties.Title = state.Title;
        display.MusicProperties.Artist = state.Artist;
        display.MusicProperties.AlbumTitle = state.Album;
        if (state.Artwork != _artwork)
        {
            _artwork = state.Artwork;
            display.Thumbnail = state.Artwork.Length > 0 && File.Exists(state.Artwork)
                ? RandomAccessStreamReference.CreateFromFile(await StorageFile.GetFileFromPathAsync(state.Artwork))
                : null;
        }
        display.Update();
        controls.PlaybackStatus = state.Status switch
        {
            "playing" => MediaPlaybackStatus.Playing,
            "paused" => MediaPlaybackStatus.Paused,
            _ => MediaPlaybackStatus.Stopped
        };
        if (state.Duration > 0)
            controls.UpdateTimelineProperties(new SystemMediaTransportControlsTimelineProperties
            {
                StartTime = TimeSpan.Zero,
                MinSeekTime = TimeSpan.Zero,
                EndTime = TimeSpan.FromSeconds(state.Duration),
                MaxSeekTime = TimeSpan.FromSeconds(state.Duration),
                Position = TimeSpan.FromSeconds(Math.Clamp(state.Position, 0, state.Duration))
            });
    }
}

/// <summary>
/// The line protocol, kept free of Windows types.
/// </summary>
internal static class Protocol
{
    public const string Version = "1";

    public sealed record State(string Status, string Title, string Artist, string Album, double Position, double Duration, string Artwork);

    public static State Parse(string line)
    {
        using var document = JsonDocument.Parse(line);
        var root = document.RootElement;
        return new State(Text(root, "status", 16), Text(root, "title", 512), Text(root, "artist", 512), Text(root, "album", 512),
            Number(root, "position"), Number(root, "duration"), Text(root, "artwork", 1024));
    }

    private static string Text(JsonElement root, string name, int limit)
    {
        var value = root.TryGetProperty(name, out var element) && element.ValueKind == JsonValueKind.String ? element.GetString() ?? "" : "";
        return value.Length > limit ? value[..limit] : value;
    }

    private static double Number(JsonElement root, string name)
    {
        return root.TryGetProperty(name, out var element) && element.ValueKind == JsonValueKind.Number && double.IsFinite(element.GetDouble())
            ? element.GetDouble()
            : 0;
    }
}
