package com.darkmusic.client.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mutable only on the client thread after loading.
 */
public final class Settings {
    public int schemaVersion = 1;
    public String musicDirectory = "";
    public java.util.List<String> musicFolders;
    public String outputDevice = "";
    public Map<String, String> roots = new LinkedHashMap<>();
    public Map<String, String> shortcuts = new LinkedHashMap<>();
    public transient java.util.Set<String> nativeShortcutNames = java.util.Set.of();
    public double volume = 1;
    public double lastAudibleVolume = 1;
    public boolean shuffle;
    public com.darkmusic.client.audio.PlaybackController.Repeat repeat = com.darkmusic.client.audio.PlaybackController.Repeat.OFF;
    public boolean autoplay;
    public boolean showHoverInfo = true;
    public boolean updateNotifications = true;
    public double speed = 1;
    public boolean hudEnabled = true;
    public com.darkmusic.client.pip.PipSettings pip = new com.darkmusic.client.pip.PipSettings();
    public String hudPosition = "TOP_LEFT";
    public Double hudX, hudY;
    public double hudScale = 1;
    public boolean hudArtwork = true;
    public HudImage classicImage = HudImage.ARTWORK, circularImage = HudImage.ARTWORK, artworkImage = HudImage.ARTWORK;
    public int circularImageGap;
    public boolean hudProgress = true;
    public boolean waveformProgress;
    public WaveformSettings waveform = new WaveformSettings();
    public boolean hudVisualizer = true;
    public HudStyle hudStyle = HudStyle.CLASSIC;
    public SpectrumStyle circularSpectrumStyle = SpectrumStyle.BARS;
    public boolean circularText = true, circularIconPulse = true;
    public boolean artworkReactive;
    public SpectrumStyle spectrumStyle = SpectrumStyle.BARS;
    public SurfaceFinish spectrumFinish = SurfaceFinish.BEVELED, progressFinish = SurfaceFinish.BEVELED;
    public HudShape hudShape = HudShape.RECTANGLE;
    public boolean hudRaised;
    public SpectrumPosition spectrumPosition = SpectrumPosition.BELOW;
    public SpectrumDirection spectrumDirection = SpectrumDirection.UPWARD;
    public double hudBackgroundTransparency = 1;
    public boolean hudBackgroundGradient;
    public VisualColors hudBackgroundColors = backgroundColors();
    public int menuBackgroundColor = 0x111420, menuAccentColor = 0xB8A8FA;
    public int classicSpectrumHeight = 32, circularSpectrumHeight = 18, artworkSpectrumHeight = 40;
    public ArtworkSpectrumPosition artworkSpectrumPosition = ArtworkSpectrumPosition.BELOW;
    public VisualColors spectrumColors = defaultVisualColors(), progressColors = defaultVisualColors();
    public int visualizerBars = 36;
    public double visualizerSensitivity = 1, visualizerSmoothing = .6;
    public double spectrumOpacity = 1;
    public String ytDlpPath = "", ffmpegPath = "", ffprobePath = "", javascriptPath = "";
    public com.darkmusic.client.download.DownloadEncoding.Format downloadFormat = com.darkmusic.client.download.DownloadEncoding.Format.ORIGINAL;
    public AudioSettings audio = new AudioSettings();
    public SoundEnvironmentSettings environment = new SoundEnvironmentSettings();
    public boolean lyricsEnabled;
    public String lyricsLanguages = "auto";
    public int lyricsConsentVersion;
    public boolean lyricsOnline, lyricsLibraryLookup;
    public boolean lyricsAutomaticLookup = true, lyricsDownloadLookup = true;
    public boolean lyricsAutoCaptions;
    public boolean lyricsHud, lyricsNextLine;
    public double lyricsHudX = .5, lyricsHudY = .9, lyricsHudScale = 1;
    public int lyricsHudWidth = 300, lyricsHudColor = 0xDDD9FF, lyricsHudNextColor = 0xAAA7BD;
    public double lyricsHudOpacity;
    public VisualQuality visualQuality = defaultVisualQuality();

