package dev.imb11.debug;

import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

public record RemoteSceneDiagnostics(
        long epoch,
        boolean subscribed,
        @Nullable Object unavailable,
        boolean terminal,
        ChunkPos requestedCenter,
        int requestedRadius,
        @Nullable ChunkPos grantedCenter,
        int grantedRadius,
        boolean cameraWithinGrant,
        int pending,
        long pendingBytes,
        int globalPending,
        long globalPendingBytes,
        long lastSequence,
        long stalePackets,
        long discardedApplies,
        long lastSendAgeTicks,
        @Nullable ChunkStats chunks
) {
    @Override
    public String toString() {
        return "epoch=" + epoch + " subscribed=" + subscribed + " unavailable=" + unavailable + " terminal=" + terminal
                + " requestedCenter=" + requestedCenter + " requestedRadius=" + requestedRadius
                + " grantedCenter=" + grantedCenter + " grantedRadius=" + grantedRadius
                + " cameraWithinGrant=" + cameraWithinGrant + " pending=" + pending + " pendingBytes=" + pendingBytes
                + " globalPending=" + globalPending + " globalPendingBytes=" + globalPendingBytes
                + " lastSequence=" + lastSequence + " stalePackets=" + stalePackets
                + " discardedApplies=" + discardedApplies + " lastSendAgeTicks=" + lastSendAgeTicks
                + " " + (chunks == null ? "chunks=unavailable" : chunks);
    }

    public record ChunkStats(
            boolean playerWorld,
            long sharedSubscriptions,
            boolean closed,
            int expected,
            int missing,
            int unlit,
            @Nullable ChunkPos firstMissing,
            @Nullable ChunkPos firstUnlit
    ) {
        public static ChunkStats scan(boolean playerWorld, long sharedSubscriptions, boolean closed, ChunkPos center, int radius,
                                      Predicate<ChunkPos> present, Predicate<ChunkPos> lit) {
            int missing = 0;
            int unlit = 0;
            ChunkPos firstMissing = null;
            ChunkPos firstUnlit = null;
            for (int x = center.x - radius; x <= center.x + radius; x++) {
                for (int z = center.z - radius; z <= center.z + radius; z++) {
                    ChunkPos pos = new ChunkPos(x, z);
                    if (!present.test(pos)) {
                        missing++;
                        firstMissing = firstMissing == null ? pos : firstMissing;
                    } else if (!lit.test(pos)) {
                        unlit++;
                        firstUnlit = firstUnlit == null ? pos : firstUnlit;
                    }
                }
            }
            int width = radius * 2 + 1;
            return new ChunkStats(playerWorld, sharedSubscriptions, closed, width * width, missing, unlit, firstMissing, firstUnlit);
        }

        @Override
        public String toString() {
            return "storage=" + (playerWorld ? "player-world" : "remote-dimension")
                    + " sharedSubscriptions=" + sharedSubscriptions + " closed=" + closed + " expectedChunks=" + expected
                    + " missingChunks=" + missing + " unlitChunks=" + unlit
                    + " firstMissingChunk=" + firstMissing + " firstUnlitChunk=" + firstUnlit;
        }
    }
}
