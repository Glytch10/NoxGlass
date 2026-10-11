package dev.imb11.client.compat;

import dev.imb11.client.renderer.projection.ProjectionLevelRenderer;
import dev.imb11.client.renderer.projection.ProjectionRenderContext;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.viewport.ViewportProvider;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.util.SodiumChunkSection;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.caffeinemc.mods.sodium.mixin.core.render.world.FrustumAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4fc;

final class IrisProjectionTerrain implements IrisCompatibility.ShaderTerrain {
    private final ProjectionLevelRenderer renderer;
    private final SodiumWorldRenderer terrain;
    private final ClientLevel level;
    private final Object shaderPack;

    public IrisProjectionTerrain(ProjectionLevelRenderer renderer, ClientLevel level, int distance, Object shaderPack) {
        this.renderer = renderer;
        this.level = level;
        this.shaderPack = shaderPack;
        terrain = ((LevelRendererExtension) renderer).sodium$getWorldRenderer();
        ((ProjectionSodiumRenderer) terrain).glass$setProjectionDistance(distance);
        terrain.setLevel(level);
    }

    public Object shaderPack() {
        return shaderPack;
    }

    public boolean isReady() {
        return terrain.getVisibleChunkCount() > 0 || terrain.isTerrainRenderComplete();
    }

    public void prepare(Camera camera, Matrix4fc projection) {
        var state = renderer.renderState().levelRenderState.cameraRenderState;
        var fog = state.fogData;
        var parameters = new FogParameters(fog.color.x, fog.color.y, fog.color.z, fog.color.w,
                fog.environmentalStart, fog.environmentalEnd, fog.renderDistanceStart, fog.renderDistanceEnd);
        var frustum = camera.getCullFrustum();
        ((LevelRendererExtension) renderer).sodium$setMatrices(new ChunkRenderMatrices(projection, state.viewRotationMatrix));
        terrain.setupTerrain(camera, ((ViewportProvider) frustum).sodium$createViewport(), parameters, false, false,
                ((FrustumAccessor) frustum).sodium$getMatrix());
    }

    public ChunkSectionsToRender prepareChunkRenders(Matrix4fc modelView) {
        var state = renderer.renderState().levelRenderState.cameraRenderState;
        var matrices = new ChunkRenderMatrices(state.projectionMatrix, modelView);
        ((LevelRendererExtension) renderer).sodium$setMatrices(matrices);
        var sections = new ChunkSectionsToRender(null, null, 0, null);
        ((SodiumChunkSection) (Object) sections).sodium$setRendering(terrain, matrices, state.pos.x, state.pos.y, state.pos.z);
        return sections;
    }

    public void extractBlockEntities(float partialTick, LevelRenderState state) {
        terrain.extractBlockEntities(ProjectionRenderContext.camera(), partialTick, level.destructionProgress(), state);
        var hidden = ProjectionRenderContext.hiddenTerrainBlock();
        state.blockEntityRenderStates.removeIf(entity -> entity.blockPos.equals(hidden));
    }

    public boolean isSectionReady(BlockPos pos) {
        return terrain.isSectionReady(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }

    public void dirty(int x, int y, int z) {
        terrain.scheduleRebuildForChunk(x, y, z, false);
    }

    @Override
    public void close() {
        terrain.setLevel(null);
    }
}
