package dev.imb11.client.renderer.projection;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.imb11.client.compat.IrisCompatibility;
import dev.imb11.client.remote.RemoteSceneClientManager;
import dev.imb11.debug.FeedDiagnostics;
import dev.imb11.debug.FeedTrace;
import dev.imb11.client.gui.ChannelScreen;
import dev.imb11.client.remote.ProjectionChunkStorage;
import dev.imb11.client.remote.RemoteSceneHandle;
import dev.imb11.blocks.entity.ProjectorBlockEntity;
import dev.imb11.client.renderer.block.ProjectorBlockEntityRenderer;
import dev.imb11.mixins.ViewAreaAccessor;
import dev.imb11.projection.ProjectionSurface;
import dev.imb11.projection.ProjectionChunkRegion;
import dev.imb11.sync.ProjectionSource;
import dev.imb11.sync.remote.RemoteSubscriptionId;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SectionBufferBuilderPool;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.function.Predicate;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class ProjectionRenderManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("glass/projection-renderer");
    private static final int REQUEST_RENDER_GRACE_FRAMES = 2;
    private static final long FEED_RETENTION_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static final long PREVIEW_RETENTION_NANOS = TimeUnit.MINUTES.toNanos(2L);
    private static final int FAILURE_RETRY_FRAMES = 60;
    private static final int STRANDED_BUILD_FRAMES = 10;
    private static final int FEED_BUILD_BUFFERS = Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    private static final long BUILD_PREPARATION_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(2L);
    private static final long HELD_FRAME_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5L);
    private static final float CAMERA_FACE_OFFSET = 0.5625F;
    private static final double PORTAL_MAPPING_OFFSET = 0.5D;
    private static final float PORTAL_CLIP_MIN_DISTANCE = 0.05F;
    private static final double GRID_RECENTER_DISTANCE = 16.0D;
    private static final float VIEW_BLOCK_MARGIN = 1.25F;
    private static final long STATIC_FADE_NANOS = TimeUnit.MILLISECONDS.toNanos(120L);
    private static final Map<FeedKey, ProjectionFeed> FEEDS = new LinkedHashMap<>();
    private static final Map<TerrainKey, TerrainResources> TERRAINS = new HashMap<>();
    private static final Map<ProjectionOwner, PortalSide> PORTAL_SIDES = new HashMap<>();
    private static final List<RetiredBufferPool> RETIRED_BUFFER_POOLS = new ArrayList<>();
    private static Map<String, ProjectionSource> registrySources = Map.of();
    private static ClientLevel activeLevel;
    private static long frameSequence;
    private static long textureSequence;
    private static long subscriptionSequence;
    private static long buildPreparationNanos;

    private ProjectionRenderManager() {
    }

    @Nullable
    public static ProjectionFeed requestFeed(
            @Nullable ProjectionSource source,
            BlockPos projectorPos,
            ProjectionSurface surface
    ) {
        RenderSystem.assertOnRenderThread();
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel currentLevel = minecraft.level;
        if (source == null || currentLevel == null) {
            return null;
        }
        if (activeLevel != currentLevel) {
            changeLevelNow(currentLevel);
        }
        if (!source.equals(registrySources.get(source.channel()))) {
            return null;
        }

        ProjectionView view = ProjectionView.create(currentLevel.dimension(), projectorPos, surface);
        PortalSide side = portalSide(view);
        side.track(view, minecraft.gameRenderer.mainCamera().position(), frameSequence);
        FeedKey key = new FeedKey(source.key(), view.dimension(), view.projectorPos(), false);
        closeFeeds(other -> !other.key.equals(key) && !other.key.preview()
                && other.key.projectorDimension().equals(key.projectorDimension())
                && other.key.projectorPos().equals(key.projectorPos()));
        ProjectionFeed feed = FEEDS.get(key);
        if (feed != null && !feed.source.equals(source)) {
            FEEDS.remove(key);
            closeFeed(feed);
            feed = null;
        }
        if (feed == null) {
            feed = new ProjectionFeed(key, source, view, side, nextTextureLocation());
            FEEDS.put(key, feed);
        } else if (!feed.view.equals(view)) {
            ProjectionAlignment alignment = feed.alignment;
            if (alignment != null && alignment.anchor.projectorPos().equals(view.projectorPos())
                    && !alignment.anchor.sameFrame(view)) {
                for (ProjectionFeed aligned : FEEDS.values()) {
                    if (aligned.alignment == alignment) {
                        aligned.alignment = null;
                        aligned.ready = false;
                    }
                }
            }
            feed.view = view;
            feed.ready = false;
            feed.stage = ProjectionStage.SURFACE_CHANGED;
        }
        feed.lastRequestFrame = frameSequence;
        feed.lastRequestNanos = System.nanoTime();
        return feed;
    }

    public static ProjectionFeed requestPreview(ProjectionSource source, BlockPos ownerPos, float aspect) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || source == null || !source.equals(registrySources.get(source.channel()))) {
            return null;
        }
        FeedKey key = new FeedKey(source.key(), minecraft.level.dimension(), ownerPos, true);
        ProjectionFeed feed = FEEDS.get(key);
        if (feed == null || !feed.source.equals(source)) {
            if (feed != null) {
                FEEDS.remove(key);
                closeFeed(feed);
            }
            ProjectionView view = new ProjectionView(minecraft.level.dimension(), ownerPos, ownerPos,
                    Direction.NORTH, Direction.EAST, Direction.UP, List.of(), new AABB(ownerPos));
            feed = new ProjectionFeed(key, source, view, new PortalSide(), nextTextureLocation());
            FEEDS.put(key, feed);
        }
        feed.previewAspect = Math.max(0.25F, aspect);
        feed.lastRequestFrame = frameSequence;
        feed.lastRequestNanos = System.nanoTime();
        feed.lastVisibleFrame = frameSequence;
        return feed;
    }

    public static boolean isReady(@Nullable ProjectionFeed feed) {
        return feed != null
                && FEEDS.get(feed.key) == feed
                && feed.ready
                && feed.target != null
                && feed.target.getColorTextureView() != null;
    }

    public static void alignFeeds(List<ProjectorBlockEntity> projectors) {
        List<ProjectionFeed> pending = new ArrayList<>();
        for (ProjectorBlockEntity projector : projectors) {
            ProjectionFeed feed = preparedFeed(registrySources.get(projector.getChannel()),
                    projector.getBlockPos(), projector.getProjectionSurface());
            if (feed != null && projector.isProjectionVisible()) {
                pending.add(feed);
            }
        }
        for (ProjectionFeed feed : FEEDS.values()) {
            if (!pending.contains(feed)) {
                feed.alignment = null;
            }
        }
        pending.sort(Comparator.comparingLong((ProjectionFeed feed) ->
                        feed.alignment == null ? Long.MAX_VALUE : feed.alignment.createdFrame)
                .thenComparingLong(feed -> feed.view.projectorPos().asLong()));
        Set<ProjectionAlignment> used = Collections.newSetFromMap(new IdentityHashMap<>());
        Vec3 viewer = Minecraft.getInstance().gameRenderer.mainCamera().position();
        while (!pending.isEmpty()) {
            ProjectionFeed first = pending.removeFirst();
            List<ProjectionFeed> connected = new ArrayList<>();
            connected.add(first);
            Map<SurfaceFaceKey, ProjectionSurface.Face> faces = new LinkedHashMap<>();
            for (ProjectionSurface.Face face : first.view.faces()) {
                faces.put(new SurfaceFaceKey(face.position(), face.normal()), face);
            }
            AABB bounds = first.view.bounds();
            boolean expanded;
            do {
                expanded = false;
                Iterator<ProjectionFeed> iterator = pending.iterator();
                while (iterator.hasNext()) {
                    ProjectionFeed candidate = iterator.next();
                    if (!first.source.equals(candidate.source)
                            || candidate.view.faces().stream().noneMatch(face ->
                            faces.containsKey(new SurfaceFaceKey(face.position(), face.normal())))) {
                        continue;
                    }
                    iterator.remove();
                    connected.add(candidate);
                    bounds = bounds.minmax(candidate.view.bounds());
                    for (ProjectionSurface.Face face : candidate.view.faces()) {
                        faces.putIfAbsent(new SurfaceFaceKey(face.position(), face.normal()), face);
                    }
                    expanded = true;
                }
            } while (expanded);
            ProjectionAlignment alignment = first.alignment;
            if (alignment == null || !used.add(alignment)) {
                ProjectionAlignment replacement = new ProjectionAlignment(alignment == null ? first.view : alignment.anchor);
                replacement.side.inheritSide(alignment == null ? first.side : alignment.side);
                alignment = replacement;
                used.add(alignment);
            }
            ProjectionView anchor = alignment.anchor;
            ProjectionView combined = new ProjectionView(anchor.dimension(), anchor.projectorPos(),
                    anchor.origin(), anchor.facing(), anchor.uDirection(), anchor.vDirection(),
                    List.copyOf(faces.values()), bounds);
            alignment.side.track(combined, viewer, frameSequence);
            alignment.planar = combined.planar();
            for (ProjectionFeed feed : connected) {
                if (feed.alignment != alignment) {
                    feed.ready = false;
                    feed.alignment = alignment;
                }
            }
        }
    }

    public static boolean usesSharedTerrain(LevelRenderer renderer) {
        for (TerrainResources terrain : TERRAINS.values()) {
            if (terrain.renderer == renderer) {
                return terrain.references > 1;
            }
        }
        return false;
    }

    @Nullable
    public static ProjectionFeed visibleFeed(@Nullable ProjectionSource source, BlockPos projectorPos, ProjectionSurface surface) {
        ProjectionFeed feed = preparedFeed(source, projectorPos, surface);
        if (feed != null) {
            markVisible(feed);
        }
        return feed;
    }

    @Nullable
    public static ProjectionFeed preparedFeed(@Nullable ProjectionSource source, BlockPos projectorPos, ProjectionSurface surface) {
        if (source == null || activeLevel == null) {
            return null;
        }
        ProjectionFeed feed = FEEDS.get(new FeedKey(source.key(), activeLevel.dimension(), projectorPos, false));
        if (feed == null || feed.lastRequestFrame != frameSequence
                || !feed.source.equals(source)
                || !feed.view.equals(ProjectionView.create(activeLevel.dimension(), projectorPos, surface))) {
            return null;
        }
        return feed;
    }

    static void markVisible(ProjectionFeed feed) {
        feed.lastVisibleFrame = frameSequence;
    }

    public static long currentFrameSequence() {
        return frameSequence;
    }

    public static boolean hasVisibleProjection() {
        for (ProjectionFeed feed : FEEDS.values()) {
            if (feed.ready && frameSequence - feed.lastRequestFrame <= REQUEST_RENDER_GRACE_FRAMES) {
                return true;
            }
        }
        return false;
    }

    public static void trackViewer(
            ClientLevel level,
            BlockPos projectorPos,
            ProjectionSurface surface,
            Vec3 viewerPosition
    ) {
        RenderSystem.assertOnRenderThread();
        if (activeLevel != level) {
            changeLevelNow(level);
        }
        ProjectionView view = ProjectionView.create(level.dimension(), projectorPos, surface);
        portalSide(view).track(view, viewerPosition, frameSequence);
    }

    public static void releaseProjector(ClientLevel level, BlockPos projectorPos) {
        BlockPos immutablePos = projectorPos.immutable();
        ResourceKey<Level> dimension = level.dimension();
        RenderResources.runOnRenderThread(() -> releaseProjectorNow(dimension, immutablePos));
    }

    public static void releasePreview(ClientLevel level, BlockPos ownerPos) {
        BlockPos immutablePos = ownerPos.immutable();
        ResourceKey<Level> dimension = level.dimension();
        RenderResources.runOnRenderThread(() -> releasePreviewNow(dimension, immutablePos));
    }

    public static boolean isProjectionRenderer(LevelRenderer candidate) {
        if (candidate == null) {
            return false;
        }
        for (ProjectionFeed feed : FEEDS.values()) {
            if (feed.renderer() == candidate) {
                return true;
            }
        }
        return false;
    }

    public static void onRegistryReplaced(Map<String, ProjectionSource> sources) {
        Map<String, ProjectionSource> replacement = new HashMap<>();
        sources.forEach((channel, source) -> {
            if (channel != null && source != null && channel.equals(source.channel())) {
                replacement.put(channel, source);
            }
        });
        Map<String, ProjectionSource> immutableReplacement = Map.copyOf(replacement);
        RenderResources.runOnRenderThread(() -> replaceRegistryNow(immutableReplacement));
    }

    public static void onClientChunkUnloaded(ClientLevel level, ChunkPos chunkPos) {
        RenderResources.runOnRenderThread(() -> chunkUnloadedNow(level, chunkPos));
    }

    public static void onClientLevelChanged(@Nullable ClientLevel level) {
        RenderResources.runOnRenderThread(() -> {
            if (activeLevel != level) {
                changeLevelNow(level);
            }
        });
    }

    public static void onMainRendererRebuilt() {
        RenderResources.runOnRenderThread(() -> {
            for (ProjectionFeed feed : List.copyOf(FEEDS.values())) {
                disposeFeedResources(feed);
            }
        });
    }

    public static void onMainSectionDirty(int sectionX, int sectionY, int sectionZ) {
        for (ProjectionFeed feed : List.copyOf(FEEDS.values())) {
            if (feed.level() == activeLevel
                    && feed.renderer() != null
                    && sectionInFeedView(feed, sectionX, sectionY, sectionZ)) {
                ProjectionSections.markDirty(feed.renderer(), sectionX, sectionY, sectionZ);
            }
        }
    }

    public static void onMainChunkLoaded(ChunkPos chunkPos) {
        for (ProjectionFeed feed : List.copyOf(FEEDS.values())) {
            if (feed.level() == activeLevel && feed.renderer() != null) {
                dirtyChunkSections(feed, chunkPos);
            }
        }
    }

    public static void renderBeforeMain(GameRenderer gameRenderer, DeltaTracker deltaTracker) {
        if (ProjectionRenderContext.isActive()) {
            return;
        }
        RenderSystem.assertOnRenderThread();
        frameSequence++;
        buildPreparationNanos = 0L;

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel currentLevel = minecraft.level;
        if (currentLevel == null || minecraft.player == null) {
            if (activeLevel != null || !FEEDS.isEmpty()) {
                changeLevelNow(null);
            }
            return;
        }
        if (activeLevel != currentLevel) {
            changeLevelNow(currentLevel);
        }

        ProjectorBlockEntityRenderer.prepareNearby(minecraft);
        if (minecraft.gui.screen() instanceof ChannelScreen<?> channelScreen) {
            channelScreen.preparePreview();
        }
        RenderTarget mainTarget = gameRenderer.mainRenderTarget();
        Camera viewerCamera = gameRenderer.mainCamera();
        Vec3 viewerPosition = viewerCamera.position();
        Matrix4f mainProjection = new Matrix4f(gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix);
        Matrix4f mainModelView = RenderSystem.getModelViewMatrixCopy();
        boolean initializedFeedThisFrame = false;
        for (ProjectionFeed feed : List.copyOf(FEEDS.values())) {
            if (FEEDS.get(feed.key) != feed) {
                continue;
            }
            feed.side.update(viewerPosition, frameSequence);
            long requestAge = frameSequence - feed.lastRequestFrame;
            long retention = feed.key.preview() ? PREVIEW_RETENTION_NANOS : FEED_RETENTION_NANOS;
            if (feed.lastRequestFrame < 0L || System.nanoTime() - feed.lastRequestNanos > retention) {
                FEEDS.remove(feed.key);
                closeFeed(feed);
                continue;
            }
            if (requestAge > 0L) {
                feed.stage = ProjectionStage.RETAINED;
                if (feed.remoteScene != null && feed.remoteScene.isTerminalFailure()) {
                    releaseRemoteFeed(feed);
                }
                continue;
            }
            if (feed.remoteScene != null && feed.remoteScene.isTerminalFailure()) {
                feed.stage = ProjectionStage.SUBSCRIPTION_FAILED;
                releaseRemoteFeed(feed);
                feed.nextRetryFrame = frameSequence + FAILURE_RETRY_FRAMES;
                continue;
            }
            if (frameSequence < feed.nextRetryFrame) {
                continue;
            }

            try {
                PortalView portalView = configurePortalCamera(
                        feed,
                        viewerCamera,
                        mainProjection,
                        mainTarget
                );
                ChunkPos cameraCenter = viewCenterChunk(feed);
                int requestedRadius = ProjectionRenderContext.feedRenderDistance(
                        minecraft.options.getEffectiveRenderDistance()
                );
                RemoteSceneHandle remoteScene = ensureRemoteScene(feed, cameraCenter, requestedRadius);
                ClientLevel remoteLevel = remoteScene.level();
                int grantedRadius = remoteScene.grantedRadius();
                ClientLevel sceneLevel = remoteLevel;
                ProjectionLightmap sceneLight = remoteScene.lightTexture();
                if (sceneLevel == null || grantedRadius < 1 || remoteScene.isUnavailable()
                        || (!feed.ready && !remoteScene.isReady(cameraCenter))) {
                    feed.stage = remoteLevel == null || grantedRadius < 1 ? ProjectionStage.WAITING_FOR_GRANT : ProjectionStage.WAITING_FOR_CAMERA_CHUNKS;
                    markUnavailable(feed);
                    continue;
                }
                applyCamera(feed.camera, sceneLevel, minecraft, portalView);
                updateStatic(feed, sceneLevel, portalView);
                boolean requiresInitialization = requiresResourceInitialization(feed, sceneLevel, grantedRadius);
                if (requiresInitialization && initializedFeedThisFrame) {
                    feed.stage = ProjectionStage.WAITING_FOR_RENDERER_SLOT;
                    continue;
                }
                if (requiresInitialization) {
                    initializedFeedThisFrame = true;
                }
                long resourceStart = System.nanoTime();
                boolean resourcesReady = ensureResources(
                        minecraft,
                        sceneLevel,
                        sceneLight,
                        grantedRadius,
                        feed,
                        portalView
                );
                reportSlowStage(feed, "resources", resourceStart);
                if (!resourcesReady) {
                    feed.stage = ProjectionStage.INITIALIZING_RENDERER;
                    continue;
                }
                long prepareStart = System.nanoTime();
                prepareTerrain(feed, portalView);
                reportSlowStage(feed, "terrain-preparation", prepareStart);
                if (feed.ready && (feed.lastVisibleFrame < 0L || frameSequence - feed.lastVisibleFrame > REQUEST_RENDER_GRACE_FRAMES)) {
                    feed.stage = ProjectionStage.PRELOADED;
                    continue;
                }
                long drawStart = System.nanoTime();
                renderFeed(deltaTracker, feed, portalView);
                reportSlowStage(feed, "draw", drawStart);
                feed.stage = feed.ready ? (feed.terrainReadyFrames < 2 ? ProjectionStage.REFRESHING_TERRAIN : ProjectionStage.READY) : ProjectionStage.WAITING_FOR_TERRAIN;
            } catch (RuntimeException exception) {
                LOGGER.error(
                        "[GLASS projector] render failed projector={} source={} stage={}; retrying in {} frames",
                        feed.key,
                        feed.source.key(),
                        feed.stage,
                        FAILURE_RETRY_FRAMES,
                        exception
                );
                disposeFeedResources(feed);
                feed.stage = ProjectionStage.RENDER_FAILED;
                feed.nextRetryFrame = frameSequence + FAILURE_RETRY_FRAMES;
            } finally {
                restoreMainRenderState(mainModelView);
            }
        }
        restoreMainRenderState(mainModelView);
        ProjectorBlockEntityRenderer.prepareSurfaceBlending(currentLevel);
    }

    public static void reset() {
        RenderResources.runOnRenderThread(ProjectionRenderManager::resetNow);
    }

    private static boolean requiresResourceInitialization(ProjectionFeed feed, ClientLevel level, int grantedRadius) {
        return feed.renderer() == null
                || feed.level() != level
                || feed.rendererRadius() != grantedRadius
                || feed.target == null
                || feed.renderBuffers() == null
                || !feed.terrain.key.equals(new TerrainKey(level, viewCenterChunk(feed), grantedRadius, feed.source.pos()));
    }

    private static boolean chunkReady(ProjectionFeed feed, ChunkPos pos) {
        return feed.remoteScene != null && feed.remoteScene.isReady(pos);
    }

    private static void reportSlowStage(ProjectionFeed feed, String stage, long started) {
        feed.trace.slowStage(stage, started, feed::diagnostics);
    }

    private static RemoteSceneHandle ensureRemoteScene(
            ProjectionFeed feed,
            ChunkPos cameraCenter,
            int requestedRadius
    ) {
        RemoteSceneHandle remoteScene = feed.remoteScene;
        if (remoteScene == null) {
            RemoteSubscriptionId subscription = new RemoteSubscriptionId(
                    feed.key.projectorDimension(),
                    feed.key.projectorPos(),
                    feed.key.preview(),
                    feed.source,
                    ++subscriptionSequence
            );
            remoteScene = RemoteSceneClientManager.acquire(subscription, cameraCenter, requestedRadius);
            feed.remoteScene = remoteScene;
        } else {
            RemoteSceneClientManager.update(remoteScene, cameraCenter, requestedRadius);
        }
        return remoteScene;
    }

    private static void replaceRegistryNow(Map<String, ProjectionSource> replacement) {
        registrySources = replacement;
        closeFeeds(feed -> !feed.source.equals(replacement.get(feed.source.channel())));
    }

    private static void chunkUnloadedNow(ClientLevel level, ChunkPos chunkPos) {
        if (level != activeLevel || ProjectionChunkStorage.of(level).retained(chunkPos)) {
            return;
        }
        for (ProjectionFeed feed : FEEDS.values()) {
            if (feed.level() != level) {
                continue;
            }
            ChunkPos cameraChunk = ChunkPos.containing(BlockPos.containing(feed.cameraPosition));
            resetUnloadedChunkSections(feed, chunkPos);
            if (chunkPos.equals(ChunkPos.containing(feed.source.pos()))
                    || chunkPos.equals(cameraChunk)) {
                feed.ready = false;
            }
        }
    }

    private static void dirtyChunkSections(ProjectionFeed feed, ChunkPos chunkPos) {
        ClientLevel level = feed.level();
        LevelRenderer renderer = feed.renderer();
        if (level == null || renderer == null) {
            return;
        }
        if (!chunkInFeedView(feed, chunkPos.x(), chunkPos.z())) {
            return;
        }
        ProjectionSections.markColumnDirty(renderer, level, chunkPos);
    }

    private static void resetUnloadedChunkSections(ProjectionFeed feed, ChunkPos chunkPos) {
        ClientLevel level = feed.level();
        LevelRenderer renderer = feed.renderer();
        if (level == null || renderer == null || !chunkInFeedView(feed, chunkPos.x(), chunkPos.z())) {
            return;
        }
        ProjectionSections.resetColumn(renderer, level, chunkPos);
    }

    private static boolean sectionInFeedView(
            ProjectionFeed feed,
            int sectionX,
            int sectionY,
            int sectionZ
    ) {
        ClientLevel level = feed.level();
        return level != null
                && sectionY >= level.getMinSectionY()
                && sectionY <= level.getMaxSectionY()
                && chunkInFeedView(feed, sectionX, sectionZ);
    }

    private static boolean chunkInFeedView(ProjectionFeed feed, int sectionX, int sectionZ) {
        ChunkPos center = viewCenterChunk(feed);
        int renderDistance = feedViewDistance(feed);
        return new ProjectionChunkRegion(center, renderDistance).contains(sectionX, sectionZ);
    }

    private static int feedViewDistance(ProjectionFeed feed) {
        LevelRenderer renderer = feed.renderer();
        if (renderer != null) {
            ViewArea viewArea = renderer.viewArea();
            if (viewArea != null) {
                return viewArea.getViewDistance();
            }
        }
        int grantedRadius = feed.remoteScene == null
                ? ProjectionRenderContext.feedRenderDistance(Minecraft.getInstance().options.getEffectiveRenderDistance())
                : feed.remoteScene.grantedRadius();
        return ProjectionRenderContext.feedRenderDistance(
                Minecraft.getInstance().options.getEffectiveRenderDistance(),
                grantedRadius
        );
    }

    private static ChunkPos viewCenterChunk(ProjectionFeed feed) {
        TerrainResources terrain = feed.terrain == null ? alignedTerrain(feed) : feed.terrain;
        Vec3 position = feed.cameraPosition;
        if (terrain != null) {
            ChunkPos retained = terrain.key.center();
            if (Math.abs(position.x - retained.getMiddleBlockX()) <= GRID_RECENTER_DISTANCE
                    && Math.abs(position.z - retained.getMiddleBlockZ()) <= GRID_RECENTER_DISTANCE) {
                return retained;
            }
        }
        return ProjectionChunkRegion.cameraCenter(position);
    }

    @Nullable
    private static TerrainResources alignedTerrain(ProjectionFeed feed) {
        if (feed.alignment == null) {
            return null;
        }
        for (ProjectionFeed other : FEEDS.values()) {
            if (other.alignment == feed.alignment && other.terrain != null) {
                return other.terrain;
            }
        }
        return null;
    }

    private static void releaseProjectorNow(ResourceKey<Level> dimension, BlockPos projectorPos) {
        closeFeeds(feed -> feed.key.projectorDimension().equals(dimension) && feed.key.projectorPos().equals(projectorPos));
        PORTAL_SIDES.remove(new ProjectionOwner(dimension, projectorPos));
    }

    private static void releasePreviewNow(ResourceKey<Level> dimension, BlockPos ownerPos) {
        closeFeeds(feed -> feed.key.preview() && feed.key.projectorDimension().equals(dimension) && feed.key.projectorPos().equals(ownerPos));
    }

    private static void changeLevelNow(@Nullable ClientLevel level) {
        closeFeeds(feed -> true);
        PORTAL_SIDES.clear();
        activeLevel = level;
    }

    private static void resetNow() {
        changeLevelNow(null);
        frameSequence = 0L;
    }

    private static void closeFeeds(Predicate<ProjectionFeed> filter) {
        List<ProjectionFeed> closed = FEEDS.values().stream().filter(filter).toList();
        for (ProjectionFeed feed : closed) {
            FEEDS.remove(feed.key);
            closeFeed(feed);
        }
    }

    private static boolean ensureResources(
            Minecraft minecraft,
            ClientLevel level,
            @Nullable ProjectionLightmap lightTexture,
            int grantedRadius,
            ProjectionFeed feed,
            PortalView portalView
    ) {
        if (lightTexture == null) {
            return false;
        }
        TerrainKey terrainKey = new TerrainKey(level, viewCenterChunk(feed), grantedRadius, feed.source.pos());
        if (feed.terrain != null && feed.terrain.key.equals(terrainKey) && feed.target != null) {
            resizeTarget(feed, portalView.targetWidth(), portalView.targetHeight());
            return true;
        }
        if (feed.terrain != null && feed.level() == level && feed.rendererRadius() == grantedRadius
                && !TERRAINS.containsKey(terrainKey)
                && (feed.terrain.references == 1 || frameSequence - feed.terrain.lastRekeyFrame > 1)) {
            TERRAINS.remove(feed.terrain.key, feed.terrain);
            feed.terrain.key = terrainKey;
            feed.terrain.lastRekeyFrame = frameSequence;
            TERRAINS.put(terrainKey, feed.terrain);
            resizeTarget(feed, portalView.targetWidth(), portalView.targetHeight());
            return true;
        }
        releaseTerrain(feed);
        if (feed.ready) {
            feed.holdingFrameSinceNanos = System.nanoTime();
        }
        feed.terrainReadyFrames = 0;
        if (feed.target == null) {
            feed.target = new TextureTarget("GLASS feed", portalView.targetWidth(), portalView.targetHeight(), true, GpuFormat.RGBA8_UNORM);
        } else {
            resizeTarget(feed, portalView.targetWidth(), portalView.targetHeight());
        }
        TerrainResources terrain = TERRAINS.get(terrainKey);
        boolean created = terrain == null;
        if (created) {
            terrain = new TerrainResources(terrainKey);
            TERRAINS.put(terrainKey, terrain);
        }
        terrain.references++;
        feed.terrain = terrain;
        if (created) {
            terrain.lightTexture = lightTexture;
            terrain.buffers = new ProjectionRenderBuffers(FEED_BUILD_BUFFERS);
            terrain.bufferCount = terrain.buffers.sectionBufferPool().getFreeBufferCount();
            terrain.renderer = new ProjectionLevelRenderer(minecraft, terrain.buffers, grantedRadius);
            try (ProjectionRenderContext.Scope ignored = ProjectionRenderContext.enter(
                    terrain.renderer, feed.camera, feed.target, feed.source.pos(), level, lightTexture
            )) {
                terrain.renderer.setLevel(level);
            }
        }
        if (feed.remoteScene != null && level == feed.remoteScene.level()) {
            RemoteSceneClientManager.attachRenderer(feed.remoteScene, feed.renderer());
        }
        if (feed.textureProxy == null) {
            feed.textureProxy = new ProjectionTargetTexture(feed);
            feed.textureManager = minecraft.getTextureManager();
            feed.textureManager.register(feed.textureLocation, feed.textureProxy);
        }
        return !created;
    }

    private static void prepareTerrain(ProjectionFeed feed, PortalView portalView) {
        Vec3 position = feed.camera.position();
        Matrix4f modelView = new Matrix4f().rotation(feed.camera.rotation().conjugate(new Quaternionf()));
        Frustum frustum = new Frustum(modelView, portalView.projection());
        frustum.prepare(position.x, position.y, position.z);
        try (ProjectionRenderContext.Scope ignored = ProjectionRenderContext.enter(
                feed.renderer(), feed.camera, feed.target, feed.source.pos(), feed.level(), feed.lightTexture()
        )) {
            ProjectionLevelRenderer renderer = feed.renderer();
            renderer.prepareCamera(feed.camera, feed.terrain.key.center());
            if (IrisCompatibility.isShaderPackInUse()) {
                feed.terrainReadyFrames = feed.remoteScene.isComplete() ? 2 : 0;
                return;
            }
            SectionRenderDispatcher dispatcher = renderer.sectionRenderDispatcher();
            uploadTerrain(dispatcher);
            Iterable<SectionRenderDispatcher.RenderSection> sections = ((ViewAreaAccessor) renderer.viewArea()).glass$getSections();
            List<SectionRenderDispatcher.RenderSection> visible = renderer.visibleSections();
            visible.clear();
            for (SectionRenderDispatcher.RenderSection section : sections) {
                BlockPos origin = section.getRenderOrigin();
                if (!feed.level().getChunkSource().hasChunk(SectionPos.blockToSectionCoord(origin.getX()), SectionPos.blockToSectionCoord(origin.getZ()))) {
                    continue;
                }
                if (frustum.isVisible(section.getBoundingBox())) {
                    visible.add(section);
                }
            }
            if (feed.terrain.lastBuildFrame != frameSequence) {
                feed.terrain.lastBuildFrame = frameSequence;
                feed.terrain.scheduledBuilds = 0;
                recoverStrandedBuilds(feed.terrain, renderer, sections);
            }
            int buildSlots = Math.max(0, dispatcher.getFreeBufferCount() - dispatcher.getCompileQueueSize());
            buildSlots = Math.min(buildSlots, Math.max(0, feed.compileBufferCount() - feed.terrain.scheduledBuilds));
            Map<Long, Boolean> chunkReadiness = new HashMap<>();
            List<SectionRenderDispatcher.RenderSection> builds = new ArrayList<>();
            boolean visibleCompiled = true;
            for (SectionRenderDispatcher.RenderSection section : visible) {
                if (section.getSectionMesh() == net.minecraft.client.renderer.chunk.CompiledSectionMesh.UNCOMPILED) {
                    visibleCompiled = false;
                }
                if (buildSlots > 0 && canCompile(feed, section, chunkReadiness)) {
                    builds.add(section);
                }
            }
            if (visibleCompiled && builds.isEmpty() && buildSlots > 0) {
                for (SectionRenderDispatcher.RenderSection section : sections) {
                    if (canCompile(feed, section, chunkReadiness)) {
                        builds.add(section);
                    }
                }
            }
            if (!builds.isEmpty()) {
                builds.sort(Comparator.comparing((SectionRenderDispatcher.RenderSection section) ->
                                section.getSectionMesh() != net.minecraft.client.renderer.chunk.CompiledSectionMesh.UNCOMPILED)
                        .thenComparingDouble(section -> section.getRenderOrigin().distToCenterSqr(position)));
                RenderRegionCache regions = new RenderRegionCache();
                for (int i = 0; i < Math.min(buildSlots, builds.size()) && buildPreparationNanos < BUILD_PREPARATION_BUDGET_NANOS; i++) {
                    SectionRenderDispatcher.RenderSection section = builds.get(i);
                    long started = System.nanoTime();
                    renderer.compileSection(section, regions);
                    buildPreparationNanos += System.nanoTime() - started;
                    feed.terrain.scheduledBuilds++;
                }
            }
        }
        feed.terrainReadyFrames = terrainReady(feed) ? Math.min(2, feed.terrainReadyFrames + 1) : 0;
    }

    private static void recoverStrandedBuilds(TerrainResources terrain, ProjectionLevelRenderer renderer,
                                              Iterable<SectionRenderDispatcher.RenderSection> sections) {
        SectionRenderDispatcher dispatcher = renderer.sectionRenderDispatcher();
        boolean idleWithQueue = dispatcher.getCompileQueueSize() > 0 && dispatcher.getFreeBufferCount() >= terrain.bufferCount;
        terrain.strandedFrames = idleWithQueue ? terrain.strandedFrames + 1 : 0;
        if (terrain.strandedFrames >= STRANDED_BUILD_FRAMES) {
            dispatcher.clearCompileQueue();
            for (SectionRenderDispatcher.RenderSection section : sections) {
                SectionPos pos = SectionPos.of(section.getSectionNode());
                renderer.setSectionDirty(pos.x(), pos.y(), pos.z());
            }
            terrain.strandedFrames = 0;
        }
    }

    private static void uploadTerrain(SectionRenderDispatcher dispatcher) {
        dispatcher.lock();
        try {
            dispatcher.uploadTerrainBuffersToGpu();
        } finally {
            dispatcher.unlock();
        }
    }

    private static boolean canCompile(ProjectionFeed feed, SectionRenderDispatcher.RenderSection section, Map<Long, Boolean> chunkReadiness) {
        BlockPos origin = section.getRenderOrigin();
        ProjectionLevelRenderer renderer = feed.renderer();
        return renderer.isSectionDirty(section)
                && chunkReadiness.computeIfAbsent(ChunkPos.pack(SectionPos.blockToSectionCoord(origin.getX()), SectionPos.blockToSectionCoord(origin.getZ())),
                packed -> chunkReady(feed, ChunkPos.unpack(packed)))
                && renderer.hasAllNeighbors(section)
                && feed.level().getLightEngine().lightOnInColumn(SectionPos.getZeroNode(SectionPos.asLong(origin)));
    }

    private static boolean terrainReady(ProjectionFeed feed) {
        if (!feed.remoteScene.isComplete()) {
            return false;
        }
        List<SectionRenderDispatcher.RenderSection> sections = feed.renderer().visibleSections();
        if (sections.isEmpty()) {
            return false;
        }
        for (SectionRenderDispatcher.RenderSection section : sections) {
            if (section.getSectionMesh() == net.minecraft.client.renderer.chunk.CompiledSectionMesh.UNCOMPILED) {
                return false;
            }
        }
        return true;
    }

    private static void renderFeed(DeltaTracker deltaTracker, ProjectionFeed feed, PortalView portalView) {
        if (feed.holdingFrameSinceNanos >= 0L && feed.terrainReadyFrames < 2
                && System.nanoTime() - feed.holdingFrameSinceNanos > HELD_FRAME_TIMEOUT_NANOS) {
            feed.holdingFrameSinceNanos = -1L;
            feed.ready = false;
        }
        if (feed.level() == null || feed.renderer() == null || feed.target == null || feed.lightTexture() == null
                || (feed.terrainReadyFrames < 2 && (!feed.ready || feed.holdingFrameSinceNanos >= 0L))) {
            return;
        }
        feed.holdingFrameSinceNanos = -1L;
        try (ProjectionRenderContext.Scope ignored = ProjectionRenderContext.enter(feed.renderer(), feed.camera,
                feed.target, feed.source.pos(), feed.level(), feed.lightTexture())) {
            IrisCompatibility.preservingCapturedState(() -> {
                feed.lightTexture().update(feed.camera, deltaTracker.getGameTimeDeltaPartialTick(false), frameSequence);
                feed.renderer().renderScene(feed.camera, new Matrix4f(portalView.projection()),
                        new Matrix4f(portalView.skyProjection()), deltaTracker, frameSequence, feed.shaderView);
            });
            feed.ready = feed.shaderView == null || feed.shaderView.isReady();
            feed.available = feed.ready;
            feed.nextRetryFrame = 0L;
        }
    }

    private static PortalView configurePortalCamera(
            ProjectionFeed feed,
            Camera viewerCamera,
            Matrix4f mainProjection,
            RenderTarget mainTarget
    ) {
        if (feed.previewAspect > 0) {
            Direction facing = feed.source.facing().getAxis().isHorizontal() ? feed.source.facing().getOpposite() : Direction.SOUTH;
            Vec3 look = directionVector(facing);
            Vec3 up = directionVector(Direction.UP);
            Vec3 position = cameraPosition(feed.source);
            double elapsedSeconds = (System.nanoTime() - feed.previewStartedNanos) / 1_000_000_000.0;
            float yaw = (float) (elapsedSeconds * Math.PI / 60.0 % (Math.PI * 2));
            Quaternionf rotation = new Quaternionf().rotationY(yaw)
                    .mul(frameRotation(look.cross(up), up, look.scale(-1)))
                    .rotateX((float) Math.toRadians(-10));
            Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(85), feed.previewAspect, 1024.0F, 0.05F,
                    RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
            PortalView preview = new PortalView(position, rotation, projection, projection, 512, Math.max(1, (int) (512 / feed.previewAspect)), false);
            feed.camera.setPose(position, rotation);
            feed.camera.setProjection(projection);
            feed.cameraPosition = position;
            return preview;
        }
        ProjectionView view = feed.alignment == null ? feed.view : feed.alignment.anchor;
        PortalSide portalSide = feed.alignment == null ? feed.side : feed.alignment.side;
        boolean planar = feed.alignment == null ? feed.view.planar() : feed.alignment.planar;
        Vec3 surfaceNormal = view.normal();
        Vec3 viewerOffset = viewerCamera.position().subtract(
                Vec3.atCenterOf(view.projectorPos()).add(surfaceNormal.scale(PORTAL_MAPPING_OFFSET)));
        double side = portalSide.resolve(view, viewerCamera.position());
        Vec3 sourceRight = directionVector(view.uDirection()).scale(side);
        Vec3 sourceUp = directionVector(view.vDirection());
        Vec3 sourceBack = surfaceNormal.scale(side);

        Vec3 destinationLook = directionVector(feed.source.facing().getOpposite());
        Vec3 destinationUp = feed.source.facing().getAxis().isHorizontal()
                ? directionVector(Direction.UP)
                : directionVector(Direction.NORTH);
        Vec3 destinationRight = destinationLook.cross(destinationUp);
        Vec3 destinationBack = destinationLook.scale(-1.0D);

        Quaternionf sourceFrame = frameRotation(sourceRight, sourceUp, sourceBack);
        Quaternionf destinationFrame = frameRotation(destinationRight, destinationUp, destinationBack);
        Quaternionf portalRotation = new Quaternionf(destinationFrame)
                .mul(new Quaternionf(sourceFrame).conjugate());
        Quaternionf cameraRotation = portalRotation
                .mul(new Quaternionf(viewerCamera.rotation()))
                .normalize();

        double horizontalOffset = viewerOffset.dot(sourceRight);
        double verticalOffset = viewerOffset.dot(sourceUp);
        double normalOffset = viewerOffset.dot(sourceBack);
        Vec3 destinationAnchor = cameraPosition(feed.source);
        Vec3 dynamicPosition = Vec3.atCenterOf(feed.source.pos()).add(destinationLook.scale(PORTAL_MAPPING_OFFSET))
                .add(destinationRight.scale(horizontalOffset))
                .add(destinationUp.scale(verticalOffset))
                .add(destinationBack.scale(normalOffset));
        TargetSize targetSize = targetSize(mainTarget);
        Matrix4f projection = planar
                ? clipToPlane(mainProjection, cameraRotation, dynamicPosition, destinationAnchor, destinationLook)
                : mainProjection;
        PortalView portalView = new PortalView(
                dynamicPosition,
                cameraRotation,
                projection,
                mainProjection,
                targetSize.width(),
                targetSize.height(),
                projection != mainProjection
        );
        feed.camera.setPose(portalView.cameraPosition(), portalView.cameraRotation());
        feed.camera.setProjection(portalView.projection());
        feed.cameraPosition = dynamicPosition;
        return portalView;
    }

    private static Matrix4f clipToPlane(Matrix4f projection, Quaternionf cameraRotation, Vec3 cameraPosition,
                                        Vec3 planePoint, Vec3 planeNormal) {
        Vector3f normal = new Vector3f((float) planeNormal.x, (float) planeNormal.y, (float) planeNormal.z)
                .rotate(new Quaternionf(cameraRotation).conjugate());
        float distance = (float) -planePoint.subtract(cameraPosition).dot(planeNormal);
        if (distance > -PORTAL_CLIP_MIN_DISTANCE) {
            return projection;
        }
        Vector4f plane = new Vector4f(normal, distance);
        boolean zZeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        float farNdc = zZeroToOne ? 0.0F : -1.0F;
        Matrix4f inverse = projection.invert(new Matrix4f());
        float maxPlaneDistance = 1.0E-4F;
        for (int corner = 0; corner < 4; corner++) {
            Vector4f farCorner = inverse.transform(new Vector4f((corner & 1) == 0 ? -1.0F : 1.0F,
                    (corner & 2) == 0 ? -1.0F : 1.0F, farNdc, 1.0F));
            maxPlaneDistance = Math.max(maxPlaneDistance, plane.dot(farCorner));
        }
        float scale = (1.0F - farNdc) / maxPlaneDistance;
        Matrix4f clipped = new Matrix4f(projection);
        clipped.m02(projection.m03() - scale * plane.x);
        clipped.m12(projection.m13() - scale * plane.y);
        clipped.m22(projection.m23() - scale * plane.z);
        clipped.m32(projection.m33() - scale * plane.w);
        return clipped;
    }

    private static void applyCamera(
            ProjectionCamera camera,
            ClientLevel level,
            Minecraft minecraft,
            PortalView portalView
    ) {
        camera.setLevel(level);
        camera.attributeProbe().tick(level, portalView.cameraPosition());
        camera.setEntity(minecraft.player);
        camera.setPose(portalView.cameraPosition(), portalView.cameraRotation());
        camera.setProjection(portalView.projection());
    }

    private static Quaternionf frameRotation(Vec3 right, Vec3 up, Vec3 back) {
        return new Quaternionf().setFromNormalized(new Matrix3f(
                (float) right.x, (float) right.y, (float) right.z,
                (float) up.x, (float) up.y, (float) up.z,
                (float) back.x, (float) back.y, (float) back.z
        ));
    }

    private static void updateStatic(ProjectionFeed feed, ClientLevel level, PortalView portalView) {
        long now = System.nanoTime();
        float target = feed.previewAspect <= 0 && !portalView.clipped() && viewBlocked(level, portalView) ? 1.0F : 0.0F;
        float step = feed.staticUpdatedNanos < 0L ? 1.0F : (float) (now - feed.staticUpdatedNanos) / STATIC_FADE_NANOS;
        feed.staticUpdatedNanos = now;
        feed.staticAmount = target > feed.staticAmount
                ? Math.min(target, feed.staticAmount + step)
                : Math.max(target, feed.staticAmount - step);
    }

    private static boolean viewBlocked(ClientLevel level, PortalView portalView) {
        Matrix4f projection = portalView.projection();
        float halfWidth = Camera.PROJECTION_Z_NEAR * VIEW_BLOCK_MARGIN / Math.abs(projection.m00());
        float halfHeight = Camera.PROJECTION_Z_NEAR * VIEW_BLOCK_MARGIN / Math.abs(projection.m11());
        Vec3 camera = portalView.cameraPosition();
        Vector3f offset = new Vector3f();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int point = 0; point < 5; point++) {
            float x = point == 4 ? 0.0F : (point & 1) == 0 ? -halfWidth : halfWidth;
            float y = point == 4 ? 0.0F : (point & 2) == 0 ? -halfHeight : halfHeight;
            offset.set(x, y, -Camera.PROJECTION_Z_NEAR).rotate(portalView.cameraRotation());
            pos.set(camera.x + offset.x, camera.y + offset.y, camera.z + offset.z);
            if (blocksView(level, pos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean blocksView(ClientLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos.getY())) {
            return false;
        }
        if (level.getChunkSource().getChunk(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()), ChunkStatus.FULL, false) == null) {
            return true;
        }
        BlockState state = level.getBlockState(pos);
        return state.getRenderShape() != RenderShape.INVISIBLE && state.isViewBlocking(level, pos);
    }

    private static Vec3 directionVector(Direction direction) {
        return new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
    }

    private static PortalSide portalSide(ProjectionView view) {
        ProjectionOwner owner = new ProjectionOwner(view.dimension(), view.projectorPos());
        return PORTAL_SIDES.computeIfAbsent(owner, ignored -> new PortalSide());
    }

    private static TargetSize targetSize(RenderTarget mainTarget) {
        return new TargetSize(
                Math.max(1, mainTarget.width),
                Math.max(1, mainTarget.height)
        );
    }

    private static void resizeTarget(ProjectionFeed feed, int width, int height) {
        TextureTarget target = feed.target;
        if (target == null || target.width == width && target.height == height) {
            return;
        }
        target.resize(width, height);
        feed.ready = false;
    }

    private static void markUnavailable(ProjectionFeed feed) {
        boolean clear = feed.ready || feed.available;
        feed.ready = false;
        feed.available = false;
        if (clear && feed.target != null) {
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                    feed.target.getColorTexture(), new Vector4f(0.0F, 0.0F, 0.0F, 1.0F), feed.target.getDepthTexture(), 0.0);
        }
    }

    private static void restoreMainRenderState(Matrix4f mainModelView) {
        if (ProjectionRenderContext.isActive()) {
            throw new IllegalStateException("Projection render context escaped its scope");
        }
        RenderSystem.getModelViewStack().set(mainModelView);
    }

    private static void closeFeed(ProjectionFeed feed) {
        long started = System.nanoTime();
        releaseRemoteFeed(feed);
        reportSlowStage(feed, "release", started);
    }

    private static void releaseRemoteFeed(ProjectionFeed feed) {
        disposeFeedResources(feed);
        RemoteSceneHandle remoteScene = feed.remoteScene;
        feed.remoteScene = null;
        if (remoteScene != null) {
            RemoteSceneClientManager.release(remoteScene);
        }
    }

    private static void disposeFeedResources(ProjectionFeed feed) {
        feed.ready = false;
        feed.terrainReadyFrames = 0;
        feed.available = false;
        if (feed.shaderView != null) {
            cleanup(feed, "shader pipeline", feed.shaderView::close);
        }

        TextureManager textureManager = feed.textureManager;
        feed.textureManager = null;
        ProjectionTargetTexture textureProxy = feed.textureProxy;
        feed.textureProxy = null;
        if (textureManager != null && textureProxy != null) {
            cleanup(feed, "texture registration", () -> textureManager.release(feed.textureLocation));
            cleanup(feed, "texture proxy", textureProxy::close);
        }

        releaseTerrain(feed);

        TextureTarget target = feed.target;
        feed.target = null;
        if (target != null) {
            cleanup(feed, "render target", target::destroyBuffers);
        }
        feed.camera.reset();
    }

    private static void releaseTerrain(ProjectionFeed feed) {
        TerrainResources terrain = feed.terrain;
        if (feed.renderer() != null && feed.remoteScene != null && feed.level() == feed.remoteScene.level()) {
            cleanup(feed, "remote renderer attachment", () -> RemoteSceneClientManager.detachRenderer(feed.remoteScene, feed.renderer()));
        }
        feed.terrain = null;
        if (terrain == null || --terrain.references > 0) {
            return;
        }
        TERRAINS.remove(terrain.key, terrain);
        if (terrain.renderer != null) {
            cleanup(feed, "renderer level", () -> terrain.renderer.setLevel(null));
            cleanup(feed, "level renderer", terrain.renderer::close);
        }
        if (terrain.buffers != null) {
            cleanup(feed, "render buffers", () -> retireRenderBuffers(terrain.buffers, terrain.bufferCount));
        }
    }

    private static void cleanup(ProjectionFeed feed, String resource, Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to release projection feed {} {}", feed.source.key(), resource, exception);
        }
    }

    private static void retireRenderBuffers(RenderBuffers renderBuffers, int bufferCount) {
        RenderResources.closeBufferBuilders(renderBuffers);
        SectionBufferBuilderPool pool = renderBuffers.sectionBufferPool();
        if (pool.getFreeBufferCount() >= bufferCount) {
            RenderResources.closeAvailablePoolBuffers(pool);
        } else {
            RETIRED_BUFFER_POOLS.add(new RetiredBufferPool(pool, bufferCount));
        }
    }

    public static void drainRetiredBufferPools() {
        RenderSystem.assertOnRenderThread();
        Iterator<RetiredBufferPool> iterator = RETIRED_BUFFER_POOLS.iterator();
        while (iterator.hasNext()) {
            RetiredBufferPool retired = iterator.next();
            if (retired.pool().getFreeBufferCount() >= retired.bufferCount()) {
                RenderResources.closeAvailablePoolBuffers(retired.pool());
                iterator.remove();
            }
        }
    }

    private static Identifier nextTextureLocation() {
        long sequence = ++textureSequence;
        return Identifier.fromNamespaceAndPath(
                "glass",
                "projection/feed/" + Long.toUnsignedString(sequence, 36)
        );
    }

    private record RetiredBufferPool(SectionBufferBuilderPool pool, int bufferCount) {
    }

    private record TerrainKey(ClientLevel level, ChunkPos center, int radius, BlockPos hiddenBlock) {
    }

    private static final class TerrainResources {
        private TerrainKey key;
        private ProjectionLevelRenderer renderer;
        private RenderBuffers buffers;
        private ProjectionLightmap lightTexture;
        private int bufferCount;
        private int references;
        private final long createdNanos = System.nanoTime();
        private long lastBuildFrame = -1L;
        private int scheduledBuilds;
        private int strandedFrames;
        private long lastRekeyFrame = -1L;

        private TerrainResources(TerrainKey key) {
            this.key = key;
        }
    }

    private static Vec3 cameraPosition(ProjectionSource source) {
        Direction facing = source.facing().getOpposite();
        return Vec3.atCenterOf(source.pos()).add(
                facing.getStepX() * CAMERA_FACE_OFFSET,
                facing.getStepY() * CAMERA_FACE_OFFSET,
                facing.getStepZ() * CAMERA_FACE_OFFSET
        );
    }

    private record FeedKey(
            ProjectionSource.Key source,
            ResourceKey<Level> projectorDimension,
            BlockPos projectorPos,
            boolean preview
    ) {
        private FeedKey {
            projectorPos = projectorPos.immutable();
        }
    }

    private record ProjectionOwner(ResourceKey<Level> dimension, BlockPos projectorPos) {
        private ProjectionOwner {
            projectorPos = projectorPos.immutable();
        }
    }

    private record TargetSize(int width, int height) {
    }

    private record PortalView(
            Vec3 cameraPosition,
            Quaternionf cameraRotation,
            Matrix4f projection,
            Matrix4f skyProjection,
            int targetWidth,
            int targetHeight,
            boolean clipped
    ) {
        private PortalView {
            cameraRotation = new Quaternionf(cameraRotation);
            projection = new Matrix4f(projection);
            skyProjection = new Matrix4f(skyProjection);
        }
    }

    private record SurfaceFaceKey(BlockPos position, Direction normal) {
    }

    private static final class ProjectionAlignment {
        private final ProjectionView anchor;
        private final PortalSide side = new PortalSide();
        private final long createdFrame = frameSequence;
        private boolean planar = true;

        private ProjectionAlignment(ProjectionView anchor) {
            this.anchor = anchor;
        }
    }

    public static final class ProjectionFeed {
        private final FeedKey key;
        private final ProjectionSource source;
        private final Identifier textureLocation;
        private final ProjectionCamera camera = new ProjectionCamera();
        private final PortalSide side;
        private final FeedTrace trace = new FeedTrace();
        private final IrisCompatibility.ShaderView shaderView = IrisCompatibility.createShaderView();
        private ProjectionView view;
        private ProjectionAlignment alignment;
        private Vec3 cameraPosition;
        private long lastRequestFrame = -1L;
        private long lastRequestNanos;
        private long lastVisibleFrame = -1L;
        private long nextRetryFrame;
        private ProjectionStage stage = ProjectionStage.REQUESTED;
        private boolean ready;
        private float previewAspect;
        private float staticAmount;
        private long staticUpdatedNanos = -1L;
        private final long previewStartedNanos = System.nanoTime();
        private int terrainReadyFrames;
        private long holdingFrameSinceNanos = -1L;
        private boolean available;
        private RemoteSceneHandle remoteScene;
        private TerrainResources terrain;
        private TextureTarget target;
        private TextureManager textureManager;
        private ProjectionTargetTexture textureProxy;

        private ProjectionFeed(
                FeedKey key,
                ProjectionSource source,
                ProjectionView view,
                PortalSide side,
                Identifier textureLocation
        ) {
            this.key = key;
            this.source = source;
            this.view = view;
            this.side = side;
            this.textureLocation = textureLocation;
            this.cameraPosition = ProjectionRenderManager.cameraPosition(source);
        }

        public ProjectionSource source() {
            return source;
        }

        @Nullable
        private ProjectionLevelRenderer renderer() {
            return terrain == null ? null : terrain.renderer;
        }

        @Nullable
        private ClientLevel level() {
            return terrain == null ? null : terrain.key.level();
        }

        private int rendererRadius() {
            return terrain == null ? 0 : terrain.key.radius();
        }

        @Nullable
        private ProjectionLightmap lightTexture() {
            return terrain == null ? null : terrain.lightTexture;
        }

        @Nullable
        private RenderBuffers renderBuffers() {
            return terrain == null ? null : terrain.buffers;
        }

        private int compileBufferCount() {
            return terrain == null ? 0 : terrain.bufferCount;
        }

        public Identifier textureLocation() {
            return textureLocation;
        }

        public GpuTextureView colorTextureView() {
            return target == null ? null : target.getColorTextureView();
        }

        public int colorTextureId() {
            TextureTarget currentTarget = target;
            return currentTarget == null || currentTarget.getColorTexture() == null ? 0 : System.identityHashCode(currentTarget.getColorTexture());
        }

        public boolean isReady() {
            return ProjectionRenderManager.isReady(this);
        }

        public float staticAmount() {
            return staticAmount;
        }

        public boolean isFailed() {
            return stage == ProjectionStage.RENDER_FAILED;
        }

        public ProjectionStage stage() {
            return stage;
        }

        public FeedDiagnostics diagnostics() {
            return new FeedDiagnostics(
                    key.projectorPos(),
                    source,
                    cameraPosition,
                    level() == null ? "none" : level() == activeLevel ? "local" : "remote",
                    lastRequestFrame < 0L ? -1L : frameSequence - lastRequestFrame,
                    lastVisibleFrame < 0L ? -1L : frameSequence - lastVisibleFrame,
                    Math.max(0L, nextRetryFrame - frameSequence),
                    terrainReadyFrames,
                    FeedDiagnostics.SectionStats.of(renderer(), pos -> chunkReady(this, pos)),
                    compileBufferCount(),
                    terrain == null ? 0 : terrain.references,
                    terrain == null ? 0L : (System.nanoTime() - terrain.createdNanos) / 1_000_000L,
                    colorTextureId(),
                    rendererRadius(),
                    remoteScene == null ? "none" : String.valueOf(remoteScene.diagnostics())
            );
        }
    }

    private static final class ProjectionTargetTexture extends AbstractTexture {
        private ProjectionFeed feed;

        private ProjectionTargetTexture(ProjectionFeed feed) {
            this.feed = feed;
        }

        @Override
        public GpuTexture getTexture() {
            return feed == null || feed.target == null ? null : feed.target.getColorTexture();
        }

        @Override
        public GpuTextureView getTextureView() {
            return feed == null || feed.target == null ? null : feed.target.getColorTextureView();
        }

        @Override
        public void close() {
            feed = null;
        }
    }
}
