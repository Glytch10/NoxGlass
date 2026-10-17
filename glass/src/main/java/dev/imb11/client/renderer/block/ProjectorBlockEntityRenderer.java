package dev.imb11.client.renderer.block;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import dev.imb11.blocks.ProjectorBlock;
import dev.imb11.debug.ProjectorActivationTrace;
import dev.imb11.blocks.entity.ProjectorBlockEntity;
import dev.imb11.client.ClientProjectionSourceRegistry;
import dev.imb11.client.ShaderRegistrar;
import dev.imb11.client.renderer.projection.ProjectionRenderManager;
import dev.imb11.client.renderer.projection.ProjectionSurfaceRenderer;
import dev.imb11.client.renderer.projection.ProjectionStage;
import dev.imb11.projection.ProjectionSurface;
import dev.imb11.sync.ProjectionSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class ProjectorBlockEntityRenderer implements BlockEntityRenderer<ProjectorBlockEntity> {
    private static ShaderInstance lensShader;
    private static final RenderType READY_GLINT = RenderType.create(
            "glass_projector_ready_glint",
            DefaultVertexFormat.POSITION_TEX,
            VertexFormat.Mode.QUADS,
            1536,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(() -> lensShader))
                    .setTextureState(new RenderStateShard.TextureStateShard(ItemRenderer.ENCHANTED_GLINT_ITEM, true, false))
                    .setTexturingState(RenderStateShard.GLINT_TEXTURING)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false)
    );
    private static final long LOADING_FADE_NANOS = 500_000_000L;
    private static final float LOADING_MAX_OPACITY = 0.35F;
    private static final int VIEW_DISTANCE = 64;
    private static final double VIEW_DISTANCE_SQUARED = VIEW_DISTANCE * VIEW_DISTANCE;
    private static final Map<ClientLevel, Map<BlockPos, ProjectorBlockEntity>> LOADED_PROJECTORS = new IdentityHashMap<>();
    private static ClientLevel frustumLevel;
    private static long frustumFrame = Long.MIN_VALUE;
    private static Frustum frustum;

    public ProjectorBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    public static void registerShader(ShaderRegistrar registrar) throws IOException {
        registrar.register(ResourceLocation.fromNamespaceAndPath("glass", "projector_lens"),
                DefaultVertexFormat.POSITION_TEX, shader -> lensShader = shader);
    }

    @Override
    public boolean shouldRenderOffScreen(ProjectorBlockEntity entity) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return VIEW_DISTANCE;
    }

    @Override
    public boolean shouldRender(ProjectorBlockEntity entity, Vec3 cameraPosition) {
        if (!(entity.getLevel() instanceof ClientLevel clientLevel) || entity.isRemoved()) {
            return false;
        }
        ProjectionSurface surface = entity.getProjectionSurface();
        if (surface != null) {
            ProjectionRenderManager.trackViewer(clientLevel, entity.getBlockPos(), surface, cameraPosition);
        }
        return distanceToSqr(renderBounds(entity, surface), cameraPosition) <= VIEW_DISTANCE_SQUARED;
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
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == level) {
            minecraft.levelRenderer.updateGlobalBlockEntities(
                    previous == null || previous == entity ? List.of() : List.of(previous),
                    List.of(entity)
            );
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
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == level) {
            minecraft.levelRenderer.updateGlobalBlockEntities(List.of(entity), List.of());
        }
    }

    public static void onMainRendererRebuilt(LevelRenderer renderer) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (renderer != minecraft.levelRenderer || level == null) {
            return;
        }
        Map<BlockPos, ProjectorBlockEntity> projectors = LOADED_PROJECTORS.get(level);
        if (projectors != null && !projectors.isEmpty()) {
            renderer.updateGlobalBlockEntities(List.of(), List.copyOf(projectors.values()));
        }
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
        Vec3 viewer = minecraft.gameRenderer.getMainCamera().getPosition();
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

    @Override
    public void render(ProjectorBlockEntity entity, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay) {
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

        renderReadyGlint(entity, matrices, vertexConsumers);

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

    private static void renderReadyGlint(ProjectorBlockEntity entity, PoseStack matrices, MultiBufferSource buffers) {
        if (lensShader == null) {
            return;
        }
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
        VertexConsumer vertices = buffers.getBuffer(READY_GLINT);
        Matrix4f pose = matrices.last().pose();
        float min = 6.0F / 16.0F;
        float max = 10.0F / 16.0F;
        float y = -0.001F;
        vertices.addVertex(pose, min, y, min).setUv(0.0F, 0.0F);
        vertices.addVertex(pose, max, y, min).setUv(1.0F, 0.0F);
        vertices.addVertex(pose, max, y, max).setUv(1.0F, 1.0F);
        vertices.addVertex(pose, min, y, max).setUv(0.0F, 1.0F);
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
            Vec3 cameraPosition = minecraft.gameRenderer.getMainCamera().getPosition();
            frustum = new Frustum(
                    new Matrix4f(RenderSystem.getModelViewMatrix()),
                    new Matrix4f(RenderSystem.getProjectionMatrix())
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
