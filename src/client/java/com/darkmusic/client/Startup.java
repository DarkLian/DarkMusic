package com.darkmusic.client;

/**
 * Building the services once the config has loaded, on the client thread. Each service is registered with the
 * cleanup as soon as it exists. If building fails part way, what was built is closed in reverse, the partial state is
 * withdrawn so the client stays as it was before the config loaded, and the original error is reported with any close
 * failures attached. Only a complete build is saved.
 */
final class Startup {
    interface Steps {
        /**
         * Builds the services in dependency order, registering each with acquired as soon as it exists.
         */
        void build(Cleanup acquired);

        /**
         * Removes the settings and every partly built service, back to the state before the config loaded.
         */
        void withdraw();

        void failed(Throwable failure);

        void save();
    }

    private Startup() {
    }

    /**
     * Returns whether the services are ready.
     */
    static boolean run(Steps steps) {
        var acquired = new Cleanup();
        try {
            steps.build(acquired);
        } catch (RuntimeException | LinkageError failure) {
            for (var closing : acquired.run()) failure.addSuppressed(closing);
            steps.withdraw();
            steps.failed(failure);
            return false;
        }
        steps.save();
        return true;
    }
}