    public static Settings defaults(Settings previous) {
        var defaults = new Settings();
        // Folder identities are playlist reference data, not preferences.
        defaults.roots.putAll(previous.roots);
        defaults.ytDlpPath = previous.ytDlpPath;
        defaults.ffmpegPath = previous.ffmpegPath;
        defaults.ffprobePath = previous.ffprobePath;
        defaults.javascriptPath = previous.javascriptPath;
        defaults.validate();
        return defaults;
    }

    public static double clampVolume(double value) {
        return Double.isFinite(value) ? Math.round(Math.clamp(value, 0, 1) * 100) / 100.0 : 1;
    }

    public void validate() {
        if (pip == null) pip = new com.darkmusic.client.pip.PipSettings();
        pip.validate();
        if (classicImage == null) classicImage = HudImage.ARTWORK;
        if (circularImage == null) circularImage = HudImage.ARTWORK;
        if (artworkImage == null) artworkImage = HudImage.ARTWORK;
        circularImageGap = Math.clamp(circularImageGap, 0, 24);
        if (lyricsConsentVersion < 1) {
            lyricsOnline = false;
            lyricsLibraryLookup = false;
        }
        if (waveform == null) waveform = new WaveformSettings();
        waveform.validate();
        menuBackgroundColor &= 0xFFFFFF;
        menuAccentColor &= 0xFFFFFF;
        circularSpectrumHeight = Math.clamp(circularSpectrumHeight, 4, 60);
        classicSpectrumHeight = Math.clamp(classicSpectrumHeight, 8, 100);
        artworkSpectrumHeight = Math.clamp(artworkSpectrumHeight, 8, 100);
        if (artworkSpectrumPosition == null) artworkSpectrumPosition = ArtworkSpectrumPosition.BELOW;
        if (hudBackgroundColors == null) hudBackgroundColors = backgroundColors();
        hudBackgroundColors.validate();
        try {
            lyricsLanguages = com.darkmusic.client.download.LyricsLanguages.normalize(lyricsLanguages);
        } catch (IllegalArgumentException e) {
            lyricsLanguages = "auto";
        }
        volume = Double.isFinite(volume) ? Math.clamp(volume, 0, 1) : 1;
        lastAudibleVolume = volume > 0 ? volume : Double.isFinite(lastAudibleVolume) && lastAudibleVolume > 0 ? Math.clamp(lastAudibleVolume, .01, 1) : 1;
        if (musicDirectory == null) musicDirectory = "";
        if (musicFolders != null)
            musicFolders = musicFolders.stream().filter(java.util.Objects::nonNull).filter(p -> !p.isBlank()).distinct().toList();
        if (outputDevice == null || !outputDevice.matches("[0-9a-f]{64}|")) outputDevice = "";
        if (roots == null) roots = new LinkedHashMap<>();
        if (shortcuts == null) shortcuts = new LinkedHashMap<>();
        if (ytDlpPath == null) ytDlpPath = "";
        if (ffmpegPath == null) ffmpegPath = "";
        if (ffprobePath == null) ffprobePath = "";
        if (javascriptPath == null) javascriptPath = "";
        if (downloadFormat == null) downloadFormat = com.darkmusic.client.download.DownloadEncoding.Format.ORIGINAL;
        if (audio == null) audio = new AudioSettings();
        audio.validate();
        if (environment == null) environment = new SoundEnvironmentSettings();
        environment.validate();
        if (visualQuality == null) visualQuality = defaultVisualQuality();
        visualQuality.validate();
        if (repeat == null) repeat = com.darkmusic.client.audio.PlaybackController.Repeat.OFF;
        if (!java.util.Set.of("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT").contains(hudPosition == null ? "" : hudPosition))
            hudPosition = "TOP_LEFT";
        speed = com.darkmusic.client.audio.PcmRateConverter.clamp(speed);
        if (spectrumColors == null) spectrumColors = defaultVisualColors();
        if (progressColors == null) progressColors = defaultVisualColors();
        spectrumColors.validate();
        progressColors.validate();
        visualizerBars = Math.clamp(visualizerBars, 8, 64);
        spectrumOpacity = Double.isFinite(spectrumOpacity) ? Math.clamp(spectrumOpacity, .1, 1) : 1;
        visualizerSensitivity = Double.isFinite(visualizerSensitivity) ? Math.clamp(visualizerSensitivity, .25, 4) : 1;
        visualizerSmoothing = Double.isFinite(visualizerSmoothing) ? Math.clamp(visualizerSmoothing, 0, .95) : .6;
        if (hudStyle == null) hudStyle = HudStyle.CLASSIC;
        if (spectrumStyle == null) spectrumStyle = SpectrumStyle.BARS;
        if (spectrumFinish == null) spectrumFinish = SurfaceFinish.BEVELED;
        if (progressFinish == null) progressFinish = SurfaceFinish.BEVELED;
        if (circularSpectrumStyle != SpectrumStyle.BARS && circularSpectrumStyle != SpectrumStyle.ROUNDED_BARS && circularSpectrumStyle != SpectrumStyle.FILLED_WAVE && circularSpectrumStyle != SpectrumStyle.FILLED_SPIKE)
            circularSpectrumStyle = SpectrumStyle.BARS;
        if (hudShape == null) hudShape = HudShape.RECTANGLE;
        if (spectrumPosition == null) spectrumPosition = SpectrumPosition.BELOW;
        if (spectrumDirection == null) spectrumDirection = SpectrumDirection.UPWARD;
        hudBackgroundTransparency = Double.isFinite(hudBackgroundTransparency) ? Math.clamp(hudBackgroundTransparency, 0, 1) : 1;
        hudScale = com.darkmusic.client.hud.HudScale.clamp(hudScale);
        lyricsHudScale = com.darkmusic.client.hud.HudScale.clamp(lyricsHudScale);
        lyricsHudX = Double.isFinite(lyricsHudX) ? Math.clamp(lyricsHudX, 0, 1) : .5;
        lyricsHudY = Double.isFinite(lyricsHudY) ? Math.clamp(lyricsHudY, 0, 1) : .9;
        lyricsHudWidth = Math.clamp(lyricsHudWidth, 120, 600);
        lyricsHudColor &= 0xFFFFFF;
        lyricsHudNextColor &= 0xFFFFFF;
        lyricsHudOpacity = Double.isFinite(lyricsHudOpacity) ? Math.clamp(lyricsHudOpacity, 0, 1) : 0;
        hudX = hudX == null || !Double.isFinite(hudX) ? (hudPosition.endsWith("RIGHT") ? 1.0 : 0.0) : Math.clamp(hudX, 0, 1);
        hudY = hudY == null || !Double.isFinite(hudY) ? (hudPosition.startsWith("BOTTOM") ? 1.0 : 0.0) : Math.clamp(hudY, 0, 1);
    }

