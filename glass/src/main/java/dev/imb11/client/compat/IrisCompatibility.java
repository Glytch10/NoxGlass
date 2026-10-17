package dev.imb11.client.compat;

import dev.imb11.platform.Platform;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.client.renderer.LevelRenderer;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

public final class IrisCompatibility {
    private static final boolean LOADED = Platform.isModLoaded("iris");

    private IrisCompatibility() {
    }

    public static boolean isShaderPackActive() {
        return LOADED && IrisAccess.shaderPackActive();
    }

    public static void renderWithPipeline(LevelRenderer renderer, Runnable render) {
        if (LOADED) {
            IrisAccess.renderWithPipeline(renderer, render);
        } else {
            render.run();
        }
    }

    private static final class IrisAccess {
        private static final List<Field> PIPELINE_FIELDS = findPipelineFields();

        private static boolean shaderPackActive() {
            return IrisApi.getInstance().isShaderPackInUse();
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
            WorldRenderingPipeline pipeline = Iris.getPipelineManager().preparePipeline(Iris.getCurrentDimension());
            try {
                for (Field field : PIPELINE_FIELDS) {
                    bind(renderer, field, pipeline);
                }
                render.run();
            } finally {
                for (int index = 0; index < PIPELINE_FIELDS.size(); index++) {
                    bind(renderer, PIPELINE_FIELDS.get(index), previous.get(index));
                }
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
