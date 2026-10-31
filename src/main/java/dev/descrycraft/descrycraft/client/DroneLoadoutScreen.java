package dev.descrycraft.descrycraft.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import dev.descrycraft.descrycraft.entity.ReconDroneEntity;
import dev.descrycraft.descrycraft.item.DroneModules;
import dev.descrycraft.descrycraft.item.ReconDroneItem;
import dev.descrycraft.descrycraft.network.DroneLoadoutPayload;
import dev.descrycraft.descrycraft.registry.ModEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 改装界面：机体悬在世界里，装着的板子缩成右上角一排，每一块用一条柔软的曲线牵回机体。
 *
 * <p>刻意没有面板、没有暗底 —— 这一层只放三样东西：一个标题条、一排配件、一具机体。
 * 界面越空，玩家越能看清自己在改什么。
 *
 * <p>机体只在按住左键拖动时转动。以前偏航直接吃鼠标的横坐标，鼠标在界面上随便一划机头就
 * 跟着甩，既没法安静地看，也让人以为界面在抢鼠标。现在拖动才是转动，松手就停。
 *
 * <p>连线原来是一条条从配件直直插到机体上沿的直线，九条一起像一把扇骨。现在每条线从
 * 配件向下离开，沿贝塞尔曲线绕到机体周围一圈看不见的"接口环"上，各自落在不同的接口点；
 * 颜色也从配件端的极淡过渡到机体端的一点青光。所有曲线、环和接点都在**一次**批处理里画完，
 * 不再是每个像素两次 {@code graphics.fill} —— 原因见 {@link #drawLinks}。
 *
 * <p>开关只在客户端"预演"一次，随后把意图发给服务端，由服务端用同一套规则再算一遍 ——
 * 见 {@code ModNetwork#handleLoadout}。
 *
 * <p>性能上照着雪花屏（{@link DroneNoise}）那套思路改的：那里的结论是"每帧上万次 GUI 填充"
 * 才是卡的原因，搬到 GPU 上一次画完就好了。这个界面是同一个病 —— 每一块板、每一道边、每一颗
 * 开关、每一条曲线原来都是独立的 {@code graphics.fill}，而 1.21.1 的 {@code GuiGraphics.fill}
 * 在非 managed 状态下**每一次调用都要 endBatch 一次**（还要开关深度测试），一帧一百多次。
 * 现在：
 * <ul>
 *   <li>所有方块（标题条、连线、板子、开关、暗罩、状态灯）分两批写进 {@code RenderType.gui()}
 *       的同一个顶点缓冲，一帧只提交几次；</li>
 *   <li>机体那 7500 多个四边形也不再每帧重新变换、重新写一遍顶点：朝向或窗口尺寸没变时，
 *       直接重放一份早就烘好的 {@link VertexBuffer}。玩家不拖的时候，画机体几乎是零 CPU。</li>
 * </ul>
 */
public class DroneLoadoutScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 机体渲染框；接口环和曲线的终点都以这个框的中心为圆心。 */
    private static final int BOX = 150;
    private static final int SLOT = 24;
    private static final int ICON = 16;
    private static final int MARGIN = 8;
    /** 开关中心落在配件格子下边缘再往下多少像素，也就是曲线离开格子后的第一小段。 */
    private static final int SWITCH_DROP = 14;

    /** 接口环的半径。比机体略大，线停在模型外面，不会钻进机体里。 */
    private static final float RING_RX = 62.0F;
    private static final float RING_RY = 46.0F;

    /**
     * 配件环的半径：九块配件挂在机体的外圈上，像一圈卡口。
     *
     * <p>竖半径比横半径小，是因为这一圈是"绕机体转"的，用椭圆才有俯视转台的感觉；
     * 真正的圆反而像贴在屏幕上的贴纸。
     */
    private static final float ORBIT_RX = 132.0F;
    private static final float ORBIT_RY = 100.0F;

    /** 环的零位朝向。拖动时整圈按 {@code yaw - YAW_HOME} 跟着机体一起转。 */
    private static final float YAW_HOME = 202.0F;

    private static final int TITLE_BG = 0xB4000000;
    private static final int TITLE_EDGE = 0x22FFFFFF;
    private static final int SLOT_BG = 0xA6000000;
    private static final int SLOT_EDGE = 0x55FFFFFF;
    private static final int SLOT_EDGE_HOT = 0xFFFFFFFF;
    private static final int SLOT_EDGE_ON = 0x883BE8FF;
    private static final int TEXT = 0xFFE8F2FA;
    private static final int CYAN = 0xFF3BE8FF;
    private static final int SWITCH_OFF = 0xFF56636E;
    private static final int SWITCH_TRACK = 0xE611171C;

    /**
     * 相机俯角的符号。
     *
     * <p>整条 pose 末尾有一次 Z 轴 180° 翻转（用来抵消 {@code scale(z = -1)} 造成的上下颠倒），
     * 所以在这之后加的俯仰，正负是反的。这里单独拎出来，万一实机上看着是从下往上仰，
     * 只翻这一个符号就行。
     */
    private static final float VIEW_TILT = -18.0F;

    /** 拖动灵敏度：横向一度一像素偏重，纵向再轻一点，免得一拉就翻过去。 */
    private static final float YAW_PER_PIXEL = 0.55F;
    private static final float PITCH_PER_PIXEL = 0.32F;
    private static final float PITCH_LIMIT = 28.0F;

    /**
     * 惯性：拖动改的是"目标角"，机体追着目标转，差值就是重量感。
     *
     * <p>{@code ROTATION_FOLLOW} 是追上去的快慢（1/秒）。调大到 40 以上就变成硬跟手，惯性没了；
     * 调小到 10 以下会拖泥带水，像在水里转。
     */
    private static final float ROTATION_FOLLOW = 20.0F;
    /** 松手后角速度的衰减时间常数（秒）。滑行的总角度约等于 速度 × 这个数。 */
    private static final float ROTATION_COAST = 0.16F;
    /** 角速度上限（度/秒）。甩得再狠也就滑这么多，不会一松手转过一整圈。 */
    private static final float ROTATION_MAX_SPEED = 1200.0F;
    /** 滑到慢于这个角速度（度/秒）就当它停了，别让浮点数在那儿抖。 */
    private static final float ROTATION_STOP = 4.0F;
    /** 单帧最多推进多少秒。卡一下之后不至于一帧转过半圈。 */
    private static final float MAX_STEP = 0.1F;
    /** 每条曲线采样多少段。26 段在 1080p 下已经看不出折角，再多只是浪费顶点。 */
    private static final int CURVE_STEPS = 26;

    /** 一次点击的落点：开关本体，或者它牵着的那个配件格子。 */
    private record Node(int flag, int plateX, int plateY,
                        float anchorX, float anchorY, float normalX, float normalY) {

        /** 开关落在配件与接口环之间的那段连线上，所以方向从法线反推，而不是固定"往下"。 */
        int switchX() {
            return Math.round(this.plateX + SLOT / 2.0F
                    - this.normalX * (SLOT / 2.0F + SWITCH_DROP));
        }

        int switchY() {
            return Math.round(this.plateY + SLOT / 2.0F
                    - this.normalY * (SLOT / 2.0F + SWITCH_DROP));
        }

        boolean hitsSwitch(double mouseX, double mouseY) {
            return mouseX >= switchX() - 8 && mouseX < switchX() + 8
                    && mouseY >= switchY() - 6 && mouseY < switchY() + 6;
        }

        boolean hitsPlate(double mouseX, double mouseY) {
            // 放宽一个像素：配件是一圈小方块，光标压在边上就得算"指着它"，否则提示框会一闪一闪。
            return mouseX >= this.plateX - 1 && mouseX < this.plateX + SLOT + 1
                    && mouseY >= this.plateY - 1 && mouseY < this.plateY + SLOT + 1;
        }
    }

    private final InteractionHand hand;
    /** 用来渲染的那具机体。它不进世界，只是个画画的架子。 */
    private ReconDroneEntity preview;

    /**
     * 朝向。拖动改的是 {@code yawTarget}，画面上的 {@code yaw} 追着它走 —— 这一层之差就是惯性。
     *
     * <p>松手之后目标不再拉人，机体带着残余角速度继续滑，直到 {@link #ROTATION_COAST} 把它磨停。
     * 配件环挂的是 {@code yaw}，所以整圈会跟着一起甩出去，而不是齐刷刷地停住。
     */
    private float yaw = YAW_HOME;
    private float pitch;
    private float yawTarget = YAW_HOME;
    private float pitchTarget;
    /** 角速度，单位是度/秒：拖动时由位移推出来，松手后靠它滑行。 */
    private float yawVelocity;
    private float pitchVelocity;
    private boolean dragging;
    private double lastDragX;
    private double lastDragY;
    private long lastFrameNanos;

    // ---- 缓存：每帧都会用到、但只有状态变了才需要重算的东西

    /** 机体烘焙后的一层几何：一个 {@code RenderType} 配一份顶点缓冲。 */
    private record Baked(RenderType type, VertexBuffer buffer) {
    }

    private final List<Baked> baked = new ArrayList<>();
    /** 朝向、窗口尺寸或预览机体变了，烘好的那份就过期。 */
    private boolean bakeDirty = true;
    /** 烘过一次失败就永远走现画的回退路径，别每帧再试。 */
    private boolean bakeFailed;

    private List<Node> cachedNodes = List.of();
    private int cachedMask = -1;
    /** 环的转角。拖动时它每帧都在变，所以也要进缓存键，不然配件不会跟着转。 */
    private float cachedSpin = Float.NaN;
    private int cachedWidth = -1;
    private int cachedHeight = -1;

    private int tooltipFlag = Integer.MIN_VALUE;
    private int tooltipActive;
    private List<Component> cachedTooltip = List.of();

    /** 配件图标堆栈按位缓存：{@code moduleStack} 每次都新建一个 ItemStack，别每帧建九次。 */
    private final Map<Integer, ItemStack> icons = new HashMap<>();

    public DroneLoadoutScreen(InteractionHand hand) {
        super(Component.translatable("screen.descrycraft.loadout"));
        this.hand = hand;
    }

    // ------------------------------------------------------------------ opening

    /**
     * 打开改装界面。由机体物品的「潜行 + 右键」调用。
     *
     * <p>只有客户端能做这件事，所以这里直接在客户端侧判断并开界面；服务端那一侧对应的手势
     * 什么也不做（{@code ReconDroneItem#sneakAction}）。
     */
    public static void open(Player player, InteractionHand hand) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || DroneController.isLinked()) {
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof ReconDroneItem)) {
            return;
        }
        if (!DroneModules.has(DroneModules.of(stack), DroneModules.CUSTOMIZATION)) {
            player.displayClientMessage(
                    Component.translatable("message.descrycraft.no_loadout"), true);
            return;
        }
        minecraft.setScreen(new DroneLoadoutScreen(hand));
    }

    @Override
    protected void init() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            this.preview = ModEntities.RECON_DRONE.get().create(minecraft.level);
        }
        // 窗口尺寸变了，机体在屏幕上的落点也就变了 —— 烘好的顶点里带着这个位移。
        this.bakeDirty = true;
    }

    @Override
    public void removed() {
        clearBaked();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ geometry

    private int boxX() {
        return (this.width - BOX) / 2;
    }

    private int boxY() {
        return (this.height - BOX) / 2;
    }

    private boolean inBox(double mouseX, double mouseY) {
        return mouseX >= boxX() && mouseX < boxX() + BOX
                && mouseY >= boxY() && mouseY < boxY() + BOX;
    }

    /** {@return the fitted flags, in the order they should be listed} */
    private static List<Integer> fitted(ItemStack stack) {
        int mask = DroneModules.of(stack);
        List<Integer> flags = new ArrayList<>();
        for (int flag : DroneModules.FLAGS) {
            if (DroneModules.has(mask, flag)) {
                flags.add(flag);
            }
        }
        return flags;
    }

    /**
     * 把配件挂到机体外圈上，整圈跟着朝向一起转。
     *
     * <p>以前是右上角一排九块，再用九条长线拽到机体上——那些线注定要挤成一束，参数怎么调都像
     * 一堆电缆。挂到环上之后，每块配件只负责自己那一小段连线：短、方向彼此错开，既不会交叉，
     * 也不会在机体旁边打结。
     *
     * <p>角度按 {@code yaw} 走：玩家拖动转动机体时，这一圈配件跟着一起转。它们本来就"装在机体
     * 周围"，机体转，它们当然跟着转。
     */
    private List<Node> nodes(ItemStack stack) {
        int mask = DroneModules.of(stack);
        float spin = Mth.wrapDegrees(this.yaw - YAW_HOME);
        if (mask == this.cachedMask && spin == this.cachedSpin
                && this.width == this.cachedWidth && this.height == this.cachedHeight) {
            return this.cachedNodes;
        }
        List<Node> nodes = buildNodes(stack, spin);
        this.cachedMask = mask;
        this.cachedSpin = spin;
        this.cachedWidth = this.width;
        this.cachedHeight = this.height;
        this.cachedNodes = nodes;
        return nodes;
    }

    private List<Node> buildNodes(ItemStack stack, float spin) {
        List<Integer> flags = fitted(stack);
        List<Node> nodes = new ArrayList<>();
        int count = flags.size();
        if (count == 0) {
            return nodes;
        }
        float centreX = this.boxX() + BOX / 2.0F;
        float centreY = this.boxY() + BOX / 2.0F;
        // 窗口太窄时把整圈往里收：宁可贴着机体，也别让配件被裁到屏幕外。
        float rx = Math.max(RING_RX + 30.0F,
                Math.min(ORBIT_RX, (this.width - 2.0F * MARGIN - SLOT) / 2.0F));
        float ry = Math.max(RING_RY + 24.0F,
                Math.min(ORBIT_RY, (this.height - 2.0F * MARGIN - SLOT) / 2.0F));

        for (int i = 0; i < count; i++) {
            // 从正上方开始顺时针铺满一圈；只有一个配件时正好落在头顶。
            double angle = Math.toRadians(-90.0 + 360.0 * i / (double) count + spin);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            int plateX = Math.round(centreX + cos * rx - SLOT / 2.0F);
            int plateY = Math.round(centreY + sin * ry - SLOT / 2.0F);
            float anchorX = centreX + cos * RING_RX;
            float anchorY = centreY + sin * RING_RY;
            // 椭圆外法线：线从环外面切进来，而不是斜着戳进去。
            float nx = cos / rx;
            float ny = sin / ry;
            float nLen = (float) Math.max(1.0E-4, Math.sqrt(nx * nx + ny * ny));
            nodes.add(new Node(flags.get(i), plateX, plateY,
                    anchorX, anchorY, nx / nLen, ny / nLen));
        }
        return nodes;
    }

    // ------------------------------------------------------------------ render

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 什么都不铺：世界照旧透出来，这正是这个界面"简洁"的来源。
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        stepRotation(frameDelta());

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        ItemStack stack = minecraft.player.getItemInHand(this.hand);
        if (!holdsConfigurableDrone(stack)) {
            // 手里的东西被换掉了。这里不调 onClose —— 那会在渲染途中把 screen 换掉，
            // 剩下的绘制就落在一个已经卸下来的界面上。什么都不画，让玩家自己按 Esc。
            return;
        }

        int active = DroneModules.activeOf(stack);
        List<Node> nodes = nodes(stack);

        // 第一大批：标题条、连线、接口环、九块板、九个开关。原来是八十多次 fill，
        // 每次 fill 都会 endBatch 一次；现在全写进同一个 gui() 缓冲，只提交一次。
        Matrix4f matrix = graphics.pose().last().pose();
        VertexConsumer buffer = graphics.bufferSource().getBuffer(RenderType.gui());
        drawTitle(buffer, matrix);
        // 线先画，机体最后画；线停在接口环上，不会钻进框里。
        drawLinks(buffer, matrix, nodes, active, mouseX, mouseY);
        for (Node node : nodes) {
            boolean on = DroneModules.has(active, node.flag());
            boolean hovered = node.hitsSwitch(mouseX, mouseY) || node.hitsPlate(mouseX, mouseY);
            drawPlate(buffer, matrix, node, on, hovered);
            drawSwitch(buffer, matrix, node, on, hovered);
        }
        graphics.flush();

        // 图标走物品渲染，自己会提交；暗罩和状态灯得压在图标上面，所以只能等这一步之后再补一批。
        for (Node node : nodes) {
            drawIcon(graphics, node);
        }
        VertexConsumer overlay = graphics.bufferSource().getBuffer(RenderType.gui());
        Matrix4f overlayMatrix = graphics.pose().last().pose();
        for (Node node : nodes) {
            boolean hovered = node.hitsSwitch(mouseX, mouseY) || node.hitsPlate(mouseX, mouseY);
            drawOverlay(overlay, overlayMatrix, node, DroneModules.has(active, node.flag()), hovered);
        }
        graphics.flush();

        // 文字放最后：drawString 自己也会提交，摆在前面只会把上面两批切碎。
        drawTitleText(graphics);
        drawNotice(graphics, stack);

        renderDrone(graphics);

        for (Node node : nodes) {
            if (node.hitsSwitch(mouseX, mouseY) || node.hitsPlate(mouseX, mouseY)) {
                graphics.renderTooltip(this.font, tooltip(node.flag(), active),
                        Optional.empty(), mouseX, mouseY);
                break;
            }
        }
    }

    /**
     * 左上角一条窄标题条，和 HUD 的仪表盘同一套配色。
     *
     * <p>这里只放名字：拖动提示、操作说明这类字在实机上是纯噪音，看过一次就不会再看，
     * 反而把界面撑得"啰嗦"。要提示就交给 Tooltip。
     */
    private void drawTitle(VertexConsumer buffer, Matrix4f matrix) {
        String title = this.title.getString();
        int barWidth = Math.max(120, this.font.width(title) + 26);
        int left = MARGIN;
        int top = MARGIN;
        rect(buffer, matrix, left, top, left + barWidth, top + 22, TITLE_BG);
        rect(buffer, matrix, left, top + 21, left + barWidth, top + 22, TITLE_EDGE);
        // 左边那道竖线是这一族界面的记号，别去掉。
        rect(buffer, matrix, left, top, left + 2, top + 22, CYAN);
    }

    private void drawTitleText(GuiGraphics graphics) {
        graphics.drawString(this.font, this.title.getString(), MARGIN + 10, MARGIN + 7, TEXT, false);
    }

    /** 没电池时的警告。 */
    private void drawNotice(GuiGraphics graphics, ItemStack stack) {
        if (ReconDroneItem.batteryOf(stack).isEmpty()) {
            // 没电池时所有开关都是摆设 —— 不说清楚，玩家会以为开关坏了。
            graphics.drawString(this.font,
                    Component.translatable("screen.descrycraft.loadout.no_battery").getString(),
                    MARGIN + 2, MARGIN + 28, 0xFFFFB020, true);
        }
    }

    /**
     * 画所有连线、接口环和接点。
     *
     * <p>这里原来是一条 Bresenham 直线，每个像素调两次 {@code graphics.fill}。1.21.1 的
     * {@code GuiGraphics.fill} 在非 managed 状态下**每调一次就 endBatch 一次**（还要开关
     * 深度测试），一帧九条线、每条几百像素，就是上万次缓冲提交 —— 这个界面掉帧的根子。
     *
     * <p>现在全部顶点塞进 {@code RenderType.gui()} 的同一个 {@link VertexConsumer}，最后只
     * {@code flush()} 一次：一帧一次提交，顶点数几百，开销可以忽略。
     */
    private void drawLinks(VertexConsumer buffer, Matrix4f matrix, List<Node> nodes, int active,
                           int mouseX, int mouseY) {
        addRing(buffer, matrix, boxX() + BOX / 2.0F, boxY() + BOX / 2.0F, 0x2E86E9FF);

        for (Node node : nodes) {
            boolean on = DroneModules.has(active, node.flag());
            boolean hovered = node.hitsSwitch(mouseX, mouseY) || node.hitsPlate(mouseX, mouseY);
            int start = on ? 0x969FE8FF : 0x38AFC4CE;
            int end = on ? 0xF5E8FBFF : 0x78D6E6EE;
            if (hovered) {
                start = withAlpha(start, Math.min(0xFF, alpha(start) + 0x38));
                end = withAlpha(end, Math.min(0xFF, alpha(end) + 0x20));
            }

            float[] points = curve(node);
            // 先铺一层比芯更宽的暗底：线要横穿草地和天空，不垫底的话亮背景那一段会消失。
            stroke(buffer, matrix, points, 2.6F, 0x30000000, 0x30000000);
            stroke(buffer, matrix, points, 1.1F, start, end);
            addDot(buffer, matrix, node.anchorX(), node.anchorY(),
                    on ? 0xFFBFF6FF : 0x88AFC4CE, on ? 2.3F : 1.7F);
        }
    }

    /**
     * 一条把配件接到接口环上的三次贝塞尔曲线。
     *
     * <p>起点在配件朝内的那条边（不是固定的"下面"）：配件绕着机体转，朝向一直在变，起点的
     * 方向得跟着法线走。中段用很小的弓形偏移，线才有"搭上去"的手感，而不是一根直尺。
     *
     * @return {@code [x0, y0, x1, y1, ...]}，长度 {@code CURVE_STEPS * 2}
     */
    private static float[] curve(Node node) {
        float nx = node.normalX();
        float ny = node.normalY();
        float x0 = node.plateX() + SLOT / 2.0F - nx * (SLOT / 2.0F + 1.0F);
        float y0 = node.plateY() + SLOT / 2.0F - ny * (SLOT / 2.0F + 1.0F);
        float x3 = node.anchorX();
        float y3 = node.anchorY();
        float dx = x3 - x0;
        float dy = y3 - y0;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 1.0E-3F) {
            length = 1.0F;
        }
        // 垂直方向上的轻弓，幅度随长度收放，短连线不会被弓成一个圈。
        float px = -dy / length * Math.min(9.0F, length * 0.18F);
        float py = dx / length * Math.min(9.0F, length * 0.18F);
        float x1 = x0 + dx * 0.33F + px;
        float y1 = y0 + dy * 0.33F + py;
        float x2 = x3 - dx * 0.33F + px;
        float y2 = y3 - dy * 0.33F + py;

        float[] points = new float[CURVE_STEPS * 2];
        for (int i = 0; i < CURVE_STEPS; i++) {
            float t = i / (float) (CURVE_STEPS - 1);
            float u = 1.0F - t;
            float a = u * u * u;
            float b = 3.0F * u * u * t;
            float c = 3.0F * u * t * t;
            float d = t * t * t;
            points[i * 2] = a * x0 + b * x1 + c * x2 + d * x3;
            points[i * 2 + 1] = a * y0 + b * y1 + c * y2 + d * y3;
        }
        return points;
    }

    /**
     * 沿采样点铺一串四边形，宽度和颜色从起点到终点渐变。
     *
     * <p>顶点直接写进批处理缓冲，不经过 {@code fill}，所以整条曲线没有额外的提交。
     */
    private static void stroke(VertexConsumer buffer, Matrix4f matrix, float[] points,
                               float width, int startColor, int endColor) {
        for (int i = 0; i < CURVE_STEPS - 1; i++) {
            float t0 = i / (float) (CURVE_STEPS - 1);
            float t1 = (i + 1) / (float) (CURVE_STEPS - 1);
            // 越靠近机体越细，像被接口"收"进去。
            float w0 = width * (1.0F - 0.30F * t0);
            float w1 = width * (1.0F - 0.30F * t1);
            segment(buffer, matrix,
                    points[i * 2], points[i * 2 + 1],
                    points[(i + 1) * 2], points[(i + 1) * 2 + 1],
                    w0, w1, lerpColor(startColor, endColor, t0),
                    lerpColor(startColor, endColor, t1));
        }
    }

    /**
     * 一条加粗的线段，法线方向撑开 {@code w0}/{@code w1} 个像素。
     *
     * <p>顶点顺序必须和 {@code GuiGraphics#fill} 一致（左上 → 左下 → 右下 → 右上）。
     * {@code RenderType.gui()} 没有关掉背面剔除（{@code CompositeStateBuilder} 默认 {@code CULL}），
     * 顺序反过来整批四边形就全被剔掉 —— 之前连线"整个消失"就是这么来的。
     */
    private static void segment(VertexConsumer buffer, Matrix4f matrix,
                                float x0, float y0, float x1, float y1,
                                float w0, float w1, int c0, int c1) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 1.0E-4F) {
            return;
        }
        float nx = -dy / length;
        float ny = dx / length;
        // 起点的两侧 -> 终点的两侧，按"左上、左下、右下、右上"的绕向来写：
        // {@code RenderType.gui()} 的 CompositeState 默认 {@code CULL}，绕反了整批四边形
        // 都会被背面剔除吃掉，表现就是线一条都看不见。这里不能随手换顺序。
        buffer.addVertex(matrix, x0 + nx * w0, y0 + ny * w0, 0.0F).setColor(c0);
        buffer.addVertex(matrix, x1 + nx * w1, y1 + ny * w1, 0.0F).setColor(c1);
        buffer.addVertex(matrix, x1 - nx * w1, y1 - ny * w1, 0.0F).setColor(c1);
        buffer.addVertex(matrix, x0 - nx * w0, y0 - ny * w0, 0.0F).setColor(c0);
    }

    /** 机体周围那圈极淡的接口环：线要有个东西可接，光停在半空会像没画完。 */
    private static void addRing(VertexConsumer buffer, Matrix4f matrix,
                                float centreX, float centreY, int color) {
        int segments = 72;
        for (int i = 0; i < segments; i++) {
            float a0 = (float) (i * Math.PI * 2.0 / segments);
            float a1 = (float) ((i + 1) * Math.PI * 2.0 / segments);
            segment(buffer, matrix,
                    centreX + (float) Math.cos(a0) * RING_RX,
                    centreY + (float) Math.sin(a0) * RING_RY,
                    centreX + (float) Math.cos(a1) * RING_RX,
                    centreY + (float) Math.sin(a1) * RING_RY,
                    0.55F, 0.55F, color, color);
        }
    }

    /** 落点上的小菱形接点。 */
    private static void addDot(VertexConsumer buffer, Matrix4f matrix,
                               float x, float y, int color, float radius) {
        // 上 → 左 → 下 → 右，和 {@code GuiGraphics#fill} 的绕向一致（见 {@link #segment}）。
        buffer.addVertex(matrix, x, y - radius, 0.0F).setColor(color);
        buffer.addVertex(matrix, x - radius, y, 0.0F).setColor(color);
        buffer.addVertex(matrix, x, y + radius, 0.0F).setColor(color);
        buffer.addVertex(matrix, x + radius, y, 0.0F).setColor(color);
    }

    /**
     * 一个实心方块，直接写进批处理缓冲 —— 也就是 {@code graphics.fill} 的"不提交"版本。
     *
     * <p>顶点绕向必须和 {@code GuiGraphics#fill} 一致（左上 → 左下 → 右下 → 右上）：
     * {@code RenderType.gui()} 没关背面剔除，绕反了整块会被吃掉，原因见 {@link #segment}。
     */
    private static void rect(VertexConsumer buffer, Matrix4f matrix,
                             int x1, int y1, int x2, int y2, int color) {
        if (x1 > x2) {
            int swap = x1;
            x1 = x2;
            x2 = swap;
        }
        if (y1 > y2) {
            int swap = y1;
            y1 = y2;
            y2 = swap;
        }
        buffer.addVertex(matrix, x1, y1, 0.0F).setColor(color);
        buffer.addVertex(matrix, x1, y2, 0.0F).setColor(color);
        buffer.addVertex(matrix, x2, y2, 0.0F).setColor(color);
        buffer.addVertex(matrix, x2, y1, 0.0F).setColor(color);
    }

    private static int alpha(int argb) {
        return argb >>> 24;
    }

    private static int withAlpha(int argb, int alpha) {
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    private static int lerpColor(int from, int to, float t) {
        int a = Mth.lerpInt(t, from >>> 24, to >>> 24);
        int r = Mth.lerpInt(t, (from >> 16) & 0xFF, (to >> 16) & 0xFF);
        int g = Mth.lerpInt(t, (from >> 8) & 0xFF, (to >> 8) & 0xFF);
        int b = Mth.lerpInt(t, from & 0xFF, to & 0xFF);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private void drawPlate(VertexConsumer buffer, Matrix4f matrix, Node node, boolean on,
                           boolean hovered) {
        int x = node.plateX();
        int y = node.plateY();
        int edge = hovered ? SLOT_EDGE_HOT : on ? SLOT_EDGE_ON : SLOT_EDGE;
        rect(buffer, matrix, x, y, x + SLOT, y + SLOT, SLOT_BG);
        rect(buffer, matrix, x, y, x + SLOT, y + 1, edge);
        rect(buffer, matrix, x, y + SLOT - 1, x + SLOT, y + SLOT, edge);
        rect(buffer, matrix, x, y, x + 1, y + SLOT, edge);
        rect(buffer, matrix, x + SLOT - 1, y, x + SLOT, y + SLOT, edge);
    }

    /** 板子上的图标。{@code renderItem} 内部会提交一次，所以它单独一步、不进上面的批。 */
    private void drawIcon(GuiGraphics graphics, Node node) {
        ItemStack module = iconOf(node.flag());
        if (module.isEmpty()) {
            return;
        }
        graphics.renderItem(module, node.plateX() + (SLOT - ICON) / 2,
                node.plateY() + (SLOT - ICON) / 2);
    }

    /** 压在图标上面的两层：关掉时的暗罩、内沿那颗状态灯。 */
    private void drawOverlay(VertexConsumer buffer, Matrix4f matrix, Node node, boolean on,
                             boolean hovered) {
        int x = node.plateX();
        int y = node.plateY();
        if (!on) {
            // 关掉的配件变暗，而不是消失：它还在机上，只是没在跑，这两件事玩家要能分清。
            // 但光标压上去时得让开一点，否则暗到看不清是哪一块 —— "能查看"先于"能分辨状态"。
            rect(buffer, matrix, x + 1, y + 1, x + SLOT - 1, y + SLOT - 1,
                    hovered ? 0x46000000 : 0xA6000000);
        }
        // 内沿一颗状态灯，和开关同一色。灯朝着机体那一侧，一圈扫过去时视线是连着的。
        int ledX = Math.round(x + SLOT / 2.0F - node.normalX() * (SLOT / 2.0F - 4.0F));
        int ledY = Math.round(y + SLOT / 2.0F - node.normalY() * (SLOT / 2.0F - 4.0F));
        rect(buffer, matrix, ledX - 1, ledY - 1, ledX + 2, ledY + 2, on ? CYAN : 0x6656636E);
    }

    private ItemStack iconOf(int flag) {
        return this.icons.computeIfAbsent(flag, DroneModules::moduleStack);
    }

    /** 线上的开关：一条轨道加一颗滑钮。比"一个圆点"好读，因为它有明确的左右两态。 */
    private void drawSwitch(VertexConsumer buffer, Matrix4f matrix, Node node, boolean on,
                            boolean hovered) {
        int x = node.switchX();
        int y = node.switchY();
        int trackW = 14;
        int trackH = 7;
        int tx = x - trackW / 2;
        int ty = y - trackH / 2;
        int colour = on ? CYAN : hovered ? 0xFF8FA3B0 : SWITCH_OFF;

        rect(buffer, matrix, tx - 1, ty - 1, tx + trackW + 1, ty + trackH + 1, SWITCH_TRACK);
        rect(buffer, matrix, tx, ty, tx + trackW, ty + 1, colour);
        rect(buffer, matrix, tx, ty + trackH - 1, tx + trackW, ty + trackH, colour);
        rect(buffer, matrix, tx, ty, tx + 1, ty + trackH, colour);
        rect(buffer, matrix, tx + trackW - 1, ty, tx + trackW, ty + trackH, colour);

        int knobX = on ? tx + trackW - 6 : tx + 1;
        rect(buffer, matrix, knobX, ty + 1, knobX + 5, ty + trackH - 1, colour);
    }

    /**
     * 画那具 3D 机体。
     *
     * <p>变换是手写的，没借 {@code InventoryScreen#renderEntityInInventoryFollowsAngle}：那个
     * 方法只收 {@code LivingEntity}，而机体是直接继承 {@code Entity} 的。
     *
     * <p>⚠️ {@code scale(s, s, -s)} 之后**必须补一次 Z 轴 180° 翻转** —— 原版那套变换里就有
     * 这一下。少了它模型是倒着的：负的 Z 缩放把整个模型翻了过来，而 GUI 的坐标系朝里是负的。
     *
     * <p>朝向写在实体上，不在 pose 里再转一次：{@code DroneRenderer} 本来就会按
     * {@code getYRot()} / {@code getXRot()} 自己转，两边都转就转了两遍。
     *
     * <p>机体七千多个四边形，每帧重新变换、重新写一遍顶点，是这个界面剩下的大头。可玩家不拖的
     * 时候画面其实一动不动 —— 所以只在朝向或尺寸变了的时候把整具机体烘一次，之后每帧只是把那份
     * 顶点缓冲再画一遍（和雪花屏同一个思路：把逐像素的活换成一次绘制）。
     */
    private void renderDrone(GuiGraphics graphics) {
        if (this.preview == null) {
            return;
        }
        float size = Math.max(this.preview.getBbWidth(), this.preview.getBbHeight());
        if (size <= 0.0F) {
            return;
        }
        int x1 = this.boxX();
        int y1 = this.boxY();
        float scale = BOX * 0.72F / size;
        float centreX = x1 + BOX / 2.0F;
        float centreY = y1 + BOX / 2.0F;

        // 朝向不进烘焙：机体是绕自己的竖轴转的，把这个旋转挪到绘制时的模型矩阵里，
        // 拖一下就不用重烘七千个四边形 —— 之前每帧重烘一次，一拖就掉帧。
        graphics.flush();
        if (!this.bakeFailed && !bakePreview(scale)) {
            this.bakeFailed = true;
            clearBaked();
        }

        graphics.enableScissor(x1, y1, x1 + BOX, y1 + BOX);
        if (this.bakeFailed) {
            // 回退路径是现画的，朝向得写回实体上。
            this.preview.setYRot(this.yaw);
            this.preview.setXRot(0.0F);
            this.preview.yRotO = this.yaw;
            this.preview.xRotO = 0.0F;
            drawPreviewDirect(graphics, centreX, centreY, scale);
        } else {
            drawBaked(centreX, centreY);
        }
        graphics.disableScissor();
    }

    private PoseStack posePreview(GuiGraphics graphics, float centreX, float centreY, float scale) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centreX, centreY, 50.0);
        pose.scale(scale, scale, -scale);
        // 抵消 -Z 缩放：没有这一下模型是倒着的。
        pose.mulPose(Axis.ZP.rotationDegrees(180.0F));
        pose.mulPose(Axis.XP.rotationDegrees(VIEW_TILT + this.pitch));
        return pose;
    }

    /**
     * 烘焙用的姿态：以机体自己为中心，不含位置、不含俯仰。
     *
     * <p>位置和朝向都留到绘制时再乘，这样同一份顶点缓冲能被任意拖动角度复用。
     */
    private PoseStack poseBake(float scale) {
        PoseStack pose = new PoseStack();
        pose.pushPose();
        pose.scale(scale, scale, -scale);
        // 抵消 -Z 缩放：没有这一下模型是倒着的。
        pose.mulPose(Axis.ZP.rotationDegrees(180.0F));
        return pose;
    }

    /**
     * 把整具机体（机身 + 自发光层）烘成顶点缓冲。
     *
     * <p>没有自己重写那套变换：{@code DroneRenderer} 里还有螺旋桨、云台、侧倾这些按实体状态算的
     * 东西，抄一遍迟早会和它走偏。这里只是把 {@code EntityRenderDispatcher#render} 的目标缓冲
     * 换成一个"什么都收"的 {@link MultiBufferSource}，让它照常往里写，写完再把每个
     * {@code RenderType} 的那一段上传成 {@link VertexBuffer}。
     *
     * @return 烘焙成功，或者本来就不需要重烘时返回 true
     */
    private boolean bakePreview(float scale) {
        if (!this.bakeDirty && !this.baked.isEmpty()) {
            return true;
        }
        this.bakeDirty = false;
        clearBaked();

        Map<RenderType, BufferBuilder> builders = new LinkedHashMap<>();
        Map<RenderType, ByteBufferBuilder> memory = new HashMap<>();
        MultiBufferSource sink = type -> {
            ByteBufferBuilder bytes = memory.computeIfAbsent(type, key -> new ByteBufferBuilder(1536));
            return builders.computeIfAbsent(type,
                    key -> new BufferBuilder(bytes, key.mode(), key.format()));
        };

        // 零位朝向烘进去，拖动时再把这个差角乘在模型矩阵上。
        this.preview.setYRot(YAW_HOME);
        this.preview.setXRot(0.0F);
        this.preview.yRotO = YAW_HOME;
        this.preview.xRotO = 0.0F;

        PoseStack pose = poseBake(scale);
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        try {
            Lighting.setupForEntityInInventory();
            dispatcher.setRenderShadow(false);
            try {
                dispatcher.render(this.preview, 0.0, 0.0, 0.0, 0.0F, 1.0F, pose, sink, 15728880);
            } finally {
                dispatcher.setRenderShadow(true);
                Lighting.setupFor3DItems();
            }
            for (Map.Entry<RenderType, BufferBuilder> entry : builders.entrySet()) {
                // 只收实体格式的那几层；万一混进别的（名牌之类），按没画处理。
                if (!DefaultVertexFormat.NEW_ENTITY.equals(entry.getKey().format())) {
                    continue;
                }
                MeshData mesh = entry.getValue().build();
                if (mesh == null) {
                    // 取过这个类型但一个四边形都没写进来（比如被关掉的名牌层）。
                    continue;
                }
                // upload 里的 setupBufferState() 是往"当前绑定的 VAO"上挂属性指针的，
                // 所以必须先 bind 再 upload —— 少了这一步，属性指针会挂到别人的 VAO 上，
                // 自己的 VAO 反而一个属性都没开，画的时候驱动直接读越界（AMD 驱动会当场崩）。
                VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                try {
                    buffer.bind();
                    buffer.upload(mesh);
                } finally {
                    VertexBuffer.unbind();
                }
                this.baked.add(new Baked(entry.getKey(), buffer));
            }
        } catch (RuntimeException error) {
            LOGGER.warn("Could not cache the drone preview geometry; drawing it directly instead",
                    error);
            clearBaked();
            return false;
        } finally {
            pose.popPose();
            // 顶点已经进显存，这几段临时 native 缓冲可以还回去了。
            for (ByteBufferBuilder bytes : memory.values()) {
                bytes.close();
            }
        }
        return !this.baked.isEmpty();
    }

    /**
     * 重放烘好的几何。
     *
     * <p>和 {@code RenderType#draw(MeshData)} 做的事情一样，区别只是顶点早就在显存里：
     * 装好渲染状态 → 画 → 还原状态。矩阵从 {@code RenderSystem} 现取，也就是 GUI 的那套
     * 正交投影 + {@code -11000} 平移，和烘焙时写进去的坐标系同一个。
     */
    private void drawBaked(float centreX, float centreY) {
        // 位置 + 俯仰 + 这一下拖出来的差角：全在模型矩阵里，顶点缓冲一份就够。
        PoseStack pose = new PoseStack();
        pose.translate(centreX, centreY, 50.0);
        pose.mulPose(Axis.XP.rotationDegrees(VIEW_TILT + this.pitch));
        pose.mulPose(Axis.YP.rotationDegrees(Mth.wrapDegrees(YAW_HOME - this.yaw)));

        Lighting.setupForEntityInInventory();
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose.last().pose());
        Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        try {
            for (Baked layer : this.baked) {
                RenderType type = layer.type();
                type.setupRenderState();
                try {
                    // 深度测试必须留着：机体是有前后遮挡的，关掉它背面就会透过来，看起来像块玻璃。
                    // 后面画的文字和提示框不受影响 —— GuiGraphics 自己提交时会临时关掉深度。
                    layer.buffer().bind();
                    layer.buffer().drawWithShader(modelView, projection, RenderSystem.getShader());
                } finally {
                    VertexBuffer.unbind();
                    type.clearRenderState();
                }
            }
        } catch (RuntimeException error) {
            // 这份缓冲画不出来就死心，退回每帧现画 —— 至少界面不会缺一块。
            LOGGER.warn("Could not replay the cached drone preview; drawing it directly instead",
                    error);
            this.bakeFailed = true;
            clearBaked();
        } finally {
            Lighting.setupFor3DItems();
        }
    }

    /** 回退路径：烘焙失败时每帧现画一遍，行为和改性能之前一模一样。 */
    private void drawPreviewDirect(GuiGraphics graphics, float centreX, float centreY, float scale) {
        PoseStack pose = posePreview(graphics, centreX, centreY, scale);
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        try {
            Lighting.setupForEntityInInventory();
            dispatcher.setRenderShadow(false);
            RenderSystem.runAsFancy(() -> dispatcher.render(this.preview, 0.0, 0.0, 0.0, 0.0F, 1.0F,
                    pose, graphics.bufferSource(), 15728880));
            graphics.flush();
            dispatcher.setRenderShadow(true);
            Lighting.setupFor3DItems();
        } finally {
            pose.popPose();
        }
    }

    private void clearBaked() {
        for (Baked layer : this.baked) {
            layer.buffer().close();
        }
        this.baked.clear();
    }

    // ------------------------------------------------------------------ interaction

    /**
     * 这一帧过了多少秒。
     *
     * <p>用真实时间而不是 {@code partialTick}：界面的帧率可能和世界 tick 脱钩（尤其是世界还在
     * 后台跑的时候），拿 tick 当时间会让惯性随帧率变化。
     */
    private float frameDelta() {
        long now = System.nanoTime();
        float delta = this.lastFrameNanos == 0L
                ? 1.0F / 60.0F
                : (now - this.lastFrameNanos) / 1.0E9F;
        this.lastFrameNanos = now;
        return Mth.clamp(delta, 1.0E-3F, MAX_STEP);
    }

    /**
     * 推进朝向：拖动时追目标，松手后靠角速度滑行。
     *
     * <p>两段用的是同一套变量，切换只由 {@link #dragging} 决定 —— 松手的瞬间角速度是接着的，
     * 所以"手上松了、模型还在转"这件事是连续的，不会顿一下再滑。
     */
    private void stepRotation(float delta) {
        float beforeYaw = this.yaw;
        float beforePitch = this.pitch;

        if (this.dragging) {
            // 指数逼近而不是直接赋值：给一个上限，追多快由 ROTATION_FOLLOW 定，
            // 帧率高低不会改变手感。
            float follow = 1.0F - (float) Math.exp(-delta * ROTATION_FOLLOW);
            this.yaw += (this.yawTarget - this.yaw) * follow;
            this.pitch += (this.pitchTarget - this.pitch) * follow;
            // 角速度直接由"这一帧实际挪了多少"反推，松手时就是这个数。
            this.yawVelocity = Mth.clamp((this.yaw - beforeYaw) / delta,
                    -ROTATION_MAX_SPEED, ROTATION_MAX_SPEED);
            this.pitchVelocity = Mth.clamp((this.pitch - beforePitch) / delta,
                    -ROTATION_MAX_SPEED, ROTATION_MAX_SPEED);
        } else if (this.yawVelocity != 0.0F || this.pitchVelocity != 0.0F) {
            this.yaw += this.yawVelocity * delta;
            this.pitch = Mth.clamp(this.pitch + this.pitchVelocity * delta,
                    -PITCH_LIMIT, PITCH_LIMIT);

            float decay = (float) Math.exp(-delta / ROTATION_COAST);
            this.yawVelocity *= decay;
            this.pitchVelocity *= decay;
            if (Math.abs(this.yawVelocity) < ROTATION_STOP) {
                this.yawVelocity = 0.0F;
            }
            // 撞到俯仰的边就当场收住，不然机体会在极限角度上一直抖。
            if (this.pitch <= -PITCH_LIMIT || this.pitch >= PITCH_LIMIT
                    || Math.abs(this.pitchVelocity) < ROTATION_STOP) {
                this.pitchVelocity = 0.0F;
            }
            // 滑行时目标跟着走，所以下一次按下去不会突然弹回去。
            this.yawTarget = this.yaw;
            this.pitchTarget = this.pitch;
        }

        // 角度别无限累加：转到几万度之后 float 的精度就开始掉。只在两者同时回绕，才不会跳。
        this.yaw = Mth.wrapDegrees(this.yaw);
        this.yawTarget = this.yaw + Mth.wrapDegrees(this.yawTarget - this.yaw);
    }

    private static boolean holdsConfigurableDrone(ItemStack stack) {
        return stack.getItem() instanceof ReconDroneItem
                && DroneModules.has(DroneModules.of(stack), DroneModules.CUSTOMIZATION);
    }

    private List<Component> tooltip(int flag, int active) {
        if (flag == this.tooltipFlag && active == this.tooltipActive && !this.cachedTooltip.isEmpty()) {
            return this.cachedTooltip;
        }
        List<Component> lines = buildTooltip(flag, active);
        this.tooltipFlag = flag;
        this.tooltipActive = active;
        this.cachedTooltip = lines;
        return lines;
    }

    private List<Component> buildTooltip(int flag, int active) {
        List<Component> lines = new ArrayList<>();
        ItemStack module = DroneModules.moduleStack(flag);
        lines.add(module.getHoverName().copy().withStyle(ChatFormatting.WHITE));

        boolean on = DroneModules.has(active, flag);
        lines.add(Component.translatable(on ? "screen.descrycraft.loadout.on" : "screen.descrycraft.loadout.off")
                .withStyle(on ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY));

        if (flag == DroneModules.CUSTOMIZATION) {
            lines.add(Component.translatable("screen.descrycraft.loadout.always_on")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return lines;
        }
        if (!on) {
            // 开不了的时候把缺的那几位直接点名，而不是只说"前置不满足" —— 玩家要的是下一步。
            int need = DroneModules.requiresOf(flag);
            if ((active & need) != need) {
                List<Component> missing = DroneModules.names(need & ~active);
                if (!missing.isEmpty()) {
                    lines.add(Component.translatable("screen.descrycraft.loadout.needs", join(missing))
                            .withStyle(ChatFormatting.RED));
                }
            }
        }
        return lines;
    }

    private static Component join(List<Component> parts) {
        var joined = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                joined.append(Component.literal(", "));
            }
            joined.append(parts.get(i));
        }
        return joined;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        ItemStack stack = minecraft.player.getItemInHand(this.hand);
        if (!holdsConfigurableDrone(stack)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        for (Node node : nodes(stack)) {
            if (!node.hitsSwitch(mouseX, mouseY) && !node.hitsPlate(mouseX, mouseY)) {
                continue;
            }
            // 客制化模块是这整个界面的门槛，关掉它界面自己也就没意义了，所以那颗开关是死的。
            if (node.flag() == DroneModules.CUSTOMIZATION) {
                return true;
            }
            this.toggle(stack, node.flag());
            return true;
        }
        // 机体附近的空白也算拖动区：模型不是实心可点选的，只让"点中模型"才能拖太苛刻。
        if (inBox(mouseX, mouseY)) {
            this.dragging = true;
            this.lastDragX = mouseX;
            this.lastDragY = mouseY;
            // 抓住的瞬间把目标和当前角度对齐、速度清零：不然会先从别处"飞"过来。
            this.yawTarget = this.yaw;
            this.pitchTarget = this.pitch;
            this.yawVelocity = 0.0F;
            this.pitchVelocity = 0.0F;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.dragging && button == 0) {
            // 减号：屏幕坐标是"鼠标往右，画面跟着往右"，而机体的 yaw 是反着长的。
            // 用加号会变成"往右拖、模型往左转"，实机上手是反的。
            // 这里只推目标角，真正的转动在 stepRotation 里带惯性追上去。
            this.yawTarget -= (float) (mouseX - this.lastDragX) * YAW_PER_PIXEL;
            this.pitchTarget = Mth.clamp(
                    this.pitchTarget - (float) (mouseY - this.lastDragY) * PITCH_PER_PIXEL,
                    -PITCH_LIMIT, PITCH_LIMIT);
            this.lastDragX = mouseX;
            this.lastDragY = mouseY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.dragging && button == 0) {
            // 只松手、不收速度：角速度留在 stepRotation 里，机体带着它继续滑一段。
            this.dragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /**
     * 切换一块板子。
     *
     * <p>先在本地的这一份上算一遍，玩家点下去立刻看到结果；然后只把"翻哪一位、要开还是要关"
     * 发出去，由服务端用同一套规则再算一遍。两条路都走 {@link DroneModules#pruneUnsatisfied}，
     * 所以关掉一个前置时它的下游会一起暗下去，而不是留下一个跑不起来的配装。
     */
    private void toggle(ItemStack stack, int flag) {
        int installed = DroneModules.of(stack);
        int active = DroneModules.activeOf(stack);
        boolean wanted = !DroneModules.has(active, flag);
        if (wanted && !DroneModules.isSatisfied(active | flag, flag)) {
            return;
        }
        if (wanted) {
            active |= flag;
        } else {
            active &= ~flag;
        }
        active = DroneModules.pruneUnsatisfied(active);
        DroneModules.setOff(stack, installed & ~active);
        PacketDistributor.sendToServer(
                new DroneLoadoutPayload(this.hand == InteractionHand.OFF_HAND, flag, wanted));
    }
}
