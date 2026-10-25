package dev.descrycraft.descrycraft.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import dev.descrycraft.descrycraft.entity.ReconDroneEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

/**
 * Signal degradation, the world-space half of it.
 *
 * <p>Two things happen as the drone flies away from its operator: the picture loses definition,
 * and the visor fills up with grain. This class owns the first one - a real defocus of the
 * rendered world - and publishes {@link #amount(ReconDroneEntity, double)} so the overlay in
 * {@link DroneHud} can draw the second at exactly the same strength.
 *
 * <p><b>Why the world and not the GUI.</b> {@code GuiGraphics} only fills flat colour. A
 * screen-space imitation can lift the blacks and darken the corners, but it can never soften an
 * edge, and an overlay that only lifts contrast reads as dust sitting on a sharp picture - which
 * is precisely what this used to look like. The only place a genuine blur can come from is the
 * frame buffer, so the world gets blurred for real and the overlay is left to do the parts that
 * really do belong to it: grain, torn scan lines and the vignette.
 *
 * <p><b>Why there is no shader code here.</b> Every pass of the chain below names one of vanilla's
 * own programs, so the whole effect is a JSON file plus a uniform write, and nothing has to be
 * compiled or registered from Java.
 *
 * <h2>Why this runs at {@code AFTER_LEVEL} instead of through {@code GameRenderer#postEffect}</h2>
 *
 * <p>Handing the chain to {@code GameRenderer} looks like the obvious route - the method is
 * public and the engine processes it for you - but it puts the blur in the wrong place in the
 * frame. The order inside {@code GameRenderer#render} is:
 *
 * <pre>
 *   renderLevel(...)              // world, entities, clouds, weather
 *   doEntityOutline()             // &lt;-- the glowing entities are composited HERE
 *   postEffect.process(...)       // &lt;-- so a post effect blurs the glow along with the world
 *   ... GUI
 * </pre>
 *
 * <p>Blurring the glow is not a cosmetic detail, it destroys the feature. A glowing entity is not
 * drawn into the main target at all - it is rendered only into the outline buffer, and what you
 * actually see is the thin white edge {@code entity_sobel.fsh} derives from it
 * ({@code outColor * 0.2}, composited with {@code SRC_ALPHA}). Spreading that edge over a
 * twenty-pixel radius drops its peak brightness by an order of magnitude, and the mark collapses
 * into a dark smear. The glow is the drone's whole reason for existing, so it has to survive.
 *
 * <p>{@code RenderLevelStageEvent.Stage.AFTER_LEVEL} fires as the last statement of
 * {@code GameRenderer#renderLevel} - after the world, before {@code doEntityOutline}. Blurring
 * there leaves the glow to be composited crisply on top of an already soft picture, which is
 * exactly the layering the visor wants: the world goes out of focus, the instruments and the
 * marks stay readable. It also means the engine never touches this chain, so there is no
 * {@code checkEntityPostEffect} reset to fight and nothing to re-assert every frame.
 *
 * <h2>Two chains, at two different points in the frame</h2>
 *
 * <p>This class owns the defocus and the detonation's radial drag: eight passes in one chain, and
 * they have to run before {@code doEntityOutline} or the glow gets smeared with the world.
 *
 * <p>The visor's grain - the signal breakup's speckle and the detonation's snow - used to be drawn
 * from {@link DroneHud} as well, one {@code GuiGraphics#fill} per grain, up to sixty thousand of
 * them on a detonation frame. That was the stutter: the cost was the CPU submitting tens of
 * thousands of 1x1 quads into the GUI vertex buffer, not the GPU painting them. It now runs as a
 * single full-screen pass in a chain of its own, driven by {@link DroneNoise} at the GUI stage -
 * it has to be there, because the grain sits on top of the visor and the instruments while the
 * defocus sits underneath them. This class only keeps that chain warm
 * ({@link DroneNoise#prepare()}) and closes it when the link goes down.
 *
 * <h2>The chain only runs when it would change the picture</h2>
 *
 * <p>The defocus used to be processed unconditionally on every frame the player was piloting, which
 * is a full-screen cost paid from the moment the link comes up whether or not a single pixel of it
 * changes anything. It is therefore skipped whenever it would be an identity, and that is not an
 * approximation: {@code box_blur.fsh} starts with
 * {@code actualRadius = round(Radius * RadiusMultiplier)} and the six passes are configured with
 * multipliers of 1.0, 0.5 and 0.25, so below a radius of half a pixel every one of them samples
 * exactly once - see {@link #BLUR_THRESHOLD}. The radial blur is likewise an identity at zero
 * strength, so the gate needs both to be at rest.
 *
 * <p>Note what is deliberately <b>not</b> here: a guard on {@code minecraft.screen}. An earlier
 * version had one, on the theory that the world behind a menu is only a backdrop - but the effect
 * is what the pilot is looking at, and having it vanish the instant ESC is pressed reads as a bug
 * rather than as a saving. {@code renderLevel} keeps running behind a screen, so the picture the
 * pilot sees there is the real one and it should keep its treatment. {@link DroneHud#render}
 * follows the same rule for the snow, and suppresses only the instruments.
 */
