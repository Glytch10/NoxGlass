package dev.imb11.client.renderer.projection;

import net.minecraft.client.Camera;
import net.minecraft.world.level.ChunkPos;
import dev.imb11.projection.ProjectionChunkRegion;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class ProjectionCamera extends Camera {
    private static final float NEAR_DISTANCE = 0.05F;
    private final Matrix4f projection = new Matrix4f();
    private ChunkPos gridCenter;

    public ChunkPos retainGridCenter(Vec3 position) {
        if (gridCenter == null || Math.abs(position.x - gridCenter.getMiddleBlockX()) > 16.0D
                || Math.abs(position.z - gridCenter.getMiddleBlockZ()) > 16.0D) {
            gridCenter = ProjectionChunkRegion.cameraCenter(position);
        }
        return gridCenter;
    }

    public ChunkPos gridCenter() {
        return gridCenter == null ? ProjectionChunkRegion.cameraCenter(position()) : gridCenter;
    }

    public void setPose(Vec3 position, Quaternionf pose) {
        Quaternionf normalizedPose = new Quaternionf(pose).normalize();
        Vector3f look = new Vector3f(0.0F, 0.0F, -1.0F).rotate(normalizedPose);
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) Math.toDegrees(Math.asin(-Math.max(-1.0F, Math.min(1.0F, look.y))));
        setRotation(yaw, pitch);
        rotation().set(normalizedPose);
        setPosition(position.x, position.y, position.z);
    }

    public void setProjection(Matrix4f projection) {
        this.projection.set(projection);
    }

    @Override
    public boolean isInitialized() {
        return true;
    }

    @Override
    public boolean isDetached() {
        return true;
    }

    @Override
    public net.minecraft.client.renderer.culling.Frustum getCullFrustum() {
        var frustum = new net.minecraft.client.renderer.culling.Frustum(getViewRotationMatrix(new Matrix4f()), projection);
        Vec3 position = position();
        frustum.prepare(position.x, position.y, position.z);
        return frustum;
    }

    @Override
    public Matrix4f getViewRotationMatrix(Matrix4f dest) {
        return dest.rotation(rotation().conjugate(new Quaternionf()));
    }

    @Override
    public Matrix4f getViewRotationProjectionMatrix(Matrix4f dest) {
        return dest.set(projection).mul(getViewRotationMatrix(new Matrix4f()));
    }
}
