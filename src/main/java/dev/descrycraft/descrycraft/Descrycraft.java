package dev.descrycraft.descrycraft;

import dev.descrycraft.descrycraft.command.ModCommands;
import dev.descrycraft.descrycraft.config.DescrycraftConfig;
import dev.descrycraft.descrycraft.entity.ReconDroneEntity;
import dev.descrycraft.descrycraft.network.ModNetwork;
import dev.descrycraft.descrycraft.registry.ModBlocks;
import dev.descrycraft.descrycraft.registry.ModCreativeTabs;
import dev.descrycraft.descrycraft.registry.ModDataComponents;
import dev.descrycraft.descrycraft.registry.ModEntities;
import dev.descrycraft.descrycraft.registry.ModItems;
import dev.descrycraft.descrycraft.registry.ModMenus;
import dev.descrycraft.descrycraft.registry.ModSounds;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

@Mod(Descrycraft.MOD_ID)
public final class Descrycraft {
    public static final String MOD_ID = "descrycraft";

    public Descrycraft(IEventBus modBus, ModContainer container) {
        // 通用配置放平衡值，客户端配置放手感。CLIENT 那份只在客户端注册：专用服务器上没有
        // 这些参数可读，注册了也只是白建一份文件。
        container.registerConfig(ModConfig.Type.COMMON, DescrycraftConfig.COMMON_SPEC);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            container.registerConfig(ModConfig.Type.CLIENT, DescrycraftConfig.CLIENT_SPEC);
        }
        // 配置载入或热重载之后，把值写回各处原有的静态常量镜像。
        // 注册在具体的子类上而不是抽象基类上，避免事件总线对抽象类型的推断问题。
        modBus.addListener(this::onConfigLoading);
        modBus.addListener(this::onConfigReloading);

        // Components come first: the drone's item registration reads its own component type when a
        // stack is built, and the block item below is built from the block registration.
        ModDataComponents.DATA_COMPONENTS.register(modBus);
        ModBlocks.BLOCKS.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModEntities.ENTITY_TYPES.register(modBus);
        ModSounds.SOUNDS.register(modBus);
        ModMenus.MENUS.register(modBus);
        ModCreativeTabs.TABS.register(modBus);
        modBus.addListener(ModNetwork::register);
        NeoForge.EVENT_BUS.addListener(ModCommands::register);
    }

    private void onConfigLoading(ModConfigEvent.Loading event) {
        applyConfig(event);
    }

    private void onConfigReloading(ModConfigEvent.Reloading event) {
        applyConfig(event);
    }

    /**
     * 把本模组的配置值写进各处镜像。
     *
     * <p><b>必须按配置类型分流，这不是优化而是正确性问题。</b>{@code ModConfigEvent} 是
     * 一份配置文件一次事件，而 {@code ConfigTracker#loadConfigs} 遍历注册表的顺序并不保证
     * COMMON 先到 —— 实测客户端上 CLIENT 那份先载入。此时 COMMON 的 {@code ConfigValue} 还是
     * 未载入状态，{@code get()} 会直接抛 {@code IllegalStateException: Cannot get config value
     * before config is loaded}，把整个模组从加载阶段拽下来。所以这里只认自己那一份：
     * COMMON 的事件才写 COMMON 镜像，CLIENT 的交给 {@code DescrycraftClient}。
     *
     * <p>客户端专属的手感与画面参数由 {@code DescrycraftClient} 同步，那个类不能在双端都加载的
     * 这里引用。
     */
    private void applyConfig(ModConfigEvent event) {
        ModConfig config = event.getConfig();
        if (!MOD_ID.equals(config.getModId()) || config.getType() != ModConfig.Type.COMMON) {
            return;
        }
        ReconDroneEntity.applyConfig();
    }
}
