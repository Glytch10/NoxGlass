package dev.imb11.client.compat;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.imb11.client.renderer.projection.ProjectionCamera;
import dev.imb11.client.renderer.projection.ProjectionLevelRenderer;
import dev.imb11.mixins.MinecraftRendererAccessor;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.blending.BlendModeStorage;
import net.irisshaders.iris.gl.blending.DepthColorStorage;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.helpers.MatrixUtils;
import net.irisshaders.iris.mixin.CloudRendererAccessor;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.IrisTimeUniforms;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.GameRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL33C;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

final class IrisProjectionRenderer implements IrisCompatibility.ShaderView {
    private static final List<Field> CAPTURED_FIELDS = fields(CapturedRenderingState.class, false);
    private static final List<Field> IMMEDIATE_FIELDS = fields(ImmediateState.class, true);
    private static final List<Field> SHADOW_FIELDS = fields(ShadowRenderer.class, true);
    private static final List<Field> MATRIX_STACK_FIELDS = fields(Matrix4fStack.class, false);
    private IrisRenderingPipeline pipeline;
    private WorldRenderingSettings settings;
    private ShaderPack pack;
    private NamespacedId dimension;
    private ProjectionLevelRenderer renderer;
    private Camera camera;
    private boolean ready;

    @Override
    public Object pipeline() {
        return pipeline;
    }

    @Override
    public Camera camera() {
        return camera;
    }

    @Override
    public GameRenderState renderState() {
        return renderer.renderState();
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public void render(ProjectionLevelRenderer renderer, ProjectionCamera camera, Matrix4f projection,
                       DeltaTracker delta, Runnable render) {
        ShaderPack currentPack = Iris.getCurrentPack().orElse(null);
        if (currentPack == null) {
            close();
            render.run();
            ready = true;
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        var previousLevel = minecraft.level;
        var previousRenderer = minecraft.levelRenderer;
        var previousBuffers = ((LevelRendererAccessor) renderer).getRenderBuffers();
        var modelView = RenderSystem.getModelViewStack();
        var previousModelView = new Matrix4f(modelView);
        boolean previousCull = minecraft.smartCull;
        int drawFramebuffer = GL33C.glGetInteger(GL33C.GL_DRAW_FRAMEBUFFER_BINDING);
        int readFramebuffer = GL33C.glGetInteger(GL33C.GL_READ_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL33C.glGetIntegerv(GL33C.GL_VIEWPORT, viewport);
        var previousProjection = RenderSystem.getProjectionMatrixBuffer();
        var previousProjectionType = RenderSystem.getProjectionType();
        this.renderer = renderer;
        this.camera = camera;
        try (var captured = snapshot(CAPTURED_FIELDS, CapturedRenderingState.INSTANCE);
             var immediate = snapshot(IMMEDIATE_FIELDS, null);
             var shadows = snapshot(SHADOW_FIELDS, null);
             var matrixStack = snapshot(MATRIX_STACK_FIELDS, modelView);
             var active = IrisCompatibility.useShaderView(this)) {
            minecraft.level = renderer.projectionLevel();
            ((MinecraftRendererAccessor) minecraft).glass$levelRenderer(renderer);
            NamespacedId currentDimension = Iris.getCurrentDimension();
            if (pack != currentPack || !currentDimension.equals(dimension)) {
                close();
                pack = currentPack;
                dimension = currentDimension;
                settings = new WorldRenderingSettings();
            }
            try (var worldSettings = IrisCompatibility.useWorldSettings(settings)) {
                ImmediateState.isRenderingLevel = true;
                ImmediateState.renderWithExtendedVertexFormat = true;
                ImmediateState.usingTessellation = false;
                ImmediateState.isRenderingBEs = false;
                CapturedRenderingState capturedState = CapturedRenderingState.INSTANCE;
                var cameraState = renderState().levelRenderState.cameraRenderState;
                capturedState.setGbufferModelView(new Matrix4f(cameraState.viewRotationMatrix));
                capturedState.setGbufferProjection(MatrixUtils.undoRevZ(new Matrix4f(projection)));
                float partialTick = delta.getGameTimeDeltaPartialTick(false);
                capturedState.setTickDelta(partialTick);
                capturedState.setCurrentEntity(-1);
                capturedState.setCurrentBlockEntity(-1);
                capturedState.setCurrentRenderedItem(-1);
                capturedState.setFogColor(cameraState.fogData.color.x, cameraState.fogData.color.y, cameraState.fogData.color.z);
                var cloudTexture = ((CloudRendererAccessor) renderer.cloudRenderer()).getTexture();
                capturedState.setCloudTime(cloudTexture == null ? 0.0F
                        : ((renderer.projectionLevel().getGameTime() % (cloudTexture.width() * 400)) + partialTick) * 0.03F);
                IrisTimeUniforms.updateTime();
                if (pipeline == null) {
                    pipeline = new IrisRenderingPipeline(pack.getProgramSet(dimension));
                }
                pipeline.beginLevelRendering();
                pipeline.setPhase(WorldRenderingPhase.NONE);
                var terrain = renderer.shaderTerrain(pack);
                terrain.prepare(camera, projection);
                ready = terrain.isReady();
                IrisRenderSystem.backupAndDisableCullingState(pipeline.shouldDisableOcclusionCulling());
                try {
                    pipeline.renderShadows((LevelRendererAccessor) renderer, camera, cameraState);
                    render.run();
                    pipeline.finalizeLevelRendering();
                } finally {
                    IrisRenderSystem.restoreCullingState();
                }
            }
        } catch (RuntimeException | Error exception) {
            close();
            throw exception;
        } finally {
            minecraft.level = previousLevel;
            ((MinecraftRendererAccessor) minecraft).glass$levelRenderer(previousRenderer);
            minecraft.smartCull = previousCull;
            ((LevelRendererAccessor) renderer).setRenderBuffers(previousBuffers);
            modelView.set(previousModelView);
            RenderSystem.setProjectionMatrix(previousProjection, previousProjectionType);
            ProgramUniforms.clearActiveUniforms();
            ProgramSamplers.clearActiveSamplers();
            DepthColorStorage.unlockDepthColor();
            BlendModeStorage.restoreBlend();
            IrisRenderSystem.unbindAllSamplers();
            GlStateManager._glUseProgram(0);
            GlStateManager._glBindFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GlStateManager._glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER, readFramebuffer);
            GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            this.renderer = null;
            this.camera = null;
        }
    }

    @Override
    public void beginClear() {
        var fog = RenderSystem.getShaderFog();
        try {
            pipeline.onBeginClear();
        } finally {
            RenderSystem.setShaderFog(fog);
        }
    }

    @Override
    public void close() {
        var previous = pipeline;
        pipeline = null;
        pack = null;
        dimension = null;
        settings = null;
        ready = false;
        if (previous != null) {
            previous.destroy();
        }
    }

    private static List<Field> fields(Class<?> type, boolean statics) {
        List<Field> fields = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) == statics && !Modifier.isFinal(field.getModifiers())) {
                field.setAccessible(true);
                fields.add(field);
            }
        }
        return List.copyOf(fields);
    }

    private static IrisCompatibility.Scope snapshot(List<Field> fields, Object instance) {
        List<Object> values = new ArrayList<>();
        try {
            for (Field field : fields) {
                values.add(field.get(instance));
            }
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to capture Iris render state", exception);
        }
        return () -> {
            try {
                for (int index = 0; index < fields.size(); index++) {
                    fields.get(index).set(instance, values.get(index));
                }
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Unable to restore Iris render state", exception);
            }
        };
    }
}
