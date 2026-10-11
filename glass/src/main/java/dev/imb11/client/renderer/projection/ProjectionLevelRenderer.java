package dev.imb11.client.renderer.projection;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.ProjectionType;
import dev.imb11.client.compat.IrisCompatibility;
import dev.imb11.mixins.LevelRendererBufferAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import org.joml.Matrix4f;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import dev.imb11.mixins.ProjectionRendererAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.BlockBreakingRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.util.ARGB;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.SortedSet;

public class ProjectionLevelRenderer extends LevelRenderer {
    private final Minecraft minecraft;
    private final RenderBuffers buffers;
    private final int renderDistance;
    private final GameRenderState state = new GameRenderState();
    private final FeatureRenderDispatcher features;
    private final List<FogRenderer> fogs = new ArrayList<>();
    private long fogFrame = -1L;
    private int fogUses;
    private final ProjectionMatrixBuffer projectionBuffer = new ProjectionMatrixBuffer("GLASS projection");
    private final ProjectionMatrixBuffer skyProjectionBuffer = new ProjectionMatrixBuffer("GLASS sky projection");
    private final GlobalSettingsUniform globals = new GlobalSettingsUniform();
    private ClientLevel projectionLevel;
    private SectionRenderDispatcher dispatcher;
    private SectionUpdateTracker sectionUpdates;
    private ChunkPos gridCenter;

    public ProjectionLevelRenderer(Minecraft minecraft, RenderBuffers buffers, int renderDistance) {
        super(minecraft.getEntityRenderDispatcher(), minecraft.getBlockEntityRenderDispatcher(), minecraft.getModelManager(),
                minecraft.getTextureManager(), minecraft.getAtlasManager(), minecraft.getShaderManager(), minecraft.gameRenderer, 1, 1);
        this.minecraft = minecraft;
        this.buffers = buffers;
        this.renderDistance = renderDistance;
        this.features = new FeatureRenderDispatcher(buffers, minecraft.getModelManager(), minecraft.getAtlasManager(), minecraft.font, state);
        ProjectionRendererAccessor access = (ProjectionRendererAccessor) this;
        access.glass$renderBuffers(buffers);
        access.glass$featureRenderDispatcher(features);
        access.glass$levelRenderState(state.levelRenderState);
        access.glass$optionsRenderState(state.optionsRenderState);
        access.glass$skyRenderer(new SkyRenderer(minecraft.getTextureManager(), minecraft.getAtlasManager(), new ProjectionSkyTarget()));
    }

    public void setLevel(ClientLevel level) {
        resetLevelRenderData();
        dispatcher = null;
        sectionUpdates = null;
        projectionLevel = level;
        gridCenter = null;
        if (level != null) {
            allChanged();
        }
    }

    public void allChanged() {
        if (projectionLevel == null) {
            return;
        }
        LevelRendererBufferAccessor accessor = (LevelRendererBufferAccessor) this;
        if (viewArea() != null) {
            viewArea().releaseAllBuffers();
        }
        projectionLevel.clearTintCaches();
        var models = minecraft.getModelManager();
        SectionCompiler compiler = new SectionCompiler(minecraft.options.ambientOcclusion().get(),
                minecraft.options.cutoutLeaves().get(), models.getBlockStateModelSet(), models.getFluidStateModelSet(),
                minecraft.getBlockColors());
        if (dispatcher == null) {
            dispatcher = new SectionRenderDispatcher(Util.backgroundExecutor(), buffers, compiler, section -> {
            });
            accessor.glass$setSectionRenderDispatcher(dispatcher);
        } else {
            dispatcher.clearCompileQueue();
            dispatcher.setCompiler(compiler);
        }
        clearVisibleSections();
        accessor.glass$setViewArea(new ViewArea(dispatcher, projectionLevel.getMinY(), projectionLevel.getMaxY(),
                projectionLevel.getMinSectionY(), projectionLevel.getMaxSectionY(), renderDistance, sectionOcclusionGraph()));
        sectionUpdates = new SectionUpdateTracker(projectionLevel, renderDistance);
        gridCenter = null;
    }

