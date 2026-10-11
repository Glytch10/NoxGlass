package dev.imb11.client.renderer.block;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.PrimitiveTopology;
import net.minecraft.client.renderer.BindGroupLayouts;
import com.mojang.math.Axis;
import dev.imb11.blocks.ProjectorBlock;
import dev.imb11.debug.ProjectorActivationTrace;
import dev.imb11.blocks.entity.ProjectorBlockEntity;
import dev.imb11.client.ClientProjectionSourceRegistry;
import dev.imb11.client.renderer.projection.ProjectionRenderManager;
import dev.imb11.client.renderer.projection.ProjectionSurfaceRenderer;
import dev.imb11.client.renderer.projection.ProjectionStage;
import dev.imb11.projection.ProjectionSurface;
import dev.imb11.sync.ProjectionSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class ProjectorBlockEntityRenderer {
    private static final com.mojang.blaze3d.pipeline.RenderPipeline LENS_PIPELINE = com.mojang.blaze3d.pipeline.RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("glass", "projector_lens"))
            .withVertexShader(Identifier.fromNamespaceAndPath("glass", "core/projector_lens"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("glass", "core/projector_lens"))
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withDepthStencilState(new com.mojang.blaze3d.pipeline.DepthStencilState(com.mojang.blaze3d.platform.CompareOp.GREATER_THAN_OR_EQUAL, false))
            .withColorTargetState(new com.mojang.blaze3d.pipeline.ColorTargetState(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT))
            .withCull(false).build();
    private static final long LOADING_FADE_NANOS = 500_000_000L;
    private static final float LOADING_MAX_OPACITY = 0.35F;
    private static final int VIEW_DISTANCE = 64;
    private static final double VIEW_DISTANCE_SQUARED = VIEW_DISTANCE * VIEW_DISTANCE;    private static final Map<ClientLevel, Map<BlockPos, ProjectorBlockEntity>> LOADED_PROJECTORS = new IdentityHashMap<>();
    private static ClientLevel frustumLevel;
    private static long frustumFrame = Long.MIN_VALUE;
    private static Frustum frustum;

    private ProjectorBlockEntityRenderer() {
    }

    public static void renderAll(Matrix4f modelView) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        var projectors = LOADED_PROJECTORS.get(level);
        if (projectors == null) {
            return;
        }
        Vec3 camera = minecraft.gameRenderer.mainCamera().position();
        for (ProjectorBlockEntity projector : List.copyOf(projectors.values())) {
            if (projector.isRemoved() || distanceToSqr(renderBounds(projector, projector.getProjectionSurface()), camera) > VIEW_DISTANCE_SQUARED) {
                continue;
            }
            PoseStack poses = new PoseStack();
            poses.mulPose(modelView);
            BlockPos pos = projector.getBlockPos();
            poses.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
            render(projector, poses);
        }
    }

    public static void registerLoaded(ClientLevel level, ProjectorBlockEntity entity) {
        Map<BlockPos, ProjectorBlockEntity> projectors = LOADED_PROJECTORS.computeIfAbsent(
                level,
                ignored -> new HashMap<>()
        );
        ProjectorBlockEntity previous = projectors.put(entity.getBlockPos().immutable(), entity);
        if (previous != null && previous != entity) {
            ProjectorActivationTrace.forget(previous);
        }
    }

    public static void unregisterLoaded(ClientLevel level, ProjectorBlockEntity entity) {
        ProjectorActivationTrace.forget(entity);
        Map<BlockPos, ProjectorBlockEntity> projectors = LOADED_PROJECTORS.get(level);
        if (projectors != null) {
            projectors.remove(entity.getBlockPos(), entity);
            if (projectors.isEmpty()) {
                LOADED_PROJECTORS.remove(level);
            }
        }
    }

    public static void onMainRendererRebuilt() {
        clearFrustum();
    }

    public static void clearLoaded() {
        LOADED_PROJECTORS.clear();
        ProjectorActivationTrace.clear();
    }

    public static void prepareNearby(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        Map<BlockPos, ProjectorBlockEntity> projectors = LOADED_PROJECTORS.get(level);
        if (projectors == null || minecraft.player == null) {
            return;
        }
        Vec3 viewer = minecraft.gameRenderer.mainCamera().position();
        List<ProjectorBlockEntity> nearby = new ArrayList<>();
        for (ProjectorBlockEntity projector : projectors.values()) {
            projector.setClientProjectionReady(false);
            if (!projector.isActive() || projector.isRemoved()) {
                projector.stopLoadingAnimation();
            }
            if (projector.isRemoved()) {
                continue;
            }
            if (ClientProjectionSourceRegistry.resolve(projector.getChannel()) == null) {
                ProjectorActivationTrace.report(projector, ProjectionStage.MISSING_CHANNEL_SOURCE, null);
                continue;
            }
            if (distanceToSqr(new AABB(projector.getBlockPos()).inflate(ProjectionSurface.MAX_RADIUS), viewer)
                    > VIEW_DISTANCE_SQUARED) {
                ProjectorActivationTrace.report(projector, ProjectionStage.OUTSIDE_PRELOAD_RANGE, null);
                continue;
            }
            projector.prepareProjectionSurface();
            ProjectionSurface surface = projector.getProjectionSurface();
            if (surface != null && distanceToSqr(surface.renderBounds(), viewer) <= VIEW_DISTANCE_SQUARED) {
                nearby.add(projector);
            } else {
                ProjectorActivationTrace.report(projector, surface == null ? ProjectionStage.MISSING_SURFACE : ProjectionStage.OUTSIDE_SURFACE_RANGE, null);
            }
        }
        nearby.sort(Comparator.comparing((ProjectorBlockEntity projector) -> !projector.isProjectionVisible())
                .thenComparingDouble(projector -> distanceToSqr(projector.getProjectionSurface().renderBounds(), viewer))
                .thenComparingLong(projector -> projector.getBlockPos().asLong()));
        for (ProjectorBlockEntity projector : nearby) {
            ProjectionRenderManager.ProjectionFeed feed = ProjectionRenderManager.requestFeed(
                    ClientProjectionSourceRegistry.resolve(projector.getChannel()),
                    projector.getBlockPos(),
                    projector.getProjectionSurface()
            );
            projector.setClientProjectionReady(ProjectionRenderManager.isReady(feed));
            ProjectorActivationTrace.report(projector, feed == null ? ProjectionStage.FEED_REQUEST_REJECTED : feed.stage(), feed);
        }
        ProjectionRenderManager.alignFeeds(nearby);
    }

    public static void prepareSurfaceBlending(ClientLevel level) {
        List<ProjectionSurfaceRenderer.Projection> projections = new ArrayList<>();
        Map<BlockPos, ProjectorBlockEntity> projectors = LOADED_PROJECTORS.get(level);
        if (projectors != null) {
            for (ProjectorBlockEntity projector : projectors.values()) {
                ProjectionSurface surface = projector.getProjectionSurface();
                if (projector.isRemoved() || !projector.isProjectionVisible() || surface == null) {
                    continue;
                }
                ProjectionRenderManager.ProjectionFeed feed = ProjectionRenderManager.preparedFeed(
                        ClientProjectionSourceRegistry.resolve(projector.getChannel()),
                        projector.getBlockPos(),
                        surface
                );
                if (ProjectionRenderManager.isReady(feed)) {
                    projections.add(new ProjectionSurfaceRenderer.Projection(
                            projector.getBlockPos(), surface, feed, projector.getRevealDistance()
                    ));
                }
            }
        }
        ProjectionSurfaceRenderer.prepare(level, projections);
    }

    public static void releaseLevel(ClientLevel level) {
        ProjectorActivationTrace.forgetLevel(level);
        LOADED_PROJECTORS.remove(level);
        if (frustumLevel == level) {
            clearFrustum();
        }
    }

    public static void reset() {
        ProjectorActivationTrace.clear();
        clearFrustum();
    }

    private static void render(ProjectorBlockEntity entity, PoseStack matrices) {
        if (!(entity.getLevel() instanceof ClientLevel clientLevel)) {
            return;
        }

        ProjectionSurface surface = entity.getProjectionSurface();
        AABB renderBounds = renderBounds(entity, surface);
        long frame = ProjectionRenderManager.currentFrameSequence();
        if (!entity.claimRenderFrame(frame)) {
            return;
        }
        if (!currentFrustum(clientLevel, frame).isVisible(renderBounds)) {
            return;
        }

        renderReadyGlint(entity, matrices);

        if (!entity.isProjectionVisible() || surface == null) {
            entity.stopLoadingAnimation();
            return;
        }

        ProjectionSource source = ClientProjectionSourceRegistry.resolve(entity.getChannel());
        ProjectionRenderManager.ProjectionFeed feed = ProjectionRenderManager.visibleFeed(
                source,
                entity.getBlockPos(),
                surface
        );
        boolean loading = entity.isActive() && source != null && !ProjectionRenderManager.isReady(feed);
        ProjectionSurfaceRenderer.render(
                clientLevel,
                entity.getBlockPos(),
                surface,
                feed,
                matrices,
                loadingOpacity(entity, loading)
        );
    }

    private static void renderReadyGlint(ProjectorBlockEntity entity, PoseStack matrices) {
        matrices.pushPose();
        matrices.translate(0.5D, 0.5D, 0.5D);
        Direction facing = entity.getBlockState().getValue(ProjectorBlock.FACING);
        if (facing == Direction.DOWN) {
            matrices.mulPose(Axis.XP.rotationDegrees(-180.0F));
        } else if (facing.getAxis().isHorizontal()) {
            matrices.mulPose(Axis.YP.rotationDegrees(-facing.get2DDataValue() * 90.0F));
            matrices.mulPose(Axis.XP.rotationDegrees(-90.0F));
        }
        matrices.translate(-0.5D, -0.5D, -0.5D);
        var vertices = dev.imb11.client.renderer.projection.ProjectionMesh.begin(DefaultVertexFormat.POSITION_TEX);
        Matrix4f pose = matrices.last().pose();
        float min = 6.0F / 16.0F;
        float max = 10.0F / 16.0F;
        float y = -0.001F;
        vertices.addVertex(pose, min, y, min).setUv(0.0F, 0.0F);
        vertices.addVertex(pose, max, y, min).setUv(1.0F, 0.0F);
        vertices.addVertex(pose, max, y, max).setUv(1.0F, 1.0F);
        vertices.addVertex(pose, min, y, max).setUv(0.0F, 1.0F);
        var texture = Minecraft.getInstance().getTextureManager().getTexture(Identifier.withDefaultNamespace("textures/misc/enchanted_glint_item.png")).getTextureView();
        try (var mesh = new dev.imb11.client.renderer.projection.ProjectionMesh(vertices.buildOrThrow())) {
            mesh.draw(LENS_PIPELINE, new Matrix4f(), Map.of("Sampler0", texture), null);
        }
        matrices.popPose();
    }

    private static float loadingOpacity(ProjectorBlockEntity entity, boolean loading) {
        if (!loading) {
            entity.stopLoadingAnimation();
            return -1.0F;
        }
        long now = System.nanoTime();
        long elapsed = (now - entity.loadingAnimationStart(now)) % (LOADING_FADE_NANOS * 2L);
        double phase = (double) elapsed / LOADING_FADE_NANOS;
        return LOADING_MAX_OPACITY * (float) (0.5D - 0.5D * Math.cos(Math.PI * phase));
    }

    private static AABB renderBounds(ProjectorBlockEntity entity, ProjectionSurface surface) {
        AABB blockBounds = new AABB(entity.getBlockPos());
        return surface == null || !entity.isProjectionVisible()
                ? blockBounds
                : blockBounds.minmax(surface.renderBounds());
    }

    private static double distanceToSqr(AABB bounds, Vec3 position) {
        double x = Math.max(Math.max(bounds.minX - position.x, 0.0D), position.x - bounds.maxX);
        double y = Math.max(Math.max(bounds.minY - position.y, 0.0D), position.y - bounds.maxY);
        double z = Math.max(Math.max(bounds.minZ - position.z, 0.0D), position.z - bounds.maxZ);
        return x * x + y * y + z * z;
    }

    private static Frustum currentFrustum(ClientLevel level, long frame) {
        if (frustum == null || frustumLevel != level || frustumFrame != frame) {
            Minecraft minecraft = Minecraft.getInstance();
            Vec3 cameraPosition = minecraft.gameRenderer.mainCamera().position();
            frustum = new Frustum(
                    minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.viewRotationMatrix,
                    new Matrix4f(minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix)
            );
            frustum.prepare(cameraPosition.x, cameraPosition.y, cameraPosition.z);
            frustumLevel = level;
            frustumFrame = frame;
        }
        return frustum;
    }

    private static void clearFrustum() {
        frustumLevel = null;
        frustumFrame = Long.MIN_VALUE;
        frustum = null;
    }
}
