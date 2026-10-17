package dev.imb11.debug;

import dev.imb11.mixins.LevelRendererBufferAccessor;
import dev.imb11.sync.ProjectionSource;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

public record FeedDiagnostics(
        BlockPos projector,
        ProjectionSource source,
        Vec3 camera,
        String terrainSource,
        long requestAgeFrames,
        long visibleAgeFrames,
        long retryFrames,
        int terrainReadyFrames,
        SectionStats sections,
        int buildBuffers,
        int terrainUsers,
        long terrainAgeMs,
        int texture,
        int rendererRadius,
        String remote
) {
    @Override
    public String toString() {
        return "projector=" + projector.toShortString() + " source=" + source + " camera=" + camera + " terrainSource=" + terrainSource
                + " requestAgeFrames=" + requestAgeFrames + " visibleAgeFrames=" + visibleAgeFrames
                + " retryFrames=" + retryFrames + " terrainReadyFrames=" + terrainReadyFrames + " " + sections
                + " buildBuffers=" + buildBuffers + " terrainUsers=" + terrainUsers + " terrainAgeMs=" + terrainAgeMs
                + " texture=" + texture + " rendererRadius=" + rendererRadius
                + " remote={" + remote + "}";
    }

    public record SectionStats(
            int visible,
            int dirty,
            int uncompiled,
            @Nullable BlockPos firstBlocked,
            boolean firstBlockedChunkReady,
            boolean compileQueueEmpty,
            String buildQueue
    ) {
        public static SectionStats of(@Nullable LevelRenderer renderer, Predicate<ChunkPos> chunkReady) {
            if (renderer == null) {
                return new SectionStats(0, 0, 0, null, false, false, "none");
            }
            int visible = 0;
            int dirty = 0;
            int uncompiled = 0;
            BlockPos firstBlocked = null;
            for (SectionRenderDispatcher.RenderSection section : ((LevelRendererBufferAccessor) renderer).glass$getVisibleSections()) {
                visible++;
                if (section.isDirty()) {
                    dirty++;
                }
                if (section.getCompiled() == SectionRenderDispatcher.CompiledSection.UNCOMPILED) {
                    uncompiled++;
                    if (firstBlocked == null) {
                        firstBlocked = section.getOrigin().immutable();
                    }
                }
            }
            return new SectionStats(visible, dirty, uncompiled, firstBlocked,
                    firstBlocked != null && chunkReady.test(new ChunkPos(firstBlocked)),
                    renderer.hasRenderedAllSections(), renderer.getSectionStatistics());
        }

        @Override
        public String toString() {
            return "visibleSections=" + visible + " dirtySections=" + dirty + " uncompiledSections=" + uncompiled
                    + " firstBlockedSection=" + firstBlocked + " firstBlockedChunkReady=" + firstBlockedChunkReady
                    + " compileQueueEmpty=" + compileQueueEmpty + " buildQueue=" + buildQueue;
        }
    }
}
