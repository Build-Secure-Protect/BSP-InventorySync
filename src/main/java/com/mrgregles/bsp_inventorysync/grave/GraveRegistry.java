package com.mrgregles.bsp_inventorysync.grave;

import com.mrgregles.bsp_inventorysync.BSPInventorySync;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** The grave block and its block entity. No item: a grave only ever comes from a death. */
public final class GraveRegistry {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, BSPInventorySync.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, BSPInventorySync.MODID);

    public static final RegistryObject<GraveBlock> GRAVE = BLOCKS.register("grave", GraveBlock::new);
    @SuppressWarnings("DataFlowIssue") // a block entity type with no data fixer, as every mod's
    public static final RegistryObject<BlockEntityType<GraveBlockEntity>> GRAVE_ENTITY = BLOCK_ENTITIES.register("grave",
            () -> BlockEntityType.Builder.of(GraveBlockEntity::new, GRAVE.get()).build(null));

    private GraveRegistry() {}

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }
}