    public void prepareCamera(ProjectionCamera camera) {
        ChunkPos center = camera.gridCenter();
        if (!center.equals(gridCenter)) {
            SectionPos cameraSection = SectionPos.of(center.x(), SectionPos.blockToSectionCoord(camera.position().y), center.z());
            viewArea().repositionCamera(cameraSection);
            sectionUpdates.repositionCamera(cameraSection);
            gridCenter = center;
        }
        dispatcher.setCameraPosition(camera.position());
    }

    public void setSectionDirty(int sectionX, int sectionY, int sectionZ) {
        if (sectionUpdates != null) {
            sectionUpdates.setDirty(sectionX, sectionY, sectionZ, false);
        }
    }

    public boolean isSectionDirty(SectionRenderDispatcher.RenderSection section) {
        SectionUpdateTracker.SectionDirtyState dirtyState = sectionUpdates == null ? null : sectionUpdates.getDirtyState(section.getSectionNode());
        return dirtyState != null && dirtyState.isDirty();
    }

    public boolean hasAllNeighbors(SectionRenderDispatcher.RenderSection section) {
        return sectionUpdates != null && sectionUpdates.hasAllNeighbors(projectionLevel, section.getSectionNode());
    }

    public void compileSection(SectionRenderDispatcher.RenderSection section, RenderRegionCache regions) {
        SectionUpdateTracker.SectionDirtyState dirtyState = sectionUpdates.getDirtyState(section.getSectionNode());
        if (dirtyState != null) {
            dirtyState.setNotDirty();
        }
        section.compileAsync(regions.createRegion(projectionLevel, section.getSectionNode()));
    }

    private FogRenderer nextFog(long frame) {
        if (frame != fogFrame) {
            fogFrame = frame;
            fogUses = 0;
        }
        if (fogUses == fogs.size()) {
            fogs.add(new FogRenderer());
        }
        return fogs.get(fogUses++);
    }

    public void renderScene(ProjectionCamera camera, Matrix4f projection, Matrix4f skyProjection, DeltaTracker deltaTracker, long frame) {
        var savedProjection = RenderSystem.getProjectionMatrixBuffer();
        var savedFog = RenderSystem.getShaderFog();
        var savedGlobals = RenderSystem.getGlobalSettingsUniform();
        FogRenderer fog = nextFog(frame);
        var options = state.optionsRenderState;
        var mainOptions = minecraft.gameRenderer.gameRenderState().optionsRenderState;
        options.cloudStatus = mainOptions.cloudStatus;
        options.cloudRange = mainOptions.cloudRange;
        options.renderDistance = renderDistance;
        options.ambientOcclusion = mainOptions.ambientOcclusion;
        options.cutoutLeaves = mainOptions.cutoutLeaves;
        options.textureFiltering = mainOptions.textureFiltering;
        options.maxAnisotropyValue = mainOptions.maxAnisotropyValue;
        options.improvedTransparency = false;
        options.cameraType = mainOptions.cameraType;
        options.glintSpeed = mainOptions.glintSpeed;
        options.glintStrength = mainOptions.glintStrength;
        options.textBackgroundOpacity = mainOptions.textBackgroundOpacity;
        options.backgroundForChatOnly = mainOptions.backgroundForChatOnly;
        options.fov = mainOptions.fov;
        var cameraState = state.levelRenderState.cameraRenderState;
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        camera.extractRenderState(cameraState, partialTick);
        cameraState.projectionMatrix.set(projection);
        cameraState.viewRotationMatrix.set(camera.getViewRotationMatrix(new Matrix4f()));
        cameraState.cullFrustum.set(camera.getCullFrustum());
        cameraState.initialized = true;
        cameraState.depthFar = renderDistance * 64.0F;
        cameraState.fogData = fog.setupFog(camera, renderDistance, deltaTracker, 0.0F, projectionLevel);
        state.levelRenderState.reset();
        try {
            extractLevel(deltaTracker, camera, partialTick);
            fog.updateBuffer(cameraState.fogData);
            GpuBufferSlice sceneProjection = projectionBuffer.getBuffer(projection);
            GpuBufferSlice unclippedProjection = skyProjectionBuffer.getBuffer(skyProjection);
            RenderSystem.setProjectionMatrix(sceneProjection, ProjectionType.PERSPECTIVE);
            var target = minecraft.gameRenderer.mainRenderTarget();
            globals.update(target.width, target.height, mainOptions.glintStrength, projectionLevel.getGameTime(),
                    deltaTracker, 0, camera.position(), false);
            renderProjectionLevel(fog, deltaTracker, sceneProjection, unclippedProjection);
        } finally {
            buffers.endFrame();
            endFrame();
            fog.endFrame();
            RenderSystem.setProjectionMatrix(savedProjection, ProjectionType.PERSPECTIVE);
            RenderSystem.setShaderFog(savedFog);
            RenderSystem.setGlobalSettingsUniform(savedGlobals);
            Camera mainCamera = minecraft.gameRenderer.mainCamera();
            entityRenderDispatcher().prepare(mainCamera, minecraft.crosshairPickEntity);
            blockEntityRenderDispatcher().prepare(mainCamera.position());
        }
    }

