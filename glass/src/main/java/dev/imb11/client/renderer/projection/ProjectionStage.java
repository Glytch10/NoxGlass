package dev.imb11.client.renderer.projection;

import java.util.Locale;

public enum ProjectionStage {
    REQUESTED,
    SURFACE_CHANGED,
    RETAINED,
    SUBSCRIPTION_FAILED,
    WAITING_FOR_GRANT,
    WAITING_FOR_CAMERA_CHUNKS,
    WAITING_FOR_RENDERER_SLOT,
    INITIALIZING_RENDERER,
    PRELOADED,
    WAITING_FOR_TERRAIN,
    REFRESHING_TERRAIN,
    READY,
    RENDER_FAILED,
    MISSING_CHANNEL_SOURCE,
    OUTSIDE_PRELOAD_RANGE,
    MISSING_SURFACE,
    OUTSIDE_SURFACE_RANGE,
    FEED_REQUEST_REJECTED;

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