public final class DroneSignal {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * The signal defocus (six {@code box_blur} passes) and the detonation's radial drag (two
     * {@code radial_blur} passes), in that order.
     *
     * <p>The path is the full path inside the namespace, because that is what {@code PostChain}
     * expects: it reads the file straight off the resource manager rather than going through any
     * shader registry.
     */
    private static final ResourceLocation CHAIN =
            ResourceLocation.fromNamespaceAndPath("descrycraft", "shaders/post/drone_blur.json");

    /**
     * Widest blur radius, in pixels, at full signal loss.
     *
     * <p>The chain runs the radius through three horizontal/vertical pairs at 1.0, 0.5 and 0.25, so
     * the spread that actually lands on screen is roughly twice this. Six is enough to lose a fence
     * and a tree line without the picture turning into soup. Below {@link #BLUR_THRESHOLD} the
     * chain is skipped rather than run at a radius of zero - see the class comment for why that is
     * the same picture.
     */
    private static float MAX_RADIUS = 6.0F;

    /**
     * Smallest radius worth running the blur chain for.
     *
     * <p>Half a pixel, which is exactly where {@code box_blur}'s {@code round()} stops returning
     * zero. Anything below it is a guaranteed identity in all six passes, so skipping the chain
     * there is the same picture for free.
     */
    private static final float BLUR_THRESHOLD = 0.5F;

    /** 客户端配置载入/热重载后把模糊半径写回镜像。 */
    public static void applyConfig() {
        MAX_RADIUS = dev.descrycraft.descrycraft.config.DescrycraftConfig.CLIENT.signalMaxBlurRadius
                .get().floatValue();
    }

    /** The chain, built on the first frame it is needed and dropped when the link goes down. */
    private static PostChain effect;
    /** Latched when the chain refuses to build, so a broken resource does not retry every frame. */
    private static boolean broken;
    /** Window size the chain was last resized for. */
    private static int width = -1;
    private static int height = -1;

    private DroneSignal() {
    }

    /**
     * 没装电池时画面劣化的强度，**故意大于 1**。
     *
     * <p>1.0 是"链路拉到极限"那一档：糊、下雪，但你还认得出树和墙。没电池要的不是"更远的距离"，
     * 而是"这块屏幕已经没用了"，所以这里越界，让模糊半径和雪花浓度一起再往上走一截。
     *
     * <p>两个效果都按这个数线性放大（半径直接乘，雪花的雾底与颗粒数也乘），所以整体轻重
     * 只有这一个旋钮 —— 觉得还不够糊就调大它，不用去动别的常数。
     */
    public static final double NO_BATTERY_SEVERITY = 1.8D;

    /**
     * How far the picture has degraded: 0 at the airframe's own static onset, 1 at its own leash.
     *
     * <p>Both ends come off the drone rather than off the base constants, because signal boosters
     * push them out together - see {@link ReconDroneEntity#staticOnset()}. On a bare drone the two
     * ends are the original 48 and 64, exactly four {@code STATIC_STEP}s apart, so it still lands
     * on the grades the design calls for: clean at 48, a quarter at 52, half at 56, three quarters
     * at 60, whiteout at 64. A boosted one simply degrades over a proportionally longer run.
     *
     * <p>An airframe with no pack at all is the one case that returns more than 1 - see
     * {@link #NO_BATTERY_SEVERITY}.
     */
    public static double amount(ReconDroneEntity drone, double range) {
        // 没装电池的机体画面是全糊的 —— 直接借"最远距离"那一档再往上加，而不是另做一套效果：
        // 玩家已经知道画面糊是什么意思，缺的只是一个"为什么糊"的提示，那个提示在 DroneHud 里。
        if (!drone.hasBattery()) {
            return NO_BATTERY_SEVERITY;
        }
        double onset = drone.staticOnset();
        double span = drone.linkRange() - onset;
        if (span <= 0.0D) {
            return 0.0D;
        }
        return Mth.clamp((range - onset) / span, 0.0D, 1.0D);
    }

    /**
     * Once per level render, at the tail of {@code GameRenderer#renderLevel}.
     *
     * @param partialTicks the frame's partial tick, forwarded to the chain so its {@code Time}
     *                     uniform keeps advancing
     */
    public static void renderLevelStage(float partialTicks) {
        Minecraft minecraft = Minecraft.getInstance();
        Entity camera = minecraft.getCameraEntity();
        boolean piloting = DroneController.isLinked()
                && minecraft.level != null
                && minecraft.player != null
                && camera instanceof ReconDroneEntity;

        if (!piloting) {
            release();
            // 颗粒链子只在操控时才有用，跟着一起收掉；它的交换目标是显存，留着没有意义。
            DroneNoise.release();
            // A chain that failed to build gets a fresh attempt on the next link.
            broken = false;
            return;
        }

        // 颗粒的链子在这里预建：它的着色器不能等到第一次下雪时才编译，那时候 GUI 顶点
        // 缓冲已经攒了一半，编译一卡最显眼。构建之后这里只是两次空判断。
        DroneNoise.prepare();

        if (broken) {
            return;
        }

        ReconDroneEntity drone = (ReconDroneEntity) camera;
        float radius = radiusFor(minecraft, drone);
        // 引爆瞬间的径向模糊。两遍共用这一个全局量，各自再乘自己那条 pass 的常数
        // （第一遍 1.0、第二遍 0.5，写在链子的 json 里），拖影才是连续的一层而不是一条硬边。
        // 之所以要这么绕：PostChain#setUniform 是对链子里所有 pass 一起设的，没法只设一条。
        float blurAmount = DroneShake.radialBlur();

        // 半径低于半像素时六遍 box_blur 全是直通，径向模糊强度为 0 时两遍 radial_blur 也是 ——
        // 这一帧跑与不跑逐像素等价，所以连链子都不建。引爆期间照跑：爆炸的径向拖影就在这里。
        if (radius < BLUR_THRESHOLD && blurAmount <= 0.001F) {
            return;
        }

        if (effect == null && acquire(minecraft) == null) {
            return;
        }
        resize(minecraft);

        effect.setUniform("Radius", radius);
        effect.setUniform("BlurAmount", blurAmount);

        // Exactly the sequence GameRenderer runs around its own post effect, and the rebind at the
        // end matters: without it the window framebuffer is left bound, and doEntityOutline would
        // composite the glow onto the wrong surface.
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.resetTextureMatrix();
        effect.process(partialTicks);
        minecraft.getMainRenderTarget().bindWrite(true);
    }

    /**
     * Builds one chain, or returns {@code null} and logs why.
     *
     * <p>The {@code catch} has to be this wide and has to log. The original version caught
     * {@code IOException | JsonSyntaxException} with a comment saying {@code PostChain} had already
     * logged the reason - both halves were wrong. {@code PostChain} only throws, and the exception
     * it throws for a bad pass ({@code ChainedJsonException}) <b>extends {@code IOException}</b>,
     * so any configuration mistake was swallowed in silence, the chain was latched as broken, and
     * nothing happened on screen and nothing appeared in the log. Two effects - the signal defocus
     * and the detonation blur - were missing for days because of it. See
     * {@code tools/verify_shaders.py}.
     */
    private static PostChain acquire(Minecraft minecraft) {
        try {
            effect = new PostChain(
                    minecraft.getTextureManager(),
                    minecraft.getResourceManager(),
                    minecraft.getMainRenderTarget(),
                    CHAIN);
            width = -1;
            height = -1;
            LOGGER.info("Post chain {} built; the visor has signal degradation and detonation blur",
                    CHAIN.getPath());
            return effect;
        } catch (Exception exception) {
            LOGGER.error("Post chain {} failed to build; the drone visor will have no signal "
                    + "degradation and no detonation blur this session. Run "
                    + "`python tools/verify_shaders.py` to find the offending pass.", CHAIN, exception);
            broken = true;
            effect = null;
            return null;
        }
    }

    /**
     * Keeps the chain's own render targets in step with the window.
     *
     * <p>Nothing calls back into this chain on a resize - {@code GameRenderer#resize} only knows
     * about its own post effect and its blur - so the size is checked here instead. Two integer
     * comparisons a frame is cheaper than any hook would be.
     */
    private static void resize(Minecraft minecraft) {
        int windowWidth = minecraft.getWindow().getWidth();
        int windowHeight = minecraft.getWindow().getHeight();
        if (windowWidth != width || windowHeight != height) {
            effect.resize(windowWidth, windowHeight);
            width = windowWidth;
            height = windowHeight;
        }
    }

    private static void release() {
        if (effect != null) {
            effect.close();
            effect = null;
            width = -1;
            height = -1;
        }
    }

    private static float radiusFor(Minecraft minecraft, ReconDroneEntity drone) {
        return (float) (MAX_RADIUS * amount(drone, Math.sqrt(drone.distanceToSqr(minecraft.player))));
    }
}