    private static VisualColors backgroundColors() {
        var colors = new VisualColors();
        colors.preset = VisualColors.Preset.CUSTOM;
        colors.start = colors.end = 0x111420;
        return colors;
    }

    private static VisualColors defaultVisualColors() {
        var colors = new VisualColors();
        colors.preset = VisualColors.Preset.OCEAN_DRIVE;
        return colors;
    }

    private static VisualQuality defaultVisualQuality() {
        var quality = new VisualQuality();
        quality.select(VisualQuality.Preset.ENHANCED);
        quality.trails = quality.glow = true;
        return quality;
    }

    public HudImage hudImage() {
        return switch (hudStyle) {
            case CIRCULAR -> circularImage;
            case ARTWORK -> artworkImage;
            default -> classicImage;
        };
    }

    public void hudImage(HudImage image) {
        switch (hudStyle) {
            case CIRCULAR -> circularImage = image;
            case ARTWORK -> artworkImage = image;
            default -> classicImage = image;
        }
    }

    public enum HudImage {ARTWORK, ICON}

    public enum HudStyle {CLASSIC, CIRCULAR, ARTWORK, SPECTRUM}

    public enum SurfaceFinish {FLAT, BEVELED}

    public enum SpectrumStyle {
        BARS, SMOOTH_WAVE, FILLED_WAVE, SPIKE, FILLED_SPIKE, ROUNDED_BARS;

        public boolean bars() {
            return this == BARS || this == ROUNDED_BARS;
        }
    }

    public enum HudShape {RECTANGLE, ROUNDED, CHAMFERED}

    public enum SpectrumPosition {ABOVE, BELOW}

    public enum ArtworkSpectrumPosition {ABOVE, BELOW, LEFT, RIGHT}

    public enum SpectrumDirection {UPWARD, DOWNWARD}
}