    private void extractLevel(DeltaTracker deltaTracker, ProjectionCamera camera, float partialTick) {
        var levelState = state.levelRenderState;
        Vec3 cameraPos = camera.position();
        Frustum cullFrustum = camera.getCullFrustum();
        blockEntityRenderDispatcher().prepare(cameraPos);
        entityRenderDispatcher().prepare(camera, minecraft.crosshairPickEntity);
        levelState.gameTime = projectionLevel.getGameTime();
        extractProjectionEntities(camera, cullFrustum, deltaTracker, levelState);
        extractProjectionBlockEntities(partialTick, levelState);
        extractBlockDestroyAnimation(cameraPos, levelState);
        weatherEffectRenderer().extractRenderState(projectionLevel, partialTick, cameraPos, levelState.weatherRenderState);
        SkyRenderer sky = skyRenderer();
        if (sky != null) {
            sky.extractRenderState(projectionLevel, partialTick, camera, levelState.skyRenderState);
        }
        worldBorderRenderer().extract(projectionLevel.getWorldBorder(), partialTick, cameraPos, renderDistance * 16,
                levelState.worldBorderRenderState);
        levelState.cloudColor = camera.attributeProbe().getValue(EnvironmentAttributes.CLOUD_COLOR, partialTick);
        if (ARGB.alpha(levelState.cloudColor) > 0) {
            levelState.cloudHeight = camera.attributeProbe().getValue(EnvironmentAttributes.CLOUD_HEIGHT, partialTick);
        }
    }

    private void extractProjectionEntities(Camera camera, Frustum frustum, DeltaTracker deltaTracker, LevelRenderState levelState) {
        Vec3 cameraPos = camera.position();
        var tickRateManager = projectionLevel.tickRateManager();
        for (Entity entity : projectionLevel.entitiesForRendering()) {
            if (!entityRenderDispatcher().shouldRender(entity, frustum, cameraPos.x, cameraPos.y, cameraPos.z)) {
                continue;
            }
            BlockPos blockPos = entity.blockPosition();
            if (!projectionLevel.isOutsideBuildHeight(blockPos.getY()) && !isSectionCompiledAndVisible(blockPos)) {
                continue;
            }
            if (entity == camera.entity() && !camera.isDetached()
                    && !(camera.entity() instanceof LivingEntity living && living.isSleeping())) {
                continue;
            }
            if (entity instanceof LocalPlayer && camera.entity() != entity) {
                continue;
            }
            if (entity.tickCount == 0) {
                entity.xOld = entity.getX();
                entity.yOld = entity.getY();
                entity.zOld = entity.getZ();
            }
            float partialEntity = deltaTracker.getGameTimeDeltaPartialTick(!tickRateManager.isEntityFrozen(entity));
            levelState.entityRenderStates.add(entityRenderDispatcher().extractEntity(entity, partialEntity));
        }
        levelState.lastEntityRenderStateCount = levelState.entityRenderStates.size();
    }

    private void extractProjectionBlockEntities(float partialTick, LevelRenderState levelState) {
        var dispatcher = blockEntityRenderDispatcher();
        var hidden = ProjectionRenderContext.hiddenTerrainBlock();
        for (var section : visibleSections()) {
            for (BlockEntity entity : section.getSectionMesh().getRenderableBlockEntities()) {
                if (!entity.getBlockPos().equals(hidden)) {
                    var entityState = dispatcher.tryExtractRenderState(entity, partialTick, null, false);
                    if (entityState != null) {
                        levelState.blockEntityRenderStates.add(entityState);
                    }
                }
            }
        }
        for (BlockEntity entity : projectionLevel.getGloballyRenderedBlockEntities()) {
            if (!entity.isRemoved() && !entity.getBlockPos().equals(hidden)) {
                var entityState = dispatcher.tryExtractRenderState(entity, partialTick, null, true);
                if (entityState != null) {
                    levelState.blockEntityRenderStates.add(entityState);
                }
            }
        }
    }

