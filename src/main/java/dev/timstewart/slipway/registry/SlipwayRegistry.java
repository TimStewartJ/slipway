package dev.timstewart.slipway.registry;

import dev.timstewart.slipway.Slipway;
import dev.timstewart.slipway.vessel.HelmBlock;
import dev.timstewart.slipway.vessel.VesselEntity;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

public final class SlipwayRegistry {
	public static final ResourceKey<Block> HELM_BLOCK_KEY = ResourceKey.create(Registries.BLOCK, Slipway.id("helm"));
	public static final ResourceKey<Item> HELM_ITEM_KEY = ResourceKey.create(Registries.ITEM, Slipway.id("helm"));
	public static final ResourceKey<EntityType<?>> VESSEL_KEY = ResourceKey.create(Registries.ENTITY_TYPE, Slipway.id("vessel"));
	/** Blocks that never become part of a vessel (see data/slipway/tags/block/assembly_deny.json). */
	public static final TagKey<Block> ASSEMBLY_DENY = TagKey.create(Registries.BLOCK, Slipway.id("assembly_deny"));

	public static Block HELM;
	public static Item HELM_ITEM;
	public static EntityType<VesselEntity> VESSEL;
	/** Keeps a vessel's plot chunks loaded and ticking while the vessel is active; not persisted. */
	public static TicketType VESSEL_TICKET;

	private SlipwayRegistry() {
	}

	public static void register() {
		HELM = Registry.register(BuiltInRegistries.BLOCK, HELM_BLOCK_KEY, new HelmBlock(BlockBehaviour.Properties.of()
			.setId(HELM_BLOCK_KEY)
			.mapColor(MapColor.WOOD)
			.strength(2.5F)
			.sound(SoundType.WOOD)));
		HELM_ITEM = Registry.register(BuiltInRegistries.ITEM, HELM_ITEM_KEY, new BlockItem(HELM, new Item.Properties()
			.setId(HELM_ITEM_KEY)
			.useBlockDescriptionPrefix()));
		VESSEL = Registry.register(BuiltInRegistries.ENTITY_TYPE, VESSEL_KEY, EntityType.Builder.<VesselEntity>of(VesselEntity::new, MobCategory.MISC)
			.sized(1.0F, 1.0F)
			.clientTrackingRange(16)
			.noUpdateInterval()
			.noSummon()
			.fireImmune()
			.build(VESSEL_KEY));
		VESSEL_TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE, Slipway.id("vessel"),
			new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION));
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(output -> output.accept(HELM_ITEM));
	}
}
