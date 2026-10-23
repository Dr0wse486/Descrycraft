package dev.watchcraft.watchcraft.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

/**
 * 颗粒的 GPU 版本：一次全屏 pass 取代每帧几万次 1×1 的 GUI 填充。
 *
 * <p>原先信号劣化和引爆雪花都在 {@code DroneHud} 里逐颗粒调用 {@code GuiGraphics#fill}。
 * 引爆时一帧最多六万颗，每颗都是一次独立的顶点提交，于是雪花屏一触发就掉帧 —— 卡的是 CPU
 * 在往 GUI 顶点缓冲里塞四边形，不是 GPU 画不动。现在颗粒由
 * {@code assets/watchcraft/shaders/program/screen_noise.fsh} 按格子哈希生成，每帧只画一次
 * 全屏四边形，密度、灰度、alpha 和重掷节奏都和原来的循环一致。
 *
 * <h2>为什么在 GUI 阶段跑，而不是并进 {@code drone_link}</h2>
 *
 * <p>颗粒必须压在面罩和仪表盘上，也必须盖住整个世界和原版 HUD。{@code drone_link} 在
 * {@code AFTER_LEVEL} 跑，那时候 GUI 还没画；要让它管颗粒就得把整条链挪到 GUI 之后，
 * 连带把模糊也拖到描边和 HUD 底下。所以分开：模糊留在世界里，颗粒在这里 ——
 * {@link DroneHud} 在原来描颗粒的位置调用 {@link #render}，层级和从前逐字一致。
 *
 * <h2>为什么先 flush</h2>
 *
 * <p>后处理采样的是 {@code minecraft:main} 这张已经画好的纹理，而 {@code GuiGraphics} 只是
 * 把填充攒在顶点缓冲里。不先 flush，被采样的画面里根本没有刚才那些填充。flush 之后 main
 * 就是"到颗粒为止的全部画面"，处理完再由链子末尾的 blit 写回 main，后面面罩继续往上画。
 *
 * <h2>为什么着色器每条路径都写 alpha 1</h2>
 *
 * <p>{@code PostPass#process} 在绘制前会先清空自己的输出目标，链子最后一条 blit 的输出
 * 目标就是 {@code minecraft:main}。如果 swap 里某个像素的 alpha 小于 1，blit 的
 * {@code srcalpha / ONE_MINUS_SRC_ALPHA} 就会把刚清空的底色混进来，屏幕上出现一块块黑斑。
 * 所以颗粒分支和直通分支都写 1.0 —— 颜色取自 main，alpha 不沿用。
 */
public final class DroneNoise {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 和 {@link DroneSignal} 一样，是资源路径而不是 shader 注册名：PostChain 直接读文件。 */
    private static final ResourceLocation EFFECT =
            ResourceLocation.fromNamespaceAndPath("watchcraft", "shaders/post/screen_noise.json");

    /** 链子第一次用到时构建。连接无人机时就先建好，免得颗粒第一次出现时现编译着色器。 */
    private static PostChain effect;
    /** 构建失败后锁死，避免每帧重试刷日志。断链时由 {@link #release()} 放开。 */
    private static boolean broken;
    /** 链子内部目标上次对齐到的窗口尺寸。 */
    private static int width = -1;
    private static int height = -1;

    private DroneNoise() {
    }

    /**
     * 提前把链子建好。
     *
     * <p>由 {@link DroneSignal#renderLevelStage} 在操控无人机时每帧调用一次，构建之后就是
     * 两次空判断。着色器编译不能留到 {@link #render} 里：那是在 GUI 顶点缓冲已经攒了一半的
     * 时候，卡一下最明显，而且颗粒第一次出现恰好也是画面最该稳的时候。
     */
    public static void prepare() {
        if (effect != null || broken) {
            return;
        }
        acquire(Minecraft.getInstance());
    }

    /**
     * 在 GUI 上叠一层颗粒。
     *
     * <p>调用点就是原来逐颗粒循环的位置，所以前后层级不需要重排。函数自己负责 flush、
     * 后处理、把 main 绑回来；返回之后 {@code GuiGraphics} 可以继续画面罩。
     *
     * @param guiWidth   横向 GUI 像素数，格子按它切
     * @param guiHeight  纵向 GUI 像素数
     * @param density    每一格被点亮的概率，0..1；由旧的颗粒数换算，见 {@code DroneHud}
     * @param seed       重掷种子，只取低 16 位，保证 float 能精确表示
     * @param detonation {@code true} 用引爆雪花的分布，{@code false} 用信号劣化的分布
     */
    public static void render(GuiGraphics graphics, int guiWidth, int guiHeight,
                              double density, long seed, boolean detonation) {
        if (broken || guiWidth <= 0 || guiHeight <= 0) {
            return;
        }
        double clamped = Math.min(density, 1.0D);
        if (clamped <= 0.0D) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (effect == null && !acquire(minecraft)) {
            return;
        }
        resize(minecraft);

        effect.setUniform("GrainDensity", (float) clamped);
        effect.setUniform("GrainSeed", (float) (seed & 0xFFFFL));
        effect.setUniform("GrainMode", detonation ? 1.0F : 0.0F);
        effect.setUniform("GuiWidth", guiWidth);
        effect.setUniform("GuiHeight", guiHeight);

        // 先把攒着的填充送进 main，后处理才有东西可采样。这一步同时把原版 HUD 也送进去了，
        // 所以颗粒会盖在它上面 —— 和逐颗粒 fill 时代完全一样。
        graphics.flush();

        // 和 DroneSignal 绕着 effect.process 的那一圈是同一套：链子自己会设投影和视口，
        // 但不会替调用方关掉混合、深度和裁剪，也不会把 main 绑回来。
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.disableScissor();
        RenderSystem.resetTextureMatrix();
        effect.process(0.0F);
        minecraft.getMainRenderTarget().bindWrite(true);
    }

    /**
     * 断开链路时调用：关掉链子、释放它自己的交换目标，并给下一次连接留一次重建机会。
     *
     * <p>幂等，每帧调用都行。
     */
    public static void release() {
        if (effect != null) {
            effect.close();
            effect = null;
        }
        width = -1;
        height = -1;
        broken = false;
    }

    private static boolean acquire(Minecraft minecraft) {
        try {
            effect = new PostChain(
                    minecraft.getTextureManager(),
                    minecraft.getResourceManager(),
                    minecraft.getMainRenderTarget(),
                    EFFECT);
            width = -1;
            height = -1;
            LOGGER.info("Post chain {} built; the visor's grain is one GPU pass",
                    EFFECT.getPath());
            return true;
        } catch (Exception exception) {
            // 和 DroneSignal#acquire 同样的理由：PostChain 自己只抛不记，而 ChainedJsonException
            // 继承 IOException，漏抓会让颗粒无声无息地消失。错误必须落到日志里。
            LOGGER.error("Post chain {} failed to build; the visor will lose its grain, "
                    + "including the detonation snow. Run `python tools/verify_shaders.py` "
                    + "to find the offending pass.", EFFECT, exception);
            broken = true;
            effect = null;
            return false;
        }
    }

    /**
     * 让链子的内部目标跟上窗口大小。窗口缩放不会通知这条链子，所以每帧比一次。
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
}
