package dev.descrycraft.descrycraft.client;

import com.google.common.collect.ImmutableMap;
import com.mojang.math.Axis;
import dev.descrycraft.descrycraft.entity.ReconDroneEntity;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.model.renderable.CompositeRenderable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Builds the per-frame part transforms for the OBJ drone.
 *
 * <p>{@link CompositeRenderable} carries a lightweight skeleton: each named component can be
 * handed a matrix, and children multiply inside their parent. Our OBJ has every group at the top
 * level, so a flat map keyed by group name is all the rig needs.
 *
 * <p>Pivots are copied from the manifest {@code tools/prepare_drone_model.py} emits, and are
 * expressed in the same space as the mesh: one unit per block, Y up, nose towards -Z, origin at
 * the airframe centre. They are the centre of each part's own bounding box, which on this model
 * coincides with the mechanical axis to four decimals - so they are the real axis, not an
 * eyeballed number.
 */
public final class DroneRig {

    /** Component names, in the order the source model lists them. */
    private static final String[] ROTORS = {"Rotor_FL", "Rotor_FR", "Rotor_RL", "Rotor_RR"};

    /**
     * Rotor hubs. The front pair sits about 0.106 blocks lower than the rear pair - the airframe
     * is a staggered quad, not a flat one - so the Y values are deliberately not all equal.
     */
    static final float[][] ROTOR_PIVOTS = {
            {-0.3280F, 0.0097F, -0.3140F},
            {0.3280F, 0.0097F, -0.3140F},
            {-0.3270F, 0.1157F, 0.3140F},
            {0.3270F, 0.1157F, 0.3140F},
    };

    /**
     * Spin direction per rotor. A quadcopter counter-rotates its <em>diagonal</em> pairs - FL with
     * RR, FR with RL - to cancel the reaction torque. Alternating by index instead would pair
     * FL with FR and leave the airframe wanting to yaw.
     */
    private static final float[] ROTOR_SPIN = {1.0F, -1.0F, -1.0F, 1.0F};

    /** Degrees per tick. */
    private static final float SPIN_RATE = 42.0F;

    /** The camera window, which lives in the glow mesh under its own group. */
    private static final String GIMBAL = "Gimbal_Lens";

    /** Centre of the window's bounding box, in model space. */
    private static final float[] GIMBAL_PIVOT = {0.0001F, 0.0482F, -0.3041F};

    /**
     * How far the window can swing, in degrees.
     *
     * <p>Twelve is not a taste call. The window is a solid block 0.05 deep and 0.034 tall, sunk
     * 0.008 behind the front of the bezel that frames it, so rotating it about its own centre
     * pushes a corner out by {@code 0.025 sin a + 0.017 cos a}. Past roughly twelve degrees that
     * corner crosses the front of the frame and the cyan visibly escapes the red surround.
     * Renders at -20 through +20 confirmed it: ten degrees reads as a lens moving in its housing,
     * twenty reads as a broken part.
     */
    private static final float GIMBAL_LIMIT = 12.0F;

    private DroneRig() {}

    public static CompositeRenderable.Transforms of(ReconDroneEntity entity, float partialTick) {
        ImmutableMap.Builder<String, Matrix4f> parts = ImmutableMap.builder();

        float spin = (entity.tickCount + partialTick) * SPIN_RATE;
        for (int i = 0; i < ROTORS.length; i++) {
            parts.put(ROTORS[i],
                    about(ROTOR_PIVOTS[i], Axis.YP.rotationDegrees(ROTOR_SPIN[i] * spin)));
        }

        // The sensor is stabilised, the way a real gimbal is: it cancels the airframe's pitch
        // outright rather than lagging behind it, so the window keeps pointing at the horizon
        // while the hull tips around it. Past the travel limit it simply rides the stop, which is
        // what a mechanical gimbal does when it runs out of room.
        //
        // The airframe is drawn with -pitch about X (see DroneRenderer), so cancelling it is the
        // same rotation with the sign flipped. Both rotations are about X, so they add directly.
        float pitch = Mth.lerp(partialTick, entity.xRotO, entity.getXRot());
        float tilt = Mth.clamp(pitch, -GIMBAL_LIMIT, GIMBAL_LIMIT);
        parts.put(GIMBAL, about(GIMBAL_PIVOT, Axis.XP.rotationDegrees(tilt)));

        return CompositeRenderable.Transforms.of(parts.build());
    }

    /** A rotation about `pivot`, expressed in model space. */
    private static Matrix4f about(float[] pivot, Quaternionf rotation) {
        return new Matrix4f()
                .translate(pivot[0], pivot[1], pivot[2])
                .rotate(rotation)
                .translate(-pivot[0], -pivot[1], -pivot[2]);
    }
}
