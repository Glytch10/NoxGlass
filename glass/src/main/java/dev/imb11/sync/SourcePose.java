package dev.imb11.sync;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;

import java.util.Objects;

public record SourcePose(Vec3 position, Quaternionf orientation) {
    private static final double POSITION_EPSILON_SQUARED = 1.0E-6D;
    private static final float ROTATION_EPSILON = 1.0E-5F;

    public SourcePose {
        position = Objects.requireNonNull(position);
        orientation = new Quaternionf(orientation).normalize();
    }

    @Override
    public Quaternionf orientation() {
        return new Quaternionf(orientation);
    }

    public Vec3 rotate(Vec3 direction) {
        Vector3d rotated = new Vector3d(direction.x, direction.y, direction.z).rotate(new Quaterniond(orientation));
        return new Vec3(rotated.x, rotated.y, rotated.z);
    }

    public SourcePose lerp(SourcePose target, float delta) {
        return new SourcePose(
                position.lerp(target.position, delta),
                new Quaternionf(orientation).slerp(target.orientation, delta)
        );
    }

    public boolean closeTo(@Nullable SourcePose other) {
        if (other == null || position.distanceToSqr(other.position) > POSITION_EPSILON_SQUARED) {
            return false;
        }
        return Math.abs(Math.abs(orientation.dot(other.orientation)) - 1.0F) <= ROTATION_EPSILON;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeDouble(position.x);
        buf.writeDouble(position.y);
        buf.writeDouble(position.z);
        buf.writeFloat(orientation.x);
        buf.writeFloat(orientation.y);
        buf.writeFloat(orientation.z);
        buf.writeFloat(orientation.w);
    }

    public static SourcePose read(FriendlyByteBuf buf) {
        Vec3 position = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        Quaternionf orientation = new Quaternionf(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
        if (!valid(position, orientation)) {
            throw new IllegalArgumentException("Invalid source pose");
        }
        return new SourcePose(position, orientation);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("x", position.x);
        tag.putDouble("y", position.y);
        tag.putDouble("z", position.z);
        tag.putFloat("qx", orientation.x);
        tag.putFloat("qy", orientation.y);
        tag.putFloat("qz", orientation.z);
        tag.putFloat("qw", orientation.w);
        return tag;
    }

    @Nullable
    public static SourcePose load(CompoundTag tag) {
        Vec3 position = new Vec3(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"));
        Quaternionf orientation = new Quaternionf(tag.getFloat("qx"), tag.getFloat("qy"), tag.getFloat("qz"), tag.getFloat("qw"));
        return valid(position, orientation) ? new SourcePose(position, orientation) : null;
    }

    private static boolean valid(Vec3 position, Quaternionf orientation) {
        return Double.isFinite(position.x) && Double.isFinite(position.y) && Double.isFinite(position.z)
                && orientation.isFinite() && orientation.lengthSquared() >= 1.0E-6F;
    }
}
