package com.darkmusic.client;

import java.util.List;

/**
 * The order Minecraft's closing runs in. The final settings are written while writes are still admitted; then closed
 * turns away new operations and late callbacks; work in flight is cancelled; services close before the services they
 * read (integrations and PiP before playback, lyrics before downloads, downloads before the library); history is
 * flushed; the storage executor drains last, so queued writes finish, and then whether the final settings reached disk
 * is logged. Every step runs even if one before it fails.
 */
final class Shutdown {
    /**
     * Everything to close, in the order it closes; any may be null when it was never created.
     */
    record Services(AutoCloseable updates, AutoCloseable discord, AutoCloseable mediaControls,
                    AutoCloseable mediaPackage, AutoCloseable pip, AutoCloseable sourceLinks,
                    AutoCloseable cleanupScanner, AutoCloseable sourceIndex, AutoCloseable audition,
                    AutoCloseable lyrics, AutoCloseable downloads, AutoCloseable library, AutoCloseable playback,
                    AutoCloseable artwork, AutoCloseable waveforms) {
    }

    interface Steps {
        /**
         * Records the resume point and writes the settings, while writes are still admitted.
         */
        void saveFinal();

        /**
         * Sets closed: no operation starts and late callbacks discard their results.
         */
        void close();

        /**
         * Cancels the folder picker, the running media operation and the browse pool.
         */
        void cancelWork();

        void flushHistory();

        /**
         * Lets the storage executor finish queued writes, within a bound, then stops it.
         */
        void drainStorage();

        /**
         * After draining: records whether the final settings reached disk, since nothing can retry them now.
         */
        void confirmSaved();
    }

    private Shutdown() {
    }

    static List<RuntimeException> run(Steps steps, Services services) {
        return new Cleanup()
            .then("settings", steps::saveFinal)
            .then("admission", steps::close)
            .then("work in flight", steps::cancelWork)
            .close("updates", services.updates())
            .close("Discord", services.discord())
            .close("media controls", services.mediaControls())
            .close("media controls package", services.mediaPackage())
            .close("PiP", services.pip())
            .close("source links", services.sourceLinks())
            .close("cleanup scanner", services.cleanupScanner())
            .close("source index", services.sourceIndex())
            .close("audition", services.audition())
            .close("lyrics", services.lyrics())
            .close("downloads", services.downloads())
            .close("library", services.library())
            .close("playback", services.playback())
            .close("artwork", services.artwork())
            .close("waveforms", services.waveforms())
            .then("listening history", steps::flushHistory)
            .then("storage", steps::drainStorage)
            .then("saved settings", steps::confirmSaved)
            .run();
    }
}
