package dev.imb11.client.renderer.projection;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.util.ARGB;
import net.minecraft.world.attribute.EnvironmentAttributes;

public final class ProjectionLightmap extends Lightmap {
    private long lastUpdateFrame = -1L;

    public void update(ProjectionCamera camera, float partialTick, long frame) {
        if (frame == lastUpdateFrame) {
            return;
        }
        lastUpdateFrame = frame;
        LightmapRenderState state = new LightmapRenderState();
        state.needsUpdate = true;
        state.blockFactor = 1.4F;
        state.blockLightTint = ARGB.vector3fFromRGB24(camera.attributeProbe().getValue(EnvironmentAttributes.BLOCK_LIGHT_TINT, partialTick));
        state.skyFactor = camera.attributeProbe().getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, partialTick);
        state.skyLightColor = ARGB.vector3fFromRGB24(camera.attributeProbe().getValue(EnvironmentAttributes.SKY_LIGHT_COLOR, partialTick));
        state.ambientColor = ARGB.vector3fFromRGB24(camera.attributeProbe().getValue(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, partialTick));
        state.nightVisionColor = ARGB.vector3fFromRGB24(camera.attributeProbe().getValue(EnvironmentAttributes.NIGHT_VISION_COLOR, partialTick));
        state.brightness = Minecraft.getInstance().options.gamma().get().floatValue();
        render(state);
    }
}