    private void extractBlockDestroyAnimation(Vec3 cameraPos, LevelRenderState levelState) {
        for (var entry : projectionLevel.destructionProgress().long2ObjectEntrySet()) {
            BlockPos pos = BlockPos.of(entry.getLongKey());
            SortedSet<BlockDestructionProgress> progresses = entry.getValue();
            if (pos.distToCenterSqr(cameraPos.x, cameraPos.y, cameraPos.z) <= 1024.0 && progresses != null && !progresses.isEmpty()) {
                levelState.blockBreakingRenderStates.add(new BlockBreakingRenderState(pos, projectionLevel.getBlockState(pos),
                        progresses.last().getProgress()));
            }
        }
    }

    private void renderProjectionLevel(FogRenderer fog, DeltaTracker delta, GpuBufferSlice sceneProjection, GpuBufferSlice skyProjection) {
        var access = (ProjectionRendererAccessor) this;
        var targets = access.glass$targets();
        var levelState = state.levelRenderState;
        var camera = levelState.cameraRenderState;
        var terrainFog = fog.getBuffer(FogRenderer.FogMode.WORLD);
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.mul(camera.viewRotationMatrix);
        access.glass$submitFeatures(levelState, access.glass$submitNodeStorage(), false);
        FeatureRenderDispatcher.PreparedFrame featureFrame = features.prepareFrame(access.glass$submitNodeStorage());
        try {
            var frame = new FrameGraphBuilder();
            var target = minecraft.gameRenderer.mainRenderTarget();
            targets.main = frame.importExternal("main", target);
            var clear = frame.addPass("clear");
            targets.main = clear.readsAndWrites(targets.main);
            clear.executes(() -> RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                    target.getColorTexture(), new Vector4f(camera.fogData.color.x, camera.fogData.color.y,
                            camera.fogData.color.z, 1.0F), target.getDepthTexture(), 0.0));
            useProjection(frame, targets, "glass_sky_projection", skyProjection);
            access.glass$skyPass(frame, camera, terrainFog);
            useProjection(frame, targets, "glass_scene_projection", sceneProjection);
            access.glass$mainPass(frame, featureFrame, terrainFog, levelState, Profiler.get(),
                    prepareChunkRenders(camera.viewRotationMatrix));
            if (state.optionsRenderState.cloudStatus != CloudStatus.OFF && ARGB.alpha(levelState.cloudColor) > 0) {
                access.glass$cloudsPass(frame, state.optionsRenderState.cloudStatus, camera.pos, levelState.gameTime,
                        delta.getGameTimeDeltaPartialTick(false), levelState.cloudColor, levelState.cloudHeight,
                        state.optionsRenderState.cloudRange);
            }
            access.glass$weatherPass(frame, terrainFog);
            IrisCompatibility.renderWithPipeline(this, () -> frame.execute(GraphicsResourceAllocator.UNPOOLED));
        } finally {
            targets.clear();
            featureFrame.close();
            modelView.popMatrix();
            levelState.reset();
        }
    }

    private static void useProjection(FrameGraphBuilder frame, LevelTargetBundle targets, String name, GpuBufferSlice projection) {
        var pass = frame.addPass(name);
        targets.main = pass.readsAndWrites(targets.main);
        pass.executes(() -> RenderSystem.setProjectionMatrix(projection, ProjectionType.PERSPECTIVE));
    }

    @Override
    public ChunkSectionsToRender prepareChunkRenders(Matrix4fc modelViewMatrix) {
        EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>>> drawGroups = new EnumMap<>(ChunkSectionLayer.class);
        int largestIndexCount = 0;
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            drawGroups.put(layer, new Int2ObjectOpenHashMap<>());
        }
        List<DynamicUniforms.ChunkSectionInfo> sectionInfos = new ArrayList<>();
        GpuTextureView blockAtlas = minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        int textureAtlasWidth = blockAtlas.getWidth(0);
        int textureAtlasHeight = blockAtlas.getHeight(0);
        if (dispatcher != null) {
            dispatcher.lock();
            try {
                long now = Util.getMillis();
                for (SectionRenderDispatcher.RenderSection section : visibleSections()) {
                    SectionMesh sectionMesh = section.getSectionMesh();
                    BlockPos renderOffset = section.getRenderOrigin();
                    int uboIndex = -1;
                    for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                        SectionMesh.SectionDraw draw = sectionMesh.getSectionDraw(layer);
                        SectionRenderDispatcher.RenderSectionBufferSlice slice = dispatcher.getRenderSectionSlice(sectionMesh, layer);
                        if (slice == null || draw == null || draw.hasCustomIndexBuffer() && slice.indexBuffer() == null) {
                            continue;
                        }
                        if (uboIndex == -1) {
                            uboIndex = sectionInfos.size();
                            sectionInfos.add(new DynamicUniforms.ChunkSectionInfo(new Matrix4f(modelViewMatrix), renderOffset.getX(),
                                    renderOffset.getY(), renderOffset.getZ(), section.getVisibility(now), textureAtlasWidth, textureAtlasHeight));
                        }
                        int combinedHash = 173;
                        VertexFormat vertexFormat = layer.pipeline().getVertexFormatBinding(0);
                        GpuBuffer vertexBuffer = slice.vertexBuffer();
                        if (layer != ChunkSectionLayer.TRANSLUCENT) {
                            combinedHash = 31 * combinedHash + vertexBuffer.hashCode();
                        }
                        int firstIndex = 0;
                        GpuBuffer indexBuffer = null;
                        IndexType indexType = null;
                        if (!draw.hasCustomIndexBuffer()) {
                            largestIndexCount = Math.max(largestIndexCount, draw.indexCount());
                        } else {
                            indexBuffer = slice.indexBuffer();
                            indexType = draw.indexType();
                            if (layer != ChunkSectionLayer.TRANSLUCENT) {
                                combinedHash = 31 * combinedHash + indexBuffer.hashCode();
                                combinedHash = 31 * combinedHash + indexType.hashCode();
                            }
                            firstIndex = (int) (slice.indexBufferOffset() / indexType.bytes);
                        }
                        int finalUboIndex = uboIndex;
                        int baseVertex = (int) (slice.vertexBufferOffset() / vertexFormat.getVertexSize());
                        drawGroups.get(layer).computeIfAbsent(combinedHash, ignored -> new ArrayList<>()).add(new RenderPass.Draw<>(
                                0, vertexBuffer, indexBuffer, indexType, firstIndex, draw.indexCount(), baseVertex,
                                (sectionUbos, uploader) -> uploader.upload("ChunkSection", sectionUbos[finalUboIndex])));
                    }
                }
            } finally {
                dispatcher.unlock();
            }
        }
        GpuBufferSlice[] chunkSectionInfos = RenderSystem.getDynamicUniforms().writeChunkSections(sectionInfos.toArray(new DynamicUniforms.ChunkSectionInfo[0]));
        return new ChunkSectionsToRender(blockAtlas, drawGroups, largestIndexCount, chunkSectionInfos);
    }

    @Override
    public boolean hasRenderedAllSections() {
        return dispatcher == null || dispatcher.isQueueEmpty();
    }

    public String sectionStatistics() {
        ViewArea area = viewArea();
        if (area == null) {
            return null;
        }
        int rendered = 0;
        for (var section : visibleSections()) {
            if (section.getSectionMesh().hasRenderableLayers()) {
                rendered++;
            }
        }
        return String.format(Locale.ROOT, "C: %d/%d D: %d, %s", rendered, area.size(), renderDistance,
                dispatcher == null ? "null" : dispatcher.getStats());
    }

    @Override
    public boolean isSectionCompiledAndVisible(BlockPos pos) {
        var section = ProjectionSections.find(this, SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getY()), SectionPos.blockToSectionCoord(pos.getZ()));
        return section != null && section.getSectionMesh() != net.minecraft.client.renderer.chunk.CompiledSectionMesh.UNCOMPILED;
    }

    @Override
    public void close() {
        setLevel(null);
        super.close();
        features.close();
        fogs.forEach(FogRenderer::close);
        fogs.clear();
        projectionBuffer.close();
        skyProjectionBuffer.close();
        globals.close();
    }
}
