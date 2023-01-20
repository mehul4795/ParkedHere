package aculix.parkedhere.app.model

enum class GeospatialState {
    /** The Geospatial API has not yet been initialized.  */
    UNINITIALIZED,

    /** The Geospatial API is not supported.  */
    UNSUPPORTED,

    /** The Geospatial API has encountered an unrecoverable error.  */
    EARTH_STATE_ERROR,

    /** The Session has started, but [Earth] isn't [TrackingState.TRACKING] yet.  */
    PRETRACKING,

    /**
     * [Earth] is [TrackingState.TRACKING], but the desired positioning confidence
     * hasn't been reached yet.
     */
    LOCALIZING,

    /** The desired positioning confidence wasn't reached in time.  */
    LOCALIZING_FAILED,

    /**
     * [Earth] is [TrackingState.TRACKING] and the desired positioning confidence has
     * been reached.
     */
    LOCALIZED
}