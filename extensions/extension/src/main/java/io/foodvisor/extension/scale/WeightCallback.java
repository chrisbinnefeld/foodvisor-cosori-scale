package io.foodvisor.extension.scale;

/** Receives scale readings and connection events. */
public interface WeightCallback {
    /**
     * @param grams   current weight in grams.
     * @param settled true once the scale reports a stable reading.
     */
    void onWeight(int grams, boolean settled);

    /** @param message human readable error, e.g. "scale not found". */
    void onError(String message);

    /** Called when the scale disconnects. */
    void onDisconnected();
}
