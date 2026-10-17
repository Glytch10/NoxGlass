package dev.imb11.debug;

import com.mojang.logging.LogUtils;
import dev.imb11.blocks.entity.ProjectorBlockEntity;
import dev.imb11.client.renderer.projection.ProjectionRenderManager;
import dev.imb11.client.renderer.projection.ProjectionStage;
import dev.imb11.projection.ProjectionSurface;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class ProjectorActivationTrace {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long CHANGE_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1L);
    private static final long STALL_NANOS = TimeUnit.SECONDS.toNanos(5L);
    private static final Map<ProjectorBlockEntity, State> STATES = new IdentityHashMap<>();

    private ProjectorActivationTrace() {
    }

    public static void report(ProjectorBlockEntity projector, ProjectionStage stage, @Nullable ProjectionRenderManager.ProjectionFeed feed) {
        State state = STATES.computeIfAbsent(projector, ignored -> new State());
        long now = System.nanoTime();
        boolean powered = projector.isActive();
        boolean powerChanged = state.powered != powered;
        if (powerChanged) {
            state.powered = powered;
            state.poweredSince = now;
        }
        boolean ready = ProjectionRenderManager.isReady(feed);
        long sinceLog = now - state.lastLog;
        boolean stalled = powered && !ready && now - state.poweredSince >= STALL_NANOS;
        boolean changed = stage != state.stage || ready != state.ready;
        if (state.stage != null && !powerChanged && !(changed && sinceLog >= CHANGE_INTERVAL_NANOS)
                && !(stalled && sinceLog >= STALL_NANOS)) {
            return;
        }
        state.stage = stage;
        state.ready = ready;
        state.lastLog = now;
        ProjectionSurface surface = projector.getProjectionSurface();
        String message = "[GLASS projector] activation dimension={} pos={} channel={} powered={} poweredMs={} stage={} ready={} reveal={} faces={} bounds={} {}";
        Object[] details = {
                projector.getLevel().dimension().location(), projector.getBlockPos().toShortString(), projector.getChannel(),
                powered, powered ? (now - state.poweredSince) / 1_000_000L : 0L, stage, ready, projector.getRevealDistance(),
                surface == null ? 0 : surface.faces().size(), surface == null ? "none" : surface.renderBounds(),
                feed == null ? "feed=none" : feed.diagnostics()
        };
        if (stalled) {
            LOGGER.warn(message, details);
        } else {
            LOGGER.info(message, details);
        }
    }

    public static void forget(ProjectorBlockEntity projector) {
        STATES.remove(projector);
    }

    public static void forgetLevel(ClientLevel level) {
        STATES.keySet().removeIf(projector -> projector.getLevel() == level);
    }

    public static void clear() {
        STATES.clear();
    }

    private static final class State {
        private ProjectionStage stage;
        private boolean powered;
        private boolean ready;
        private long poweredSince;
        private long lastLog;
    }
}
