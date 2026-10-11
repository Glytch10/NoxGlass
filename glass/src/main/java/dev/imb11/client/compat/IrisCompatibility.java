package dev.imb11.client.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.imb11.platform.Platform;
import dev.imb11.client.renderer.projection.ProjectionCamera;
import dev.imb11.client.renderer.projection.ProjectionLevelRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL33C;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class IrisCompatibility {
    private static final boolean LOADED = Platform.isModLoaded("iris");
    private static final ThreadLocal<Boolean> VANILLA_VERTICES = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Object> WORLD_SETTINGS = new ThreadLocal<>();
    private static final ThreadLocal<ShaderView> SHADER_VIEW = new ThreadLocal<>();

    private IrisCompatibility() {
    }

    public static ShaderView createShaderView() {
        return LOADED ? new IrisProjectionRenderer() : null;
    }

    public static boolean isShaderPackInUse() {
        return LOADED && IrisAccess.isShaderPackInUse();
    }

    public static ShaderTerrain createShaderTerrain(ProjectionLevelRenderer renderer, ClientLevel level, int distance, Object pack) {
        return new IrisProjectionTerrain(renderer, level, distance, pack);
    }

    public interface ShaderTerrain extends AutoCloseable {
        Object shaderPack();
        boolean isReady();
        void prepare(Camera camera, Matrix4fc projection);
        ChunkSectionsToRender prepareChunkRenders(Matrix4fc modelView);
        void extractBlockEntities(float partialTick, LevelRenderState state);
        boolean isSectionReady(BlockPos pos);
        void dirty(int x, int y, int z);
        @Override
        void close();
    }

    public static ShaderView shaderView() {
        return SHADER_VIEW.get();
    }

    public static Object worldSettings() {
        return WORLD_SETTINGS.get();
    }

    public static Scope useWorldSettings(Object settings) {
        Object previous = WORLD_SETTINGS.get();
        WORLD_SETTINGS.set(settings);
        return () -> WORLD_SETTINGS.set(previous);
    }

    public static Scope useShaderView(ShaderView view) {
        ShaderView previous = SHADER_VIEW.get();
        SHADER_VIEW.set(view);
        return () -> SHADER_VIEW.set(previous);
    }

    public interface ShaderView extends AutoCloseable {
        Object pipeline();
        Camera camera();
        GameRenderState renderState();
        boolean isReady();
        void render(ProjectionLevelRenderer renderer, ProjectionCamera camera, Matrix4f projection,
                    DeltaTracker delta, Runnable render);
        void beginClear();
        @Override
        void close();
    }

    public static void beginClear() {
        ShaderView view = shaderView();
        if (view != null) {
            view.beginClear();
        }
    }

    public static void renderWithPipeline(LevelRenderer renderer, Runnable render) {
        if (LOADED) {
            IrisAccess.renderWithPipeline(renderer, render);
        } else {
            render.run();
        }
    }

    public static <T> T withVanillaVertices(Supplier<T> operation) {
        if (!LOADED) {
            return operation.get();
        }
        boolean previous = VANILLA_VERTICES.get();
        VANILLA_VERTICES.set(true);
        try {
            return IrisAccess.withVanillaVertices(operation);
        } finally {
            VANILLA_VERTICES.set(previous);
        }
    }

    public static boolean isUsingVanillaVertices() {
        return VANILLA_VERTICES.get();
    }

    public static Scope preserveSamplers(int count) {
        if (!LOADED || !RenderSystem.getDevice().getDeviceInfo().backendName().equals("OpenGL")) {
            return () -> {};
        }
        int[] samplers = new int[count];
        for (int unit = 0; unit < count; unit++) {
            samplers[unit] = GL33C.glGetIntegeri(GL33C.GL_SAMPLER_BINDING, unit);
        }
        return () -> {
            for (int unit = 0; unit < count; unit++) {
                GL33C.glBindSampler(unit, samplers[unit]);
                IrisAccess.restoreSampler(unit, samplers[unit]);
            }
        };
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public static void preservingCapturedState(Runnable render) {
        if (LOADED) {
            IrisAccess.preservingCapturedState(render);
        } else {
            render.run();
        }
    }

    private static final class IrisAccess {
        private static final List<Field> PIPELINE_FIELDS = findPipelineFields();

        private static boolean isShaderPackInUse() {
            return net.irisshaders.iris.Iris.getCurrentPack().isPresent();
        }

        private static void restoreSampler(int unit, int sampler) {
            net.irisshaders.iris.gl.IrisRenderSystem.bindSamplerToUnit(unit, sampler);
        }

        private static void preservingCapturedState(Runnable render) {
            CapturedRenderingState state = CapturedRenderingState.INSTANCE;
            Vector3d fogColor = new Vector3d(state.getFogColor());
            float fogDensity = state.getFogDensity();
            int entity = state.getCurrentRenderedEntity();
            int blockEntity = state.getCurrentRenderedBlockEntity();
            int item = state.getCurrentRenderedItem();
            float alphaTest = state.getCurrentAlphaTest();
            boolean tessellation = ImmediateState.usingTessellation;
            boolean extendedVertices = ImmediateState.renderWithExtendedVertexFormat;
            boolean renderingBlockEntities = ImmediateState.isRenderingBEs;
            try (Scope ignored = preserveSamplers(6)) {
                ImmediateState.usingTessellation = false;
                ImmediateState.renderWithExtendedVertexFormat = false;
                ImmediateState.isRenderingBEs = false;
                render.run();
            } finally {
                state.setFogColor((float) fogColor.x, (float) fogColor.y, (float) fogColor.z);
                state.setFogDensity(fogDensity);
                state.setCurrentEntity(entity);
                state.setCurrentBlockEntity(blockEntity);
                state.setCurrentRenderedItem(item);
                state.setCurrentAlphaTest(alphaTest);
                ImmediateState.usingTessellation = tessellation;
                ImmediateState.renderWithExtendedVertexFormat = extendedVertices;
                ImmediateState.isRenderingBEs = renderingBlockEntities;
            }
        }

        private static void renderWithPipeline(LevelRenderer renderer, Runnable render) {
            List<WorldRenderingPipeline> previous = new ArrayList<>(PIPELINE_FIELDS.size());
            for (Field field : PIPELINE_FIELDS) {
                try {
                    previous.add((WorldRenderingPipeline) field.get(renderer));
                } catch (IllegalAccessException exception) {
                    throw new IllegalStateException("Unable to read Iris pipeline on " + field, exception);
                }
            }
            try {
                for (Field field : PIPELINE_FIELDS) {
                    ShaderView view = shaderView();
                    bind(renderer, field, view == null ? ProjectionRenderingPipeline.INSTANCE : (WorldRenderingPipeline) view.pipeline());
                }
                render.run();
            } finally {
                for (int index = 0; index < PIPELINE_FIELDS.size(); index++) {
                    bind(renderer, PIPELINE_FIELDS.get(index), previous.get(index));
                }
            }
        }

        private static <T> T withVanillaVertices(Supplier<T> operation) {
            Boolean previous = ImmediateState.skipExtension.get();
            ImmediateState.skipExtension.set(true);
            try {
                return operation.get();
            } finally {
                ImmediateState.skipExtension.set(previous);
            }
        }

        private static List<Field> findPipelineFields() {
            List<Field> fields = new ArrayList<>();
            for (Field field : LevelRenderer.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && field.getType() == WorldRenderingPipeline.class) {
                    field.setAccessible(true);
                    fields.add(field);
                }
            }
            return List.copyOf(fields);
        }

        private static void bind(LevelRenderer renderer, Field field, WorldRenderingPipeline pipeline) {
            try {
                field.set(renderer, pipeline);
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Unable to bind Iris pipeline on " + field, exception);
            }
        }
    }
}
