//? if fabric {
package dev.imb11.platform;

import net.fabricmc.fabric.impl.client.rendering.level.LevelRenderContextImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.LevelRenderState;

import java.lang.reflect.Field;

public final class LevelRenderContexts {
    private static final Field CONTEXT = findContextField();

    private LevelRenderContexts() {
    }

    public static void prepare(LevelRenderer renderer, LevelRenderState state, SubmitNodeCollector collector) {
        LevelRenderContextImpl context = context(renderer);
        if (context != null) {
            context.prepare(Minecraft.getInstance().gameRenderer, renderer, state, collector);
        }
    }

    public static void setSectionsToRender(LevelRenderer renderer, ChunkSectionsToRender sections) {
        LevelRenderContextImpl context = context(renderer);
        if (context != null) {
            context.setSectionsToRender(sections);
        }
    }

    private static LevelRenderContextImpl context(LevelRenderer renderer) {
        if (CONTEXT == null) {
            return null;
        }
        try {
            return (LevelRenderContextImpl) CONTEXT.get(renderer);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static Field findContextField() {
        for (Field field : LevelRenderer.class.getDeclaredFields()) {
            if (field.getType() == LevelRenderContextImpl.class) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }
}
//?} else {
/*package dev.imb11.platform;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.LevelRenderState;

public final class LevelRenderContexts {
    private LevelRenderContexts() {
    }

    public static void prepare(LevelRenderer renderer, LevelRenderState state, SubmitNodeCollector collector) {
    }

    public static void setSectionsToRender(LevelRenderer renderer, ChunkSectionsToRender sections) {
    }
}
*///?}
