package dev.descrycraft.descrycraft.client;

import com.google.common.collect.ImmutableMap;
import com.mojang.logging.LogUtils;
import dev.descrycraft.descrycraft.Descrycraft;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.Util;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.Material;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.StandaloneGeometryBakingContext;
import net.neoforged.neoforge.client.model.obj.ObjLoader;
import net.neoforged.neoforge.client.model.obj.ObjModel;
import net.neoforged.neoforge.client.model.renderable.CompositeRenderable;
import net.neoforged.neoforge.client.model.renderable.ITextureRenderTypeLookup;
import org.slf4j.Logger;

/**
 * Loads the two OBJ meshes that make up the drone and bakes them once.
 *
 * <p>Both meshes are authored offline: {@code drone.obj} carries the airframe and
 * {@code drone_glow.obj} carries the emissive parts, which live in a separate file so they can be
 * drawn with a different render type and forced to full brightness. They are produced either by
 * {@code tools/prepare_drone_model.py} (from the Blender hard-surface model) or by
 * {@code tools/generate_drone_mesh.py} (the procedural fallback). Both write the same layout.
 *
 * <p>The loader settings are worth spelling out, because two of the four defaults are wrong for
 * an entity:
 * <ul>
 *   <li>{@code automatic_culling} is off. It only ever fires for quads sitting exactly on the
 *       x = 0 or x = 1 plane, which is a block-model assumption; leaving it on would silently
 *       assign cull directions to a mesh that does not want them.</li>
 *   <li>{@code flip_v} stays off, because the generator already writes {@code vt} with the origin
 *       at the top left, matching Minecraft rather than the OBJ spec.</li>
 *   <li>{@code emissive_ambient} is off. With it on, the baked lightmap comes from the material's
 *       {@code Ka}, and our materials declare {@code Ka 0 0 0} - which bakes every quad at light
 *       level zero and renders the whole drone black.</li>
 * </ul>
 *
 * <p>The two meshes are drawn with deliberately different render types, and the difference is the
 * whole reason they are separate files:
 * <ul>
 *   <li>The airframe uses {@link RenderType#entityCutout}, an ordinary lit type. It multiplies in
 *       the lightmap we pass, so the hull brightens and darkens with the world.</li>
 *   <li>The camera window, the motor rings and the nose lamp use {@link RenderType#eyes} -
 *       vanilla's additive emissive type, the same one behind a spider's and an enderman's eyes.
 *       It samples <em>no</em> lightmap at all and blends with {@code ONE, ONE}.</li>
 * </ul>
 *
 * <p>Passing {@code LightTexture.FULL_BRIGHT} to a lit type is <em>not</em> enough to make a part
 * look self-lit, and that mistake is what this pair exists to avoid. {@code FULL_BRIGHT} means
 * "as bright as a fully lit block", which is a ceiling rather than a bypass: the lightmap texel it
 * selects is pure white, so the part renders at exactly its texture colour - which is also what a
 * fully lit block renders at. In daylight the hull next to it is at that same ceiling, so a
 * "glowing" lens is pixel-for-pixel indistinguishable from a brightly lit one. The only way to
 * exceed the lighting ceiling is additive blending, hence {@code eyes}.
 *
 * <p>{@link CompositeRenderable} passes the lightmap and overlay we hand it straight into
 * {@code putBulkData}, so the airframe's hurt flash still works. The emissive shader has no
 * overlay input, so the glow parts do not take the red flash - they stay lit while the hull
 * flashes, which reads correctly anyway.
 */
public final class DroneMeshes implements ResourceManagerReloadListener {

    public static final DroneMeshes INSTANCE = new DroneMeshes();

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String NAMESPACE = Descrycraft.MOD_ID;

    /** Paths are relative to {@code assets/descrycraft/}, with no {@code textures/} prefix. */
    private static final ResourceLocation BODY_TEXTURE = id("entity/recon_drone");
    private static final ResourceLocation GLOW_TEXTURE = id("entity/recon_drone_glow");
    private static final ResourceLocation RED_GLOW_TEXTURE = id("entity/recon_drone_glow_red");

    /**
     * The OBJ groups {@link DroneRig} animates. A missing group is not fatal - the transform is
     * simply not applied - so this exists purely to turn a silently dead rotor into a log line.
     */
    static final String[] ANIMATED = {
            "Rotor_FL", "Rotor_FR", "Rotor_RL", "Rotor_RR", "Gimbal_Lens",
    };

    /** Lit type: brightens and darkens with the world. */
    public static final ITextureRenderTypeLookup BODY_LOOKUP = RenderType::entityCutout;

    /**
     * Additive emissive type: {@code ONE, ONE} blending and no lightmap, so the camera window, the
     * motor rings and the nose lamp add their colour on top of whatever is behind them and stay
     * lit at noon and at midnight alike. Culling is left on, so each pixel takes exactly one
     * additive contribution - which is also why an emissive face that points into the hull, like
     * the motor rings, is never drawn at all.
     */
    public static final ITextureRenderTypeLookup GLOW_LOOKUP = RenderType::eyes;

    /**
     * The same additive type against the alarm-red sheet. The lookup ignores the texture baked
     * into the mesh and substitutes {@link #RED_GLOW_TEXTURE}, so a charge or detonation can
     * recolour the emissive pass without a second bake or a second copy of the geometry.
     */
    public static final ITextureRenderTypeLookup GLOW_RED_LOOKUP =
            texture -> RenderType.eyes(RED_GLOW_TEXTURE);

