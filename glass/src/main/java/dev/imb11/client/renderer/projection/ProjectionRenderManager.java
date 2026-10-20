package dev.imb11.client.renderer.projection;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.imb11.client.ClientSourcePoses;
import dev.imb11.client.compat.IrisCompatibility;
import dev.imb11.client.compat.SableRenderCompatibility;
import dev.imb11.compat.SableCompatibility;
import dev.imb11.sync.SourcePose;
import dev.imb11.client.remote.RemoteSceneClientManager;
import dev.imb11.debug.FeedDiagnostics;
import dev.imb11.debug.FeedTrace;
import dev.imb11.client.gui.ChannelScreen;
import dev.imb11.client.remote.ProjectionChunkStorage;
import dev.imb11.client.remote.RemoteSceneHandle;
import dev.imb11.blocks.entity.ProjectorBlockEntity;
import dev.imb11.client.renderer.block.ProjectorBlockEntityRenderer;
import dev.imb11.mixins.CompiledSectionAccessor;
import dev.imb11.mixins.LevelRendererBufferAccessor;
import dev.imb11.mixins.LevelRendererInvoker;
import dev.imb11.mixins.SectionRenderDispatcherAccessor;
import dev.imb11.projection.ProjectionSurface;
import dev.imb11.projection.ProjectionChunkRegion;
import dev.imb11.sync.ProjectionSource;
import dev.imb11.sync.remote.RemoteSubscriptionId;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SectionBufferBuilderPool;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
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
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.function.Predicate;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.TimeUnit;

