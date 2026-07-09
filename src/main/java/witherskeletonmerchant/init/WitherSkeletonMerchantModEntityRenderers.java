/*
 *    MCreator note: This file will be REGENERATED on each build.
 */
package witherskeletonmerchant.init;

import witherskeletonmerchant.client.renderer.WitherSkeletonMerchantRenderer;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.api.distmarker.Dist;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class WitherSkeletonMerchantModEntityRenderers {
	@SubscribeEvent
	public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
		event.registerEntityRenderer(WitherSkeletonMerchantModEntities.WITHER_SKELETON_MERCHANT.get(), WitherSkeletonMerchantRenderer::new);
	}
}