    /** A fully baked pair: the lit airframe and the emissive parts. */
    public record Meshes(CompositeRenderable body, CompositeRenderable glow) {
    }

    /** Guards the bookkeeping below; the meshes themselves are published through {@link #meshes}. */
    private final Object lock = new Object();
    /** Bumped on every reload, so a load that finishes after one is thrown away. */
    private int generation;
    /** Whether a load is in flight. */
    private boolean loading;
    /** Published as one value, so the renderer never sees the body without the glow. */
    private volatile Meshes meshes;
    /** Latched when a load throws, so a broken mesh does not retry (and log) every frame. */
    private volatile boolean failed;

    private DroneMeshes() {}

    /**
     * Drops the baked meshes and starts loading them again on a background thread.
     *
     * <p>{@code failed} is cleared too: a reload is exactly the moment at which a mesh that failed
     * to parse might have been fixed.
     */
    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        synchronized (lock) {
            generation++;
            meshes = null;
            failed = false;
            loading = false;
        }
        // Start at once: a reload happens while the world is still loading, so the parse and the
        // bake are normally finished before the drone can first come into view.
        ensureLoaded();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, path);
    }

    /**
     * {@return the baked meshes, or {@code null} while the background load has not finished}
     *
     * <p>Also kicks the load off if it has not started yet - the reload listener normally does
     * that, but a failed start is retried here rather than left for a reload that may never come.
     */
    public static Meshes meshes() {
        INSTANCE.ensureLoaded();
        return INSTANCE.meshes;
    }

    /** {@return whether a full bake is available for callers that cannot draw a partial one} */
    public static boolean isReady() {
        return meshes() != null;
    }

    private void ensureLoaded() {
        int startedFor;
        synchronized (lock) {
            if (loading || failed || meshes != null) {
                return;
            }
            loading = true;
            startedFor = generation;
        }
        Util.backgroundExecutor().execute(() -> load(startedFor));
    }

    /**
     * Parses and bakes both meshes off the render thread.
     *
     * <p>It is roughly half a second of work - the airframe alone is 1.1 MB of OBJ and 6,675
     * quads - plus the class loading that comes with the OBJ loader. On the render thread that
     * landed as a one-second freeze on the first frame the drone was visible, which read as "the
     * inventory takes a second to open" whenever that frame happened to be the one behind a menu.
     * Baking it here moves the cost to a worker, and the drone simply stays out of frame for the
     * handful of frames it takes.
     */
    private void load(int startedFor) {
        Meshes baked = null;
        try {
            ObjModel bodyModel = loadModel("drone.obj");
            ObjModel glowModel = loadModel("drone_glow.obj");
            baked = new Meshes(bake(bodyModel, BODY_TEXTURE), bake(glowModel, GLOW_TEXTURE));
            reportMissingParts(bodyModel, glowModel);
        } catch (Exception error) {
            // One failed load should cost us the drone, not the frame.
            LOGGER.error("Could not load the Recon Drone meshes; the drone will not render", error);
        }
        synchronized (lock) {
            if (startedFor != generation) {
                // A reload ran while this load was in flight and already owns the state.
                return;
            }
            loading = false;
            if (baked == null) {
                failed = true;
            } else {
                meshes = baked;
            }
        }
    }

    private static ObjModel loadModel(String file) {
        return ObjLoader.INSTANCE.loadModel(new ObjModel.ModelSettings(
                id("models/entity/" + file),
                false,   // automatic_culling
                true,    // shade_quads
                false,   // flip_v
                false,   // emissive_ambient
                null));  // mtl_override - resolved from the mtllib line in the OBJ
    }

    /**
     * Bakes the model against a throwaway baking context.
     *
     * <p>The material key {@code "#texture0"} is what the procedural generator's MTL declares
     * ({@code map_Kd #texture0}); the OBJ loader hands anything starting with {@code #} to
     * {@code context.getMaterial} untouched, so that key has to be present with the hash for the
     * fallback mesh to bake. The hard-surface MTL instead names a real texture
     * ({@code map_Kd descrycraft:entity/recon_drone}), which {@code resolveDirtyMaterial} parses
     * into a {@link ResourceLocation} directly and never consults this map for - so the map entry
     * is unused there, and harmless.
     *
     * <p>The atlas named on the material is never read either. {@code ObjModel} bakes every quad
     * against {@code UnitTextureAtlasSprite} - a 1x1 sprite whose {@code getU}/{@code getV} are the
     * identity - so the only part of the material that survives into the mesh is its texture
     * location, which becomes the {@link RenderType} we hand in at draw time.
     */
    private static CompositeRenderable bake(ObjModel model, ResourceLocation texture) {
        Material material = new Material(InventoryMenu.BLOCK_ATLAS, texture);
        IGeometryBakingContext context = StandaloneGeometryBakingContext.builder()
                .withMaterials(ImmutableMap.of("#texture0", material), material)
                .build(texture);
        return model.bakeRenderable(context);
    }

    /**
     * Both models are searched, not just the airframe: a mesh may carry part of the animated
     * geometry in its own file, so checking only the body could report a present group as missing.
     */
    private static void reportMissingParts(ObjModel body, ObjModel glow) {
        Set<String> names = new HashSet<>(body.getConfigurableComponentNames());
        names.addAll(glow.getConfigurableComponentNames());
        for (String part : ANIMATED) {
            if (!names.contains(part)) {
                LOGGER.error("Recon Drone mesh has no '{}' group, so that part will not animate. "
                        + "Groups present: {}", part, names);
            }
        }
    }
}
