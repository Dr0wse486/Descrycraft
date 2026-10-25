#version 150

// 信号颗粒与引爆雪花的 GPU 版本。
//
// 原先这两套效果都在 DroneHud 里逐颗粒调用 GuiGraphics#fill：信号劣化每帧最多两万多颗，
// 引爆雪花固定六万颗，每颗都是一次独立的矩阵变换和四个顶点提交。雪花屏一触发就卡，
// 噪点越浓越卡，卡的不是 GPU，是 CPU 在往 GUI 顶点缓冲里塞几万个 1×1 四边形。
//
// 现在每一格 GUI 像素只做一次整数哈希：坐标和种子决定它是不是颗粒、灰度多少、透明度多少。
// 全屏只画一次。密度、灰度范围、alpha 范围都和原来的循环逐项对上，所以画面不变，
// 每帧几万次提交变成了一次绘制。
//
// 格子按 GUI 像素切，不是按设备像素：原效果里的"一像素"是 GUI 缩放下的一格，在 4K 屏上
// 本来就是两三个设备像素见方。GuiWidth / GuiHeight 由 Java 侧从 GuiGraphics 取，和
// DroneHud 计算颗粒数用的是同一个坐标系，密度才对得上。
//
// 两种模式只差颜色分布：
//   0 = 信号劣化：稀疏、细小，亮暗各半，alpha 很低；
//   1 = 引爆雪花：密得多，灰度铺满，alpha 很高，种子每帧换一次。
//
// 输出 alpha 恒为 1：链子末尾的 blit 在绘制前会清空 minecraft:main，swap 里任何 alpha < 1
// 的像素都会让清屏色透出来（颗粒本身已经混进 rgb，不需要 alpha 通道携带透明度）。

uniform sampler2D DiffuseSampler;

// 0..1，被点亮的 GUI 像素比例。Java 侧已经按旧的颗粒数换算过（见 DroneNoise）。
uniform float GrainDensity;
// 每 40ms（信号）或每帧（引爆）换一次。只取低 16 位，float 能精确表示。
uniform float GrainSeed;
// 0 信号劣化，1 引爆雪花。
uniform float GrainMode;
uniform float GuiWidth;
uniform float GuiHeight;

in vec2 texCoord;

out vec4 fragColor;

// 32 位整数哈希，PCG 风格的混合。比 sin(dot(...)) 那套稳定：不依赖驱动的三角函数精度，
// 同一个种子在每台机器上都是同一张噪点，也不需要额外的 noise 纹理。
uint hash(uint x) {
    x ^= x >> 16;
    x *= 0x7feb352dU;
    x ^= x >> 15;
    x *= 0x846ca68bU;
    x ^= x >> 16;
    return x;
}

float rand01(uint x) {
    return float(hash(x)) * (1.0 / 4294967295.0);
}

void main() {
    vec4 base = texture(DiffuseSampler, texCoord);
    if (GrainDensity <= 0.0) {
        fragColor = vec4(base.rgb, 1.0);
        return;
    }

    // 当前片元落在哪个 GUI 像素上。乘的是 GUI 尺寸而不是 OutSize，所以一格在屏幕上
    // 正好是一个 GUI 像素（GUI scale 3 时是 3×3 个设备像素），和逐像素 fill 时代一致。
    vec2 cell = floor(texCoord * vec2(GuiWidth, GuiHeight));
    uint key = uint(GrainSeed) * 0x9E3779B9U
             ^ uint(cell.x) * 0x85EBCA6BU
             ^ uint(cell.y) * 0xC2B2AE35U;
    uint h = hash(key);

    // 密度就是"这一格被点亮的概率"。原循环里 amount 决定颗粒数量，不是单颗亮度，
    // 这里保持同一个语义。
    if (float(h) * (1.0 / 4294967295.0) >= GrainDensity) {
        fragColor = vec4(base.rgb, 1.0);
        return;
    }

    // 三次独立哈希，分别决定灰度、alpha 和亮暗。同一个 key 每帧结果固定，换种子才换画面。
    float r1 = rand01(h ^ 0x68E31DA4U);
    float r2 = rand01(h ^ 0xB5297A4DU);
    float r3 = rand01(h ^ 0x1B56C4E9U);

    float alpha;
    vec3 grain;
    if (GrainMode < 0.5) {
        // 信号劣化：alpha 0x16..0x5D，亮灰 0xE4..0xFF 或暗灰 0x0C..0x27，各占一半。
        // 只有亮颗粒会看起来像灰尘，亮暗各半才读作噪点。
        alpha = (22.0 + 72.0 * r2) / 255.0;
        float grey = r1 < 0.5
                ? 228.0 + 27.0 * r3
                : 12.0 + 27.0 * r3;
        grain = vec3(grey / 255.0);
    } else {
        // 引爆雪花：alpha 0x60..0xFF，灰度铺满 0..255。
        alpha = (96.0 + 160.0 * r2) / 255.0;
        grain = vec3(r1);
    }

    // 等价于把 1×1 的颗粒按 SRC_ALPHA / ONE_MINUS_SRC_ALPHA 盖在已经画好的画面上。
    fragColor = vec4(mix(base.rgb, grain, alpha), 1.0);
}
