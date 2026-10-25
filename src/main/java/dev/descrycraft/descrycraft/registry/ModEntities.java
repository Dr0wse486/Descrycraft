package dev.descrycraft.descrycraft.registry;

import dev.descrycraft.descrycraft.Descrycraft;
import dev.descrycraft.descrycraft.entity.ReconDroneEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Descrycraft.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<ReconDroneEntity>> RECON_DRONE =
            ENTITY_TYPES.register("recon_drone", () -> EntityType.Builder
                    .<ReconDroneEntity>of(ReconDroneEntity::new, MobCategory.MISC)
                    .sized(0.8F, 0.45F)
                    .eyeHeight(0.3F)
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .noSummon()
                    .fireImmune()
                    .build("recon_drone"));

    private ModEntities() {
    }
}
