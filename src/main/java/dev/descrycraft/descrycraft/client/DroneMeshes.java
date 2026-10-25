package dev.descrycraft.descrycraft.client;

import com.google.common.collect.ImmutableMap;
import com.mojang.logging.LogUtils;
import dev.descrycraft.descrycraft.Descrycraft;
import java.util.HashSet;
import java.util.Set;
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

    private CompositeRenderable body;
    private CompositeRenderable glow;
    private boolean failed;

    private DroneMeshes() {}

    /**
     * Drops the baked meshes so the next frame re-reads the OBJ and MTL files.
     *
     * <p>{@code failed} is cleared too: a reload is exactly the moment at which a mesh that failed
     * to parse might have been fixed.
     */
    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        body = null;
        glow = null;
        failed = false;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, path);
    }

    public static CompositeRenderable body() {
        ensureLoaded();
        return INSTANCE.body;
    }

    public static CompositeRenderable glow() {
        ensureLoaded();
        return INSTANCE.glow;
    }

    private static void ensureLoaded() {
        if ((INSTANCE.body != null && INSTANCE.glow != null) || INSTANCE.failed) {
            return;
        }
        try {
            ObjModel bodyModel = loadModel("drone.obj");
            ObjModel glowModel = loadModel("drone_glow.obj");
            INSTANCE.body = bake(bodyModel, BODY_TEXTURE);
            INSTANCE.glow = bake(glowModel, GLOW_TEXTURE);
            reportMissingParts(bodyModel, glowModel);
        } catch (Exception error) {
            // One failed load should cost us the drone, not the frame.
            INSTANCE.failed = true;
            LOGGER.error("Could not load the Recon Drone meshes; the drone will not render", error);
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
