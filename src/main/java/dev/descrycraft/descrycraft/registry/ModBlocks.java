package dev.descrycraft.descrycraft.registry;

import dev.descrycraft.descrycraft.Descrycraft;
import dev.descrycraft.descrycraft.block.ModuleWorkbenchBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Descrycraft.MOD_ID);

    /** The bench the drone is opened up on. Metal, and heavy enough to need a pickaxe. */
    public static final DeferredBlock<ModuleWorkbenchBlock> MODULE_WORKBENCH =
            BLOCKS.register("module_workbench", () -> new ModuleWorkbenchBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .strength(3.5F, 6.0F)
                            .requiresCorrectToolForDrops()
                            .sound(SoundType.METAL)));

    private ModBlocks() {
    }
}
