package com.darkmusic.client.config;


import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Mutable only on the client thread after loading.
 */
public final class Settings {
    public int schemaVersion = 1;
    public String musicDirectory = "";
    public List<String> musicFolders;
    public String outputDevice = "";
    public Map<String, String> roots = new LinkedHashMap<>();
    public Map<String, String> shortcuts = new LinkedHashMap<>();
    public transient Set<String> nativeShortcutNames = Set.of();
    public double volume = 1;
    public double lastAudibleVolume = 1;
    public boolean shuffle;
    public Repeat repeat = Repeat.OFF;
    public boolean autoplay;
    public boolean resumeLast = false;
    /**
     * The song to continue with Resume Where You Left Off, or null.
     */
    public Resume resume;
    public boolean showHoverInfo = true;
    public boolean updateNotifications = true;
    // Discord status is opt-in: it shares song titles with the player's Discord friends.
    public boolean discordStatus, discordShowTitle = true;
    // Artwork comes from the song's public source thumbnail; it is shown only with the song itself.
    public boolean discordArtwork = true;
    // Media keys and Windows media controls, through the optional Media Controls extension.
    public boolean mediaControls;
    public double speed = 1;
    public boolean hudEnabled = true;
    public boolean hudInMenus;
    /**
     * Seconds of pause or stop before the HUD hides, up to two minutes; 0 keeps it.
     */
    public int hudIdleHide;
    public Motion hudIdleAnimation = Motion.SLIDE;
    public SongCard songCard = SongCard.OFF;
    public Motion songCardAnimation = Motion.SLIDE;
    /**
     * Where the song card sits once moved in Edit HUD Layout; null keeps it beside the HUD.
     */
    public Double songCardX, songCardY;
    public double songCardScale = 1;
    public boolean hudNextSong, hudTransitions = true;
    // Classic HUD time: under the progress bar like the player, or "1:23 / 3:45" at the end of the artist line.
    public boolean hudTime;
    public TimePosition hudTimePosition = TimePosition.BELOW_BAR;
    public PipSettings pip = new PipSettings();
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
    // Readability backdrop behind the song title and artist in the Classic and Circular HUDs.
    public boolean hudTextPlate = true;
    // Image and spectrum turn together; the progress ring has its own direction but shares the angle.
    public Rotation circularRotation = Rotation.OFF, circularProgressRotation = Rotation.OFF;
    public int circularRotationSeconds = 20;
    public boolean artworkReactive, artworkText = true;
    public SpectrumStyle spectrumStyle = SpectrumStyle.BARS;
    public SurfaceFinish spectrumFinish = SurfaceFinish.BEVELED, progressFinish = SurfaceFinish.BEVELED;
    public HudShape hudShape = HudShape.RECTANGLE;
    public boolean hudRaised;
    public SpectrumPosition spectrumPosition = SpectrumPosition.BELOW;
    public SpectrumDirection spectrumDirection = SpectrumDirection.UPWARD;
    public double hudBackgroundTransparency = 1;
    public boolean hudBackgroundGradient;
    public VisualColors hudBackgroundColors = backgroundColors();
    public int menuBackgroundColor = 0x000000, menuAccentColor = 0xB8A8FA;
    public int classicSpectrumHeight = 32, circularSpectrumHeight = 18, artworkSpectrumHeight = 40;
    public ArtworkSpectrumPosition artworkSpectrumPosition = ArtworkSpectrumPosition.BELOW;
    public VisualColors spectrumColors = defaultVisualColors(), progressColors = defaultVisualColors();
    public int visualizerBars = 36;
    public double visualizerSensitivity = 1, visualizerSmoothing = .6;
    public double spectrumOpacity = 1;
    public String ytDlpPath = "", ffmpegPath = "", ffprobePath = "", javascriptPath = "";
    public DownloadFormat downloadFormat = DownloadFormat.ORIGINAL;
    public AudioSettings audio = new AudioSettings();
    public SoundEnvironmentSettings environment = new SoundEnvironmentSettings();
    public boolean lyricsEnabled;
    public String lyricsLanguages = "auto";
    public int lyricsConsentVersion;
    public boolean lyricsOnline, lyricsLibraryLookup;
    public boolean lyricsAutomaticLookup = true, lyricsDownloadLookup = true;
    public boolean lyricsAutoCaptions;
    public boolean lyricsHud, lyricsNextLine;
    // A soft dark plate behind each lyric line, fading as the lyrics background gets opaque.
    public boolean lyricsTextPlate = true;
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
        return Math.round(VOLUME.apply(value) * 100) / 100.0;
    }

    /**
     * Playback speed and HUD scales share one range: 0.5 to 3 in steps of 0.1.
     */
    public static double clampStep(double value) {
        return Math.round(STEP.apply(value) * 10) / 10.0;
    }

    // Speed and the HUD scales; validate() also rounds them to steps of 0.1, their setters only clamp.
    public static final Range STEP = Range.of(.5, 3, 1);
    public static final Range VOLUME = Range.of(0, 1, 1);
    public static final Range LYRICS_HUD_X = Range.of(0, 1, .5);
    public static final Range LYRICS_HUD_Y = Range.of(0, 1, .9);
    public static final Range LYRICS_HUD_WIDTH = Range.whole(120, 600);
    public static final Range LYRICS_HUD_OPACITY = Range.of(0, 1, 0);
    public static final Range CIRCULAR_IMAGE_GAP = Range.whole(0, 24);
    public static final Range HUD_IDLE_HIDE = Range.whole(0, Settings.IDLE_HIDE_MAX);
    public static final Range CIRCULAR_ROTATION_SECONDS = Range.whole(4, 120);
    public static final Range HUD_BACKGROUND_TRANSPARENCY = Range.of(0, 1, 1);
    public static final Range CIRCULAR_SPECTRUM_HEIGHT = Range.whole(4, 60);
    public static final Range CLASSIC_SPECTRUM_HEIGHT = Range.whole(8, 100);
    public static final Range ARTWORK_SPECTRUM_HEIGHT = Range.whole(8, 100);
    public static final Range VISUALIZER_BARS = Range.whole(8, 64);
    public static final Range SPECTRUM_OPACITY = Range.of(.1, 1, 1);
    public static final Range VISUALIZER_SENSITIVITY = Range.of(.25, 4, 1);
    public static final Range VISUALIZER_SMOOTHING = Range.of(0, .95, .6);

    public void validate() {
        if (pip == null) pip = new PipSettings();
        pip.validate();
        validateLibrary();
        validatePlayback();
        validateDownloads();
        validateLyrics();
        validateMenuColors();
        validateHud();
        validateSpectrum();
    }

    // Each applies the same range as validate(); the fields stay public for Gson and for reading.
    public void volume(double value) {
        volume = VOLUME.apply(value);
    }

    public void lyricsHudX(double value) {
        lyricsHudX = LYRICS_HUD_X.apply(value);
    }

    public void lyricsHudY(double value) {
        lyricsHudY = LYRICS_HUD_Y.apply(value);
    }

    public void lyricsHudWidth(int value) {
        lyricsHudWidth = LYRICS_HUD_WIDTH.apply(value);
    }

    public void lyricsHudOpacity(double value) {
        lyricsHudOpacity = LYRICS_HUD_OPACITY.apply(value);
    }

    public void circularImageGap(int value) {
        circularImageGap = CIRCULAR_IMAGE_GAP.apply(value);
    }

    public void hudIdleHide(int value) {
        hudIdleHide = HUD_IDLE_HIDE.apply(value);
    }

    public void circularRotationSeconds(int value) {
        circularRotationSeconds = CIRCULAR_ROTATION_SECONDS.apply(value);
    }

    public void hudBackgroundTransparency(double value) {
        hudBackgroundTransparency = HUD_BACKGROUND_TRANSPARENCY.apply(value);
    }

    public void circularSpectrumHeight(int value) {
        circularSpectrumHeight = CIRCULAR_SPECTRUM_HEIGHT.apply(value);
    }

    public void classicSpectrumHeight(int value) {
        classicSpectrumHeight = CLASSIC_SPECTRUM_HEIGHT.apply(value);
    }

    public void artworkSpectrumHeight(int value) {
        artworkSpectrumHeight = ARTWORK_SPECTRUM_HEIGHT.apply(value);
    }

    public void visualizerBars(int value) {
        visualizerBars = VISUALIZER_BARS.apply(value);
    }

    public void spectrumOpacity(double value) {
        spectrumOpacity = SPECTRUM_OPACITY.apply(value);
    }

    public void visualizerSensitivity(double value) {
        visualizerSensitivity = VISUALIZER_SENSITIVITY.apply(value);
    }

    public void visualizerSmoothing(double value) {
        visualizerSmoothing = VISUALIZER_SMOOTHING.apply(value);
    }

    public void speed(double value) {
        speed = STEP.apply(value);
    }

    public void lyricsHudScale(double value) {
        lyricsHudScale = STEP.apply(value);
    }

    public void songCardScale(double value) {
        songCardScale = STEP.apply(value);
    }

    public void hudScale(double value) {
        hudScale = STEP.apply(value);
    }

    private void validateLibrary() {
        if (musicDirectory == null) musicDirectory = "";
        if (musicFolders != null)
            musicFolders = musicFolders.stream().filter(Objects::nonNull).filter(p -> !p.isBlank()).distinct().toList();
        if (roots == null) roots = new LinkedHashMap<>();
        if (shortcuts == null) shortcuts = new LinkedHashMap<>();
    }

    /**
     * The collection a song was playing from (the player's mode and group), the song's id, and the position in seconds.
     */
    public static final class Resume {
        public String mode = "All", group = "", track = "";
        public double position;

        public Resume() {
        }

        public Resume(String mode, String group, String track, double position) {
            this.mode = mode;
            this.group = group;
            this.track = track;
            this.position = position;
        }

        boolean valid() {
            return mode != null && mode.length() <= 64 && group != null && group.length() <= 16384 && track != null && !track.isBlank()
                && track.length() <= 16384 && Double.isFinite(position) && position >= 0;
        }
    }

    private void validatePlayback() {
        if (resume != null && !resume.valid()) resume = null;
        volume = VOLUME.apply(volume);
        lastAudibleVolume = volume > 0 ? volume : Double.isFinite(lastAudibleVolume) && lastAudibleVolume > 0 ? Math.clamp(lastAudibleVolume, .01, 1) : 1;
        if (outputDevice == null || !outputDevice.matches("[0-9a-f]{64}|")) outputDevice = "";
        if (audio == null) audio = new AudioSettings();
        audio.validate();
        audio.matchProfile();
        if (environment == null) environment = new SoundEnvironmentSettings();
        environment.validate();
        environment.matchPreset();
        if (repeat == null) repeat = Repeat.OFF;
        speed = clampStep(speed);
    }

    private void validateDownloads() {
        if (ytDlpPath == null) ytDlpPath = "";
        if (ffmpegPath == null) ffmpegPath = "";
        if (ffprobePath == null) ffprobePath = "";
        if (javascriptPath == null) javascriptPath = "";
        if (downloadFormat == null) downloadFormat = DownloadFormat.ORIGINAL;
    }

    private void validateLyrics() {
        if (lyricsConsentVersion < 1) {
            lyricsOnline = false;
            lyricsLibraryLookup = false;
        }
        try {
            lyricsLanguages = LyricsLanguages.normalize(lyricsLanguages);
        } catch (IllegalArgumentException e) {
            lyricsLanguages = "auto";
        }
        lyricsHudScale = clampStep(lyricsHudScale);
        lyricsHudX = LYRICS_HUD_X.apply(lyricsHudX);
        lyricsHudY = LYRICS_HUD_Y.apply(lyricsHudY);
        lyricsHudWidth = LYRICS_HUD_WIDTH.apply(lyricsHudWidth);
        lyricsHudColor &= 0xFFFFFF;
        lyricsHudNextColor &= 0xFFFFFF;
        lyricsHudOpacity = LYRICS_HUD_OPACITY.apply(lyricsHudOpacity);
    }

    private void validateMenuColors() {
        menuBackgroundColor &= 0xFFFFFF;
        menuAccentColor &= 0xFFFFFF;
    }

    private void validateHud() {
        if (classicImage == null) classicImage = HudImage.ARTWORK;
        if (circularImage == null) circularImage = HudImage.ARTWORK;
        if (artworkImage == null) artworkImage = HudImage.ARTWORK;
        circularImageGap = CIRCULAR_IMAGE_GAP.apply(circularImageGap);
        if (waveform == null) waveform = new WaveformSettings();
        waveform.validate();
        if (hudBackgroundColors == null) hudBackgroundColors = backgroundColors();
        hudBackgroundColors.validate();
        if (!Set.of("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT").contains(hudPosition == null ? "" : hudPosition))
            hudPosition = "TOP_LEFT";
        if (progressColors == null) progressColors = defaultVisualColors();
        progressColors.validate();
        if (hudStyle == null) hudStyle = HudStyle.CLASSIC;
        if (progressFinish == null) progressFinish = SurfaceFinish.BEVELED;
        if (hudShape == null) hudShape = HudShape.RECTANGLE;
        if (songCard == null) songCard = SongCard.OFF;
        if (hudIdleAnimation == null) hudIdleAnimation = Motion.SLIDE;
        if (hudTimePosition == null) hudTimePosition = TimePosition.BELOW_BAR;
        if (songCardAnimation == null) songCardAnimation = Motion.SLIDE;
        hudIdleHide = HUD_IDLE_HIDE.apply(hudIdleHide);
        if (songCardX == null || songCardY == null || !Double.isFinite(songCardX) || !Double.isFinite(songCardY))
            songCardX = songCardY = null;
        else {
            songCardX = Math.clamp(songCardX, 0, 1);
            songCardY = Math.clamp(songCardY, 0, 1);
        }
        songCardScale = clampStep(songCardScale);
        if (circularRotation == null) circularRotation = Rotation.OFF;
        if (circularProgressRotation == null) circularProgressRotation = Rotation.OFF;
        circularRotationSeconds = CIRCULAR_ROTATION_SECONDS.apply(circularRotationSeconds);
        hudBackgroundTransparency = HUD_BACKGROUND_TRANSPARENCY.apply(hudBackgroundTransparency);
        hudScale = clampStep(hudScale);
        hudX = hudX == null || !Double.isFinite(hudX) ? (hudPosition.endsWith("RIGHT") ? 1.0 : 0.0) : Math.clamp(hudX, 0, 1);
        hudY = hudY == null || !Double.isFinite(hudY) ? (hudPosition.startsWith("BOTTOM") ? 1.0 : 0.0) : Math.clamp(hudY, 0, 1);
    }

    private void validateSpectrum() {
        circularSpectrumHeight = CIRCULAR_SPECTRUM_HEIGHT.apply(circularSpectrumHeight);
        classicSpectrumHeight = CLASSIC_SPECTRUM_HEIGHT.apply(classicSpectrumHeight);
        artworkSpectrumHeight = ARTWORK_SPECTRUM_HEIGHT.apply(artworkSpectrumHeight);
        if (artworkSpectrumPosition == null) artworkSpectrumPosition = ArtworkSpectrumPosition.BELOW;
        if (visualQuality == null) visualQuality = defaultVisualQuality();
        visualQuality.validate();
        if (spectrumColors == null) spectrumColors = defaultVisualColors();
        spectrumColors.validate();
        visualizerBars = VISUALIZER_BARS.apply(visualizerBars);
        spectrumOpacity = SPECTRUM_OPACITY.apply(spectrumOpacity);
        visualizerSensitivity = VISUALIZER_SENSITIVITY.apply(visualizerSensitivity);
        visualizerSmoothing = VISUALIZER_SMOOTHING.apply(visualizerSmoothing);
        if (spectrumStyle == null) spectrumStyle = SpectrumStyle.BARS;
        if (spectrumFinish == null) spectrumFinish = SurfaceFinish.BEVELED;
        if (circularSpectrumStyle != SpectrumStyle.BARS && circularSpectrumStyle != SpectrumStyle.ROUNDED_BARS && circularSpectrumStyle != SpectrumStyle.FILLED_WAVE && circularSpectrumStyle != SpectrumStyle.FILLED_SPIKE)
            circularSpectrumStyle = SpectrumStyle.BARS;
        if (spectrumPosition == null) spectrumPosition = SpectrumPosition.BELOW;
        if (spectrumDirection == null) spectrumDirection = SpectrumDirection.UPWARD;
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

    public enum SongCard {OFF, HIDDEN_HUD, ALWAYS}

    public enum Motion {SLIDE, FADE}

    public enum TimePosition {BELOW_BAR, ARTIST_LINE}

    public static final int IDLE_HIDE_MAX = 120;

    public enum HudStyle {CLASSIC, CIRCULAR, ARTWORK, SPECTRUM}

    public enum SurfaceFinish {FLAT, BEVELED}

    public enum SpectrumStyle {
        BARS, SMOOTH_WAVE, FILLED_WAVE, SPIKE, FILLED_SPIKE, ROUNDED_BARS;

        public boolean bars() {
            return this == BARS || this == ROUNDED_BARS;
        }
    }

    public enum HudShape {RECTANGLE, ROUNDED, CHAMFERED}

    public enum Rotation {
        OFF, CLOCKWISE, COUNTERCLOCKWISE;

        public int sign() {
            return this == CLOCKWISE ? 1 : this == COUNTERCLOCKWISE ? -1 : 0;
        }
    }

    public enum SpectrumPosition {ABOVE, BELOW}

    public enum ArtworkSpectrumPosition {ABOVE, BELOW, LEFT, RIGHT}

    public enum SpectrumDirection {UPWARD, DOWNWARD}
}
