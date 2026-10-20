package dev.imb11.debug;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Queue;
import java.util.UUID;

public final class RemoteStreamTrace {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PENDING_LOG_INTERVAL_TICKS = 100;
    private static final int COMPLETE_LOG_INTERVAL_TICKS = 20;
    private final LogThrottle grantLog = new LogThrottle(PENDING_LOG_INTERVAL_TICKS);
    private int lastProgressLogTick;
    private boolean completeLogged;
    private long reusedPlayerChunks;
    private long reusedProjectionChunks;
    private long transferredChunks;

    public RemoteStreamTrace(int now) {
        lastProgressLogTick = now;
    }

    public static void accepted(UUID player, Object id, ChunkPos center, int radius) {
        LOGGER.info("[GLASS projector] server accepted subscribe player={} id={} center={} radius={}", player, id, center, radius);
    }

    public static void rejected(String request, UUID player, Object id, int radius, @Nullable Object denial, String details) {
        LOGGER.warn("[GLASS projector] server rejected {} player={} id={} radius={} denial={} {}",
                request, player, id, radius, denial, details);
    }

    public static void rejectedStale(UUID player, Object id, long latestEpoch) {
        LOGGER.warn("[GLASS projector] server rejected stale subscribe player={} id={} latestEpoch={}", player, id, latestEpoch);
    }

    public static void chunkFailed(Object id, ChunkPos pos, Exception exception) {
        LOGGER.warn("[GLASS projector] cannot prepare chunk {} for {}", pos, id, exception);
    }

    public void reusedPlayerChunk() {
        reusedPlayerChunks++;
    }

    public void reusedProjectionChunk() {
        reusedProjectionChunks++;
    }

    public void transferredChunk() {
        transferredChunks++;
    }

    public void granted(Summary summary, int requestedRadius, int added, int removed, boolean unsupported, int now) {
        if (!grantLog.ready(now) && summary.radius() > 0) {
            return;
        }
        grantLog.reset(now);
        LOGGER.info("[GLASS projector] server grant id={} center={} radius={} requestedRadius={} chunks={} added={} removed={} pending={} unsupported={}",
                summary.id(), summary.center(), summary.radius(), requestedRadius, summary.interest(), added, removed,
                summary.pending().size(), unsupported);
    }

    public void progress(Summary summary, @Nullable ServerLevel level, int now) {
        if (summary.pending().isEmpty() && summary.inFlight() == 0) {
            if (!completeLogged && now - lastProgressLogTick >= COMPLETE_LOG_INTERVAL_TICKS) {
                LOGGER.info("[GLASS projector] server chunks ready id={} center={} radius={} available={}/{} tickets={} sequence={} {}",
                        summary.id(), summary.center(), summary.radius(), summary.sent(), summary.interest(), summary.tickets(),
                        summary.sequence(), counters());
                completeLogged = true;
                lastProgressLogTick = now;
            }
            return;
        }
        completeLogged = false;
        if (now - lastProgressLogTick < PENDING_LOG_INTERVAL_TICKS) {
            return;
        }
        lastProgressLogTick = now;
        int missing = 0;
        int unlit = 0;
        for (ChunkPos pos : summary.pending()) {
            LevelChunk chunk = level == null ? null : level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk == null) {
                missing++;
            } else if (!chunk.isLightCorrect()) {
                unlit++;
            }
        }
        LOGGER.warn("[GLASS projector] server chunk stream pending id={} center={} radius={} sent={}/{} pending={} missing={} unlit={} nextChunk={} tickets={} heartbeatAgeTicks={} sequence={} inFlight={} {}",
                summary.id(), summary.center(), summary.radius(), summary.sent(), summary.interest(), summary.pending().size(),
                missing, unlit, summary.pending().peek(), summary.tickets(), summary.heartbeatAgeTicks(), summary.sequence(),
                summary.inFlight(), counters());
    }

    public void closed(Summary summary, Object reason, boolean notify, @Nullable Object denial) {
        LOGGER.info("[GLASS projector] server closed id={} reason={} notify={} sent={}/{} pending={} tickets={} heartbeatAgeTicks={} denial={} {}",
                summary.id(), reason, notify, summary.sent(), summary.interest(), summary.pending().size(), summary.tickets(),
                summary.heartbeatAgeTicks(), denial, counters());
    }

    private String counters() {
        return "reusedPlayer=" + reusedPlayerChunks + " reusedProjection=" + reusedProjectionChunks + " transferred=" + transferredChunks;
    }

    public record Summary(
            Object id,
            ChunkPos center,
            int radius,
            int sent,
            int interest,
            Queue<ChunkPos> pending,
            int tickets,
            int heartbeatAgeTicks,
            long sequence,
            int inFlight
    ) {
    }
}
