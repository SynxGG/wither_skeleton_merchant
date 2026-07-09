/*
 *    MCreator note: This file will be REGENERATED on each build.
 */
package witherskeletonmerchant.init;

import witherskeletonmerchant.WitherSkeletonMerchantMod;

import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.common.ForgeSpawnEggItem;

import net.minecraft.world.item.Item;

public class WitherSkeletonMerchantModItems {
	public static final DeferredRegister<Item> REGISTRY = DeferredRegister.create(ForgeRegistries.ITEMS, WitherSkeletonMerchantMod.MODID);
	public static final RegistryObject<Item> WITHER_SKELETON_MERCHANT_SPAWN_EGG;
	static {
		WITHER_SKELETON_MERCHANT_SPAWN_EGG = REGISTRY.register("wither_skeleton_merchant_spawn_egg", () -> new ForgeSpawnEggItem(WitherSkeletonMerchantModEntities.WITHER_SKELETON_MERCHANT, -1, -1, new Item.Properties()));
	}
	// Start of user code block custom items
	// End of user code block custom items
}