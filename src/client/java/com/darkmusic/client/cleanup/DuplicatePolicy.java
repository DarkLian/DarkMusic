package com.darkmusic.client.cleanup;

/**
 * Candidate thresholds and resource limits; none of these authorize deletion.
 */
public enum DuplicatePolicy {
    STRICT(.94, .95, .13, .85, 32, .9),
    BALANCED(.78, .70, .20, .70, 24, .7),
    LOOSE(.54, .48, .27, .55, 20, .7);

    public final double title, filename, audioDistance, audioCoverage, artist;
    public final int audioFrames;

    DuplicatePolicy(double title, double filename, double audioDistance, double audioCoverage, int audioFrames, double artist) {
        this.title = title;
        this.filename = filename;
        this.audioDistance = audioDistance;
        this.audioCoverage = audioCoverage;
        this.audioFrames = audioFrames;
        this.artist = artist;
    }

    public static final int MAX_RESULTS = 20_000, BUCKET_SIZE = 64, MAX_RETRIEVED = 256;
    public static final int MAX_TRACKS = 100_000, MAX_FINGERPRINT = 400, CACHE_SCHEMA = 1;

    public boolean duration(double a, double b) {
        return a > 0 && b > 0 && Math.abs(a - b) <= Math.max(this == STRICT ? 2 : this == BALANCED ? 6 : 12, Math.max(a, b) * (this == STRICT ? .01 : .04));
    }
}