public final class ProjectionRenderManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("glass/projection-renderer");
    private static final int REQUEST_RENDER_GRACE_FRAMES = 2;
    private static final long FEED_RETENTION_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static final long PREVIEW_RETENTION_NANOS = TimeUnit.MINUTES.toNanos(2L);
    private static final int FAILURE_RETRY_FRAMES = 60;
    private static final int FEED_BUILD_BUFFERS = Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    private static final long BUILD_PREPARATION_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(2L);
    private static final long UPLOAD_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(2L);
    private static final long HELD_FRAME_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5L);
    private static final float CAMERA_FACE_OFFSET = 0.5625F;
    private static final double PORTAL_MAPPING_OFFSET = 0.5D;
    private static final float PORTAL_CLIP_MIN_DISTANCE = 0.05F;
    private static final double GRID_RECENTER_DISTANCE = 16.0D;
    private static final float VIEW_BLOCK_MARGIN = 1.25F;
    private static final long STATIC_FADE_NANOS = TimeUnit.MILLISECONDS.toNanos(120L);
    private static final RenderType[] TERRAIN_LAYERS = {
            RenderType.solid(),
            RenderType.cutoutMipped(),
            RenderType.cutout(),
            RenderType.translucent(),
            RenderType.tripwire()
    };
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
    private static long uploadNanos;

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
        side.track(view, SableCompatibility.toLocalPosition(currentLevel, projectorPos,
                minecraft.gameRenderer.getMainCamera().getPosition()), frameSequence);
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
                && feed.target.getColorTextureId() > 0;
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
        Vec3 viewer = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
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
            alignment.side.track(combined, SableCompatibility.toLocalPosition(activeLevel, anchor.projectorPos(), viewer),
                    frameSequence);
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

    public static void onMainRendererRebuilt(LevelRenderer candidate) {
        Minecraft minecraft = Minecraft.getInstance();
        if (candidate != minecraft.levelRenderer) {
            return;
        }
        RenderResources.runOnRenderThread(() -> {
            for (ProjectionFeed feed : List.copyOf(FEEDS.values())) {
                disposeFeedResources(feed);
            }
        });
    }

    public static void onMainSectionDirty(LevelRenderer candidate, int sectionX, int sectionY, int sectionZ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (candidate != minecraft.levelRenderer) {
            return;
        }
        for (ProjectionFeed feed : List.copyOf(FEEDS.values())) {
            if (feed.level() == activeLevel
                    && feed.renderer() != null
                    && sectionInFeedView(feed, sectionX, sectionY, sectionZ)) {
                ProjectionSections.markDirty(feed.renderer(), sectionX, sectionY, sectionZ);
            }
        }
    }

    public static void onMainChunkLoaded(LevelRenderer candidate, ChunkPos chunkPos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (candidate != minecraft.levelRenderer) {
            return;
        }
        for (ProjectionFeed feed : List.copyOf(FEEDS.values())) {
            if (feed.level() == activeLevel && feed.renderer() != null) {
                feed.renderer().onChunkLoaded(chunkPos);
                dirtyChunkSections(feed, chunkPos);
            }
        }
    }

    public static void onMainRendererTick(LevelRenderer candidate) {
        Minecraft minecraft = Minecraft.getInstance();
        if (candidate != minecraft.levelRenderer) {
            return;
        }
        Set<LevelRenderer> ticked = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ProjectionFeed feed : List.copyOf(FEEDS.values())) {
            if (feed.renderer() != null && ticked.add(feed.renderer())) {
                feed.renderer().tick();
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
        uploadNanos = 0L;

        Minecraft minecraft = gameRenderer.getMinecraft();
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
        if (minecraft.screen instanceof ChannelScreen<?> channelScreen) {
            channelScreen.preparePreview();
        }
        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        Camera viewerCamera = gameRenderer.getMainCamera();
        Vec3 viewerPosition = viewerCamera.getPosition();
        Matrix4f mainProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4f mainModelView = new Matrix4f(RenderSystem.getModelViewMatrix());
        boolean initializedFeedThisFrame = false;
        for (ProjectionFeed feed : List.copyOf(FEEDS.values())) {
            if (FEEDS.get(feed.key) != feed) {
                continue;
            }
            feed.side.update(SableCompatibility.toLocalPosition(currentLevel, feed.view.projectorPos(), viewerPosition),
                    frameSequence);
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

            if (IrisCompatibility.isShaderPackActive()) {
                feed.stage = ProjectionStage.SHADER_PACK_ACTIVE;
                markUnavailable(feed, mainTarget);
                continue;
            }

            SourceFrame destination = sourceFrame(feed.source, partialTick);
            if (destination == null) {
                feed.stage = ProjectionStage.WAITING_FOR_SOURCE_POSE;
                markUnavailable(feed, mainTarget);
                continue;
            }

            try {
                PortalView portalView = configurePortalCamera(
                        feed,
                        destination,
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
                LightTexture sceneLight = remoteScene.lightTexture();
                if (sceneLevel == null || grantedRadius < 1 || remoteScene.isUnavailable()
                        || (!feed.ready && !remoteScene.isReady(cameraCenter))) {
                    feed.stage = remoteLevel == null || grantedRadius < 1 ? ProjectionStage.WAITING_FOR_GRANT : ProjectionStage.WAITING_FOR_CAMERA_CHUNKS;
                    markUnavailable(feed, mainTarget);
                    continue;
                }
                applyCamera(feed.camera, sceneLevel, minecraft, portalView, partialTick);
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
                renderFeed(minecraft, gameRenderer, deltaTracker, partialTick, feed, portalView, mainTarget);
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
                restoreMainRenderState(mainTarget, gameRenderer, mainProjection, mainModelView);
            }
        }
        restoreMainRenderState(mainTarget, gameRenderer, mainProjection, mainModelView);
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
            ChunkPos cameraChunk = new ChunkPos(BlockPos.containing(feed.cameraPosition));
            resetUnloadedChunkSections(feed, chunkPos);
            if (chunkPos.equals(new ChunkPos(BlockPos.containing(feed.sourceCenter)))
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
        if (!chunkInFeedView(feed, chunkPos.x, chunkPos.z)) {
            return;
        }
        ProjectionSections.markColumnDirty(renderer, level, chunkPos);
    }

    private static void resetUnloadedChunkSections(ProjectionFeed feed, ChunkPos chunkPos) {
        ClientLevel level = feed.level();
        LevelRenderer renderer = feed.renderer();
        if (level == null || renderer == null || !chunkInFeedView(feed, chunkPos.x, chunkPos.z)) {
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
                && sectionY >= level.getMinSection()
                && sectionY < level.getMaxSection()
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
            ViewArea viewArea = ((LevelRendererBufferAccessor) renderer).glass$getViewArea();
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
            @Nullable LightTexture lightTexture,
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
            feed.target = new TextureTarget(portalView.targetWidth(), portalView.targetHeight(), true, Minecraft.ON_OSX);
            feed.target.setFilterMode(GL11.GL_LINEAR);
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
            } finally {
                ClientLevel mainLevel = minecraft.level;
                if (mainLevel != null) {
                    minecraft.getEntityRenderDispatcher().setLevel(mainLevel);
                }
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
        Vec3 position = feed.camera.getPosition();
        Matrix4f modelView = new Matrix4f().rotation(feed.camera.rotation().conjugate(new Quaternionf()));
        Frustum frustum = new Frustum(modelView, portalView.projection());
        frustum.prepare(position.x, position.y, position.z);
        try (ProjectionRenderContext.Scope ignored = ProjectionRenderContext.enter(
                feed.renderer(), feed.camera, feed.target, feed.source.pos(), feed.level(), feed.lightTexture()
        )) {
            feed.renderer().prepareCamera(feed.camera, feed.terrain.key.center());
            LevelRendererBufferAccessor accessor = (LevelRendererBufferAccessor) feed.renderer();
            SectionRenderDispatcher dispatcher = feed.renderer().getSectionRenderDispatcher();
            uploadTerrain(dispatcher);
            List<SectionRenderDispatcher.RenderSection> visible = accessor.glass$getVisibleSections();
            visible.clear();
            for (SectionRenderDispatcher.RenderSection section : accessor.glass$getViewArea().sections) {
                BlockPos origin = section.getOrigin();
                if (!feed.level().getChunkSource().hasChunk(SectionPos.blockToSectionCoord(origin.getX()), SectionPos.blockToSectionCoord(origin.getZ()))) {
                    continue;
                }
                if (frustum.isVisible(section.getBoundingBox())) {
                    visible.add(section);
                }
            }
            int buildSlots = Math.max(0, feed.compileBufferCount() * 2 - dispatcher.getToBatchCount()
                    - (feed.compileBufferCount() - dispatcher.getFreeBufferCount()));
            if (feed.terrain.lastBuildFrame != frameSequence) {
                feed.terrain.lastBuildFrame = frameSequence;
                feed.terrain.scheduledBuilds = 0;
            }
            buildSlots = Math.min(buildSlots, Math.max(0, feed.compileBufferCount() * 2 - feed.terrain.scheduledBuilds));
            Map<Long, Boolean> chunkReadiness = new HashMap<>();
            List<SectionRenderDispatcher.RenderSection> builds = new ArrayList<>();
            boolean visibleCompiled = true;
            for (SectionRenderDispatcher.RenderSection section : visible) {
                if (section.getCompiled() == SectionRenderDispatcher.CompiledSection.UNCOMPILED) {
                    visibleCompiled = false;
                }
                if (buildSlots > 0 && canCompile(feed, section, chunkReadiness)) {
                    builds.add(section);
                }
            }
            if (visibleCompiled && builds.isEmpty() && buildSlots > 0) {
                for (SectionRenderDispatcher.RenderSection section : accessor.glass$getViewArea().sections) {
                    if (canCompile(feed, section, chunkReadiness)) {
                        builds.add(section);
                    }
                }
            }
            if (!builds.isEmpty()) {
                builds.sort(Comparator.comparing((SectionRenderDispatcher.RenderSection section) ->
                                section.getCompiled() != SectionRenderDispatcher.CompiledSection.UNCOMPILED)
                        .thenComparingDouble(section -> section.getOrigin().distToCenterSqr(position)));
                RenderRegionCache regions = new RenderRegionCache();
                for (int i = 0; i < Math.min(buildSlots, builds.size()) && buildPreparationNanos < BUILD_PREPARATION_BUDGET_NANOS; i++) {
                    SectionRenderDispatcher.RenderSection section = builds.get(i);
                    long started = System.nanoTime();
                    section.rebuildSectionAsync(dispatcher, regions);
                    section.setNotDirty();
                    buildPreparationNanos += System.nanoTime() - started;
                    feed.terrain.scheduledBuilds++;
                }
            }
        }
        feed.terrainReadyFrames = terrainReady(feed) ? Math.min(2, feed.terrainReadyFrames + 1) : 0;
    }

    private static void uploadTerrain(SectionRenderDispatcher dispatcher) {
        var pending = ((SectionRenderDispatcherAccessor) dispatcher).glass$getPendingUploads();
        while (uploadNanos < UPLOAD_BUDGET_NANOS) {
            Runnable upload = pending.poll();
            if (upload == null) {
                break;
            }
            long started = System.nanoTime();
            upload.run();
            uploadNanos += System.nanoTime() - started;
        }
    }

    private static boolean canCompile(ProjectionFeed feed, SectionRenderDispatcher.RenderSection section, Map<Long, Boolean> chunkReadiness) {
        BlockPos origin = section.getOrigin();
        return section.isDirty()
                && chunkReadiness.computeIfAbsent(ChunkPos.asLong(SectionPos.blockToSectionCoord(origin.getX()), SectionPos.blockToSectionCoord(origin.getZ())),
                packed -> chunkReady(feed, new ChunkPos(packed)))
                && section.hasAllNeighbors()
                && feed.level().getLightEngine().lightOnInSection(SectionPos.of(origin));
    }

    private static boolean terrainReady(ProjectionFeed feed) {
        if (!feed.remoteScene.isComplete()) {
            return false;
        }
        List<SectionRenderDispatcher.RenderSection> sections = ((LevelRendererBufferAccessor) feed.renderer()).glass$getVisibleSections();
        if (sections.isEmpty()) {
            return false;
        }
        for (SectionRenderDispatcher.RenderSection section : sections) {
            if (section.getCompiled() == SectionRenderDispatcher.CompiledSection.UNCOMPILED) {
                return false;
            }
        }
        return true;
    }

    private static void renderFeed(
            Minecraft minecraft,
            GameRenderer gameRenderer,
            DeltaTracker deltaTracker,
            float partialTick,
            ProjectionFeed feed,
            PortalView portalView,
            RenderTarget mainTarget
    ) {
        ClientLevel level = feed.level();
        LevelRenderer renderer = feed.renderer();
        TextureTarget target = feed.target;
        LightTexture lightTexture = feed.lightTexture();
        RemoteSceneHandle remoteScene = feed.remoteScene;
        if (level == null
                || renderer == null
                || target == null
                || lightTexture == null
                || remoteScene == null
                || minecraft.player == null) {
            return;
        }
        if (feed.holdingFrameSinceNanos >= 0L && feed.terrainReadyFrames < 2
                && System.nanoTime() - feed.holdingFrameSinceNanos > HELD_FRAME_TIMEOUT_NANOS) {
            feed.holdingFrameSinceNanos = -1L;
            feed.ready = false;
        }
        if (feed.terrainReadyFrames < 2 && (!feed.ready || feed.holdingFrameSinceNanos >= 0L)) {
            return;
        }
        feed.holdingFrameSinceNanos = -1L;

        Vec3 cameraPosition = feed.camera.getPosition();
        int renderDistanceChunks = ProjectionRenderContext.feedRenderDistance(
                minecraft.options.getEffectiveRenderDistance(),
                feed.rendererRadius()
        );
        float renderDistanceBlocks = renderDistanceChunks * 16.0F;
        Matrix4f projection = new Matrix4f(portalView.projection());
        Matrix4f skyProjection = new Matrix4f(portalView.skyProjection());
        Quaternionf inverseRotation = feed.camera.rotation().conjugate(new Quaternionf());
        Matrix4f modelView = new Matrix4f().rotation(inverseRotation);

        boolean worldFog = level.effects().isFoggyAt(Mth.floor(cameraPosition.x), Mth.floor(cameraPosition.y));
        FogRenderer.setupColor(
                feed.camera,
                partialTick,
                level,
                renderDistanceChunks,
                0.0F
        );
        FogRenderer.levelFogColor();

        target.setClearColor(FogRenderer.fogRed, FogRenderer.fogGreen, FogRenderer.fogBlue, 1.0F);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);
        RenderSystem.setShaderGameTime(level.getGameTime(), partialTick);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        try (ProjectionRenderContext.Scope ignored = ProjectionRenderContext.enter(
                renderer,
                feed.camera,
                target,
                feed.source.pos(),
                level,
                lightTexture
        )) {
            lightTexture.updateLightTexture(partialTick);
            lightTexture.turnOnLightLayer();
            IrisCompatibility.renderWithPipeline(renderer, () -> {
                RenderSystem.setShader(GameRenderer::getPositionShader);
                renderer.renderSky(
                        modelView,
                        skyProjection,
                        partialTick,
                        feed.camera,
                        worldFog,
                        () -> FogRenderer.setupFog(
                                feed.camera,
                                FogRenderer.FogMode.FOG_SKY,
                                renderDistanceBlocks,
                                worldFog,
                                partialTick
                        )
                );
                restoreProjectionTarget(target);
                FogRenderer.setupFog(
                        feed.camera,
                        FogRenderer.FogMode.FOG_TERRAIN,
                        Math.max(renderDistanceBlocks, 32.0F),
                        worldFog,
                        partialTick
                );

                for (RenderType layer : TERRAIN_LAYERS) {
                    if (layer == RenderType.translucent()) {
                        renderEntities(minecraft, gameRenderer, deltaTracker, feed, portalView, modelView, projection, partialTick);
                        if (feed.terrain.references > 1) {
                            sortSharedTransparency(feed);
                        }
                    }
                    renderLayer(feed.renderer(), layer, cameraPosition, modelView, projection, target);
                }
                SableRenderCompatibility.renderAfterSections(level, cameraPosition, modelView, projection, partialTick);
                restoreProjectionTarget(target);

                if (minecraft.options.getCloudsType() != CloudStatus.OFF) {
                    renderer.renderClouds(
                            new PoseStack(),
                            modelView,
                            projection,
                            partialTick,
                            cameraPosition.x,
                            cameraPosition.y,
                            cameraPosition.z
                    );
                    restoreProjectionTarget(target);
                }

                renderWeather(gameRenderer, feed, modelView, projection, partialTick, cameraPosition, lightTexture);
            });

            feed.ready = true;
            feed.available = true;
            feed.nextRetryFrame = 0L;
        } finally {
            mainTarget.bindWrite(true);
            gameRenderer.lightTexture().turnOnLightLayer();
        }
    }

    private static void renderWeather(GameRenderer gameRenderer, ProjectionFeed feed, Matrix4f modelView, Matrix4f projection,
                                      float partialTick, Vec3 cameraPosition, LightTexture lightTexture) {
        LevelRendererInvoker invoker = (LevelRendererInvoker) feed.renderer();
        RenderSystem.getModelViewStack().pushMatrix();
        try {
            RenderSystem.getModelViewStack().set(modelView);
            RenderSystem.applyModelViewMatrix();
            gameRenderer.resetProjectionMatrix(projection);
            RenderSystem.depthMask(false);
            invoker.glass$renderSnowAndRain(lightTexture, partialTick, cameraPosition.x, cameraPosition.y, cameraPosition.z);
            invoker.glass$renderWorldBorder(feed.camera);
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.applyModelViewMatrix();
            gameRenderer.resetProjectionMatrix(projection);
            restoreProjectionTarget(feed.target);
        }
    }

    private static void renderEntities(Minecraft minecraft, GameRenderer gameRenderer, DeltaTracker deltaTracker, ProjectionFeed feed,
                                       PortalView portalView, Matrix4f modelView, Matrix4f projection, float partialTick) {
        Vec3 position = feed.camera.getPosition();
        Frustum frustum = new Frustum(modelView, portalView.projection());
        frustum.prepare(position.x, position.y, position.z);
        var dispatcher = minecraft.getEntityRenderDispatcher();
        var blockDispatcher = minecraft.getBlockEntityRenderDispatcher();
        var buffers = feed.renderBuffers().bufferSource();
        PoseStack poses = new PoseStack();
        RenderSystem.getModelViewStack().pushMatrix();
        try {
            setupEntityLighting(feed.level());
            RenderSystem.getModelViewStack().set(modelView);
            RenderSystem.applyModelViewMatrix();
            gameRenderer.resetProjectionMatrix(projection);
            dispatcher.prepare(feed.level(), feed.camera, null);
            blockDispatcher.prepare(feed.level(), feed.camera, null);
            LevelRendererInvoker invoker = (LevelRendererInvoker) feed.renderer();
            var tickRateManager = feed.level().tickRateManager();
            for (Entity entity : feed.level().entitiesForRendering()) {
                if (entity.isRemoved() || !dispatcher.shouldRender(entity, frustum, position.x, position.y, position.z)) {
                    continue;
                }
                BlockPos blockPos = entity.blockPosition();
                if (!feed.level().isOutsideBuildHeight(blockPos.getY()) && !feed.renderer().isSectionCompiled(blockPos)) {
                    continue;
                }
                if (entity instanceof LocalPlayer && feed.camera.getEntity() != entity) {
                    continue;
                }
                if (entity.tickCount == 0) {
                    entity.xOld = entity.getX();
                    entity.yOld = entity.getY();
                    entity.zOld = entity.getZ();
                }
                float partialEntity = deltaTracker.getGameTimeDeltaPartialTick(!tickRateManager.isEntityFrozen(entity));
                invoker.glass$renderEntity(entity, position.x, position.y, position.z, partialEntity, poses, buffers);
            }
            LevelRendererBufferAccessor accessor = (LevelRendererBufferAccessor) feed.renderer();
            Set<BlockEntity> blockEntities = new LinkedHashSet<>();
            for (SectionRenderDispatcher.RenderSection section : accessor.glass$getVisibleSections()) {
                blockEntities.addAll(section.getCompiled().getRenderableBlockEntities());
            }
            synchronized (accessor.glass$getGlobalBlockEntities()) {
                blockEntities.addAll(accessor.glass$getGlobalBlockEntities());
            }
            for (BlockEntity blockEntity : blockEntities) {
                if (blockEntity.isRemoved() || blockEntity instanceof ProjectorBlockEntity
                        || blockEntity.getBlockPos().equals(feed.source.pos())) {
                    continue;
                }
                BlockPos pos = blockEntity.getBlockPos();
                poses.pushPose();
                try {
                    poses.translate(pos.getX() - position.x, pos.getY() - position.y, pos.getZ() - position.z);
                    blockDispatcher.render(blockEntity, partialTick, poses, buffers);
                } finally {
                    poses.popPose();
                }
            }
            SableRenderCompatibility.renderBlockEntities(feed.level(), blockDispatcher, buffers, position, partialTick,
                    blockEntity -> blockEntity instanceof ProjectorBlockEntity || blockEntity.getBlockPos().equals(feed.source.pos()));
            buffers.endBatch();
            renderBlockBreaking(minecraft, feed, poses, position);
        } finally {
            dispatcher.prepare(minecraft.level, gameRenderer.getMainCamera(), minecraft.crosshairPickEntity);
            blockDispatcher.prepare(minecraft.level, gameRenderer.getMainCamera(), minecraft.hitResult);
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.applyModelViewMatrix();
            gameRenderer.resetProjectionMatrix(portalView.projection());
            restoreProjectionTarget(feed.target);
        }
    }

    private static void renderBlockBreaking(Minecraft minecraft, ProjectionFeed feed, PoseStack poses, Vec3 position) {
        ClientLevel level = feed.level();
        LevelRenderer progressSource = level == minecraft.level ? minecraft.levelRenderer : feed.renderer();
        var progress = ((LevelRendererBufferAccessor) progressSource).glass$getDestructionProgress();
        if (progress.isEmpty()) {
            return;
        }
        var crumbling = feed.renderBuffers().crumblingBufferSource();
        for (var entry : progress.long2ObjectEntrySet()) {
            BlockPos pos = BlockPos.of(entry.getLongKey());
            SortedSet<BlockDestructionProgress> progresses = entry.getValue();
            if (pos.distToCenterSqr(position.x, position.y, position.z) > 1024.0 || progresses == null || progresses.isEmpty()) {
                continue;
            }
            poses.pushPose();
            try {
                poses.translate(pos.getX() - position.x, pos.getY() - position.y, pos.getZ() - position.z);
                VertexConsumer consumer = new SheetedDecalTextureGenerator(
                        crumbling.getBuffer(ModelBakery.DESTROY_TYPES.get(progresses.last().getProgress())), poses.last(), 1.0F);
                minecraft.getBlockRenderer().renderBreakingTexture(level.getBlockState(pos), pos, level, poses, consumer);
            } finally {
                poses.popPose();
            }
        }
        crumbling.endBatch();
    }

    private static void sortSharedTransparency(ProjectionFeed feed) {
        TerrainResources terrain = feed.terrain;
        if (terrain.sortBuffer == null) {
            terrain.sortBuffer = new ByteBufferBuilder(1536);
        }
        Vec3 camera = feed.camera.getPosition();
        try {
            for (SectionRenderDispatcher.RenderSection section : ((LevelRendererBufferAccessor) terrain.renderer).glass$getVisibleSections()) {
                MeshData.SortState sort = ((CompiledSectionAccessor) section.getCompiled()).glass$getTransparencyState();
                if (sort == null) {
                    continue;
                }
                BlockPos origin = section.getOrigin();
                VertexBuffer buffer = section.getBuffer(RenderType.translucent());
                buffer.bind();
                ByteBufferBuilder.Result indices = sort.buildSortedIndexBuffer(terrain.sortBuffer, VertexSorting.byDistance(
                        (float) (camera.x - origin.getX()), (float) (camera.y - origin.getY()), (float) (camera.z - origin.getZ())));
                if (indices != null) {
                    buffer.uploadIndexBuffer(indices);
                }
            }
        } finally {
            VertexBuffer.unbind();
            terrain.sortBuffer.clear();
        }
    }

    private static PortalView configurePortalCamera(
            ProjectionFeed feed,
            SourceFrame destination,
            Camera viewerCamera,
            Matrix4f mainProjection,
            RenderTarget mainTarget
    ) {
        feed.sourceCenter = destination.center();
        if (feed.previewAspect > 0) {
            Direction facing = feed.source.facing().getAxis().isHorizontal() ? feed.source.facing().getOpposite() : Direction.SOUTH;
            Vec3 look = destination.rotate(directionVector(facing));
            Vec3 up = destination.rotate(directionVector(Direction.UP));
            Vec3 position = destination.cameraPosition();
            double elapsedSeconds = (System.nanoTime() - feed.previewStartedNanos) / 1_000_000_000.0;
            float yaw = (float) (elapsedSeconds * Math.PI / 60.0 % (Math.PI * 2));
            Quaternionf rotation = new Quaternionf(destination.orientation()).rotateY(yaw)
                    .mul(new Quaternionf(destination.orientation()).conjugate())
                    .mul(frameRotation(look.cross(up), up, look.scale(-1)))
                    .rotateX((float) Math.toRadians(-10));
            Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(85), feed.previewAspect, 0.05F, 1024.0F);
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
        Vec3 viewerPosition = SableCompatibility.toLocalPosition(activeLevel, view.projectorPos(), viewerCamera.getPosition());
        Quaternionf viewerRotation = SableCompatibility.toLocalRotation(activeLevel, view.projectorPos(),
                new Quaternionf(viewerCamera.rotation()));
        Vec3 viewerOffset = viewerPosition.subtract(
                Vec3.atCenterOf(view.projectorPos()).add(surfaceNormal.scale(PORTAL_MAPPING_OFFSET)));
        double side = portalSide.resolve(view, viewerPosition);
        Vec3 sourceRight = directionVector(view.uDirection()).scale(side);
        Vec3 sourceUp = directionVector(view.vDirection());
        Vec3 sourceBack = surfaceNormal.scale(side);

        Vec3 destinationLook = destination.look();
        Vec3 destinationUp = destination.up();
        Vec3 destinationRight = destinationLook.cross(destinationUp);
        Vec3 destinationBack = destinationLook.scale(-1.0D);

        Quaternionf sourceFrame = frameRotation(sourceRight, sourceUp, sourceBack);
        Quaternionf destinationFrame = frameRotation(destinationRight, destinationUp, destinationBack);
        Quaternionf portalRotation = new Quaternionf(destinationFrame)
                .mul(new Quaternionf(sourceFrame).conjugate());
        Quaternionf cameraRotation = portalRotation
                .mul(viewerRotation)
                .normalize();

        double horizontalOffset = viewerOffset.dot(sourceRight);
        double verticalOffset = viewerOffset.dot(sourceUp);
        double normalOffset = viewerOffset.dot(sourceBack);
        Vec3 destinationAnchor = destination.cameraPosition();
        Vec3 dynamicPosition = destination.center().add(destinationLook.scale(PORTAL_MAPPING_OFFSET))
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

    private static void applyCamera(
            ProjectionCamera camera,
            ClientLevel level,
            Minecraft minecraft,
            PortalView portalView,
            float partialTick
    ) {
        camera.setup(level, minecraft.player, false, false, partialTick);
        camera.setPose(portalView.cameraPosition(), portalView.cameraRotation());
        camera.setProjection(portalView.projection());
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
        float nearNdc = -1.0F;
        float farNdc = 1.0F;
        Matrix4f inverse = projection.invert(new Matrix4f());
        float maxPlaneDistance = 1.0E-4F;
        for (int corner = 0; corner < 4; corner++) {
            Vector4f farCorner = inverse.transform(new Vector4f((corner & 1) == 0 ? -1.0F : 1.0F,
                    (corner & 2) == 0 ? -1.0F : 1.0F, farNdc, 1.0F));
            maxPlaneDistance = Math.max(maxPlaneDistance, plane.dot(farCorner));
        }
        float scale = (farNdc - nearNdc) / maxPlaneDistance;
        Matrix4f clipped = new Matrix4f(projection);
        clipped.m02(scale * plane.x + nearNdc * projection.m03());
        clipped.m12(scale * plane.y + nearNdc * projection.m13());
        clipped.m22(scale * plane.z + nearNdc * projection.m23());
        clipped.m32(scale * plane.w + nearNdc * projection.m33());
        return clipped;
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
        float halfWidth = GameRenderer.PROJECTION_Z_NEAR * VIEW_BLOCK_MARGIN / Math.abs(projection.m00());
        float halfHeight = GameRenderer.PROJECTION_Z_NEAR * VIEW_BLOCK_MARGIN / Math.abs(projection.m11());
        Vec3 camera = portalView.cameraPosition();
        Vector3f offset = new Vector3f();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int point = 0; point < 5; point++) {
            float x = point == 4 ? 0.0F : (point & 1) == 0 ? -halfWidth : halfWidth;
            float y = point == 4 ? 0.0F : (point & 2) == 0 ? -halfHeight : halfHeight;
            offset.set(x, y, -GameRenderer.PROJECTION_Z_NEAR).rotate(portalView.cameraRotation());
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
                Math.max(1, mainTarget.viewWidth),
                Math.max(1, mainTarget.viewHeight)
        );
    }

    private static void resizeTarget(ProjectionFeed feed, int width, int height) {
        TextureTarget target = feed.target;
        if (target == null || target.viewWidth == width && target.viewHeight == height) {
            return;
        }
        target.resize(width, height, Minecraft.ON_OSX);
        target.setFilterMode(GL11.GL_LINEAR);
        feed.ready = false;
    }

    private static void markUnavailable(ProjectionFeed feed, RenderTarget mainTarget) {
        boolean clear = feed.ready || feed.available;
        feed.ready = false;
        feed.available = false;
        if (!clear || feed.target == null) {
            return;
        }
        feed.target.setClearColor(0.0F, 0.0F, 0.0F, 1.0F);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        feed.target.clear(Minecraft.ON_OSX);
        mainTarget.bindWrite(true);
    }

    private static void renderLayer(
            ProjectionLevelRenderer renderer,
            RenderType layer,
            Vec3 cameraPosition,
            Matrix4f modelView,
            Matrix4f projection,
            TextureTarget target
    ) {
        boolean completed = false;
        try {
            renderer.renderTerrainLayer(layer, cameraPosition, modelView, projection);
            completed = true;
        } finally {
            if (!completed) {
                layer.clearRenderState();
            }
            target.bindWrite(false);
        }
    }

    private static void restoreProjectionTarget(TextureTarget target) {
        target.bindWrite(false);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        VertexBuffer.unbind();
    }

    private static void restoreMainRenderState(
            RenderTarget mainTarget,
            GameRenderer gameRenderer,
            Matrix4f mainProjection,
            Matrix4f mainModelView
    ) {
        if (ProjectionRenderContext.isActive()) {
            throw new IllegalStateException("Projection render context escaped its scope");
        }
        Minecraft minecraft = gameRenderer.getMinecraft();
        if (minecraft.level != null) {
            minecraft.getEntityRenderDispatcher().setLevel(minecraft.level);
            setupEntityLighting(minecraft.level);
        }
        gameRenderer.lightTexture().turnOnLightLayer();
        mainTarget.bindWrite(true);
        gameRenderer.resetProjectionMatrix(new Matrix4f(mainProjection));
        RenderSystem.getModelViewStack().set(mainModelView);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.enableCull();
        RenderSystem.disablePolygonOffset();
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.resetTextureMatrix();
        FogRenderer.setupNoFog();
        VertexBuffer.unbind();
    }

    private static void setupEntityLighting(ClientLevel level) {
        if (level.effects().constantAmbientLight()) {
            Lighting.setupNetherLevel();
        } else {
            Lighting.setupLevel();
        }
    }

    private static void closeFeed(ProjectionFeed feed) {
        long started = System.nanoTime();
        releaseRemoteFeed(feed);
        ProjectionSurfaceRenderer.releaseTexture(feed.textureLocation);
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
            cleanup(feed, "renderer global buffers", () -> RenderResources.closeGlobalBuffers(terrain.renderer));
            cleanup(feed, "level renderer", terrain.renderer::close);
            ClientLevel currentLevel = Minecraft.getInstance().level;
            if (currentLevel != null) {
                Minecraft.getInstance().getEntityRenderDispatcher().setLevel(currentLevel);
            }
        }
        if (terrain.buffers != null) {
            cleanup(feed, "render buffers", () -> retireRenderBuffers(terrain.buffers, terrain.bufferCount));
        }
        if (terrain.sortBuffer != null) {
            cleanup(feed, "transparency sort buffer", terrain.sortBuffer::close);
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

    private static ResourceLocation nextTextureLocation() {
        long sequence = ++textureSequence;
        return ResourceLocation.fromNamespaceAndPath(
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
        private LightTexture lightTexture;
        private ByteBufferBuilder sortBuffer;
        private int bufferCount;
        private int references;
        private final long createdNanos = System.nanoTime();
        private long lastBuildFrame = -1L;
        private int scheduledBuilds;
        private long lastRekeyFrame = -1L;

        private TerrainResources(TerrainKey key) {
            this.key = key;
        }
    }

    @Nullable
    private static SourceFrame sourceFrame(ProjectionSource source, float partialTick) {
        if (!ClientSourcePoses.isOnSubLevel(source)) {
            return new SourceFrame(source, Vec3.atCenterOf(source.pos()), new Quaternionf());
        }
        SourcePose pose = ClientSourcePoses.resolve(source, partialTick);
        return pose == null ? null : new SourceFrame(source, pose.position(), pose.orientation());
    }

    private record SourceFrame(ProjectionSource source, Vec3 center, Quaternionf orientation) {
        private SourceFrame {
            orientation = new Quaternionf(orientation);
        }

        @Override
        public Quaternionf orientation() {
            return new Quaternionf(orientation);
        }

        private Vec3 rotate(Vec3 direction) {
            Vector3f rotated = new Vector3f((float) direction.x, (float) direction.y, (float) direction.z).rotate(orientation);
            return new Vec3(rotated.x, rotated.y, rotated.z);
        }

        private Vec3 look() {
            return rotate(directionVector(source.facing().getOpposite()));
        }

        private Vec3 up() {
            return rotate(source.facing().getAxis().isHorizontal()
                    ? directionVector(Direction.UP)
                    : directionVector(Direction.NORTH));
        }

        private Vec3 cameraPosition() {
            return center.add(look().scale(CAMERA_FACE_OFFSET));
        }
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
        private final ResourceLocation textureLocation;
        private final ProjectionCamera camera = new ProjectionCamera();
        private final PortalSide side;
        private final FeedTrace trace = new FeedTrace();
        private ProjectionView view;
        private ProjectionAlignment alignment;
        private Vec3 cameraPosition;
        private Vec3 sourceCenter;
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
                ResourceLocation textureLocation
        ) {
            this.key = key;
            this.source = source;
            this.view = view;
            this.side = side;
            this.textureLocation = textureLocation;
            SourceFrame frame = sourceFrame(source, 1.0F);
            this.sourceCenter = frame == null ? Vec3.atCenterOf(source.pos()) : frame.center();
            this.cameraPosition = frame == null ? sourceCenter : frame.cameraPosition();
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
        private LightTexture lightTexture() {
            return terrain == null ? null : terrain.lightTexture;
        }

        @Nullable
        private RenderBuffers renderBuffers() {
            return terrain == null ? null : terrain.buffers;
        }

        private int compileBufferCount() {
            return terrain == null ? 0 : terrain.bufferCount;
        }

        public ResourceLocation textureLocation() {
            return textureLocation;
        }

        public int colorTextureId() {
            TextureTarget currentTarget = target;
            return currentTarget == null ? 0 : currentTarget.getColorTextureId();
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
        public int getId() {
            ProjectionFeed currentFeed = feed;
            return currentFeed == null ? 0 : currentFeed.colorTextureId();
        }

        @Override
        public void load(ResourceManager resourceManager) throws IOException {
        }

        @Override
        public void releaseId() {
        }

        @Override
        public void close() {
            feed = null;
        }
    }
}
