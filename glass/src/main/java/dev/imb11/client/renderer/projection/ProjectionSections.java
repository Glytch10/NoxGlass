package dev.imb11.client.renderer.projection;

import dev.imb11.mixins.LevelRendererBufferAccessor;
import dev.imb11.mixins.ViewAreaInvoker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.jetbrains.annotations.Nullable;

public final class ProjectionSections {
    private ProjectionSections() {
    }

    public static @Nullable SectionRenderDispatcher.RenderSection find(LevelRenderer renderer, int x, int y, int z) {
        ViewArea area = ((LevelRendererBufferAccessor) renderer).glass$getViewArea();
        if (area == null) {
            return null;
        }
        BlockPos origin = SectionPos.of(x, y, z).origin();
        SectionRenderDispatcher.RenderSection section = ((ViewAreaInvoker) area).glass$getRenderSectionAt(origin);
        return section != null && section.getOrigin().equals(origin) ? section : null;
    }

    public static void markDirty(LevelRenderer renderer, int x, int y, int z) {
        SectionRenderDispatcher.RenderSection section = find(renderer, x, y, z);
        if (section != null) {
            section.setDirty(false);
        }
    }

    public static void markColumnDirty(LevelRenderer renderer, ClientLevel level, ChunkPos chunk) {
        for (int y = level.getMinSection(); y < level.getMaxSection(); y++) {
            markDirty(renderer, chunk.x, y, chunk.z);
        }
    }

    public static void resetColumn(LevelRenderer renderer, ClientLevel level, ChunkPos chunk) {
        boolean reset = false;
        for (int y = level.getMinSection(); y < level.getMaxSection(); y++) {
            SectionRenderDispatcher.RenderSection section = find(renderer, chunk.x, y, chunk.z);
            if (section != null) {
                BlockPos origin = section.getOrigin();
                section.setOrigin(origin.getX(), origin.getY(), origin.getZ());
                reset = true;
            }
        }
        if (reset) {
            renderer.needsUpdate();
        }
    }
}
