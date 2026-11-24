package com.paynux.setup;

/** Receives Setup progress. Callbacks may arrive on a background thread. */
public interface SetupListener {
    /** A new stage started, e.g. "Downloading", "Verifying". */
    void onStage(String stage);

    /**
     * Progress of the current stage. Units depend on the stage: bytes while
     * downloading, entries while extracting. total is -1 when unknown.
     */
    void onProgress(long done, long total);

    /** Free-form log line. */
    void onLog(String line);
}
