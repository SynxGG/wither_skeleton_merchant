package witherskeletonmerchant.client.renderer;

import witherskeletonmerchant.entity.WitherSkeletonMerchantEntity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.HumanoidModel;

public class WitherSkeletonMerchantRenderer extends HumanoidMobRenderer<WitherSkeletonMerchantEntity, HumanoidModel<WitherSkeletonMerchantEntity>> {
	private final ResourceLocation entityTexture = new ResourceLocation("wither_skeleton_merchant:textures/entities/wither_skeleton.png");

	public WitherSkeletonMerchantRenderer(EntityRendererProvider.Context context) {
		super(context, new HumanoidModel<WitherSkeletonMerchantEntity>(context.bakeLayer(ModelLayers.PLAYER)), 0.5f);
		this.addLayer(new HumanoidArmorLayer(this, new HumanoidModel(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)), new HumanoidModel(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)), context.getModelManager()));
	}

	@Override
	public ResourceLocation getTextureLocation(WitherSkeletonMerchantEntity entity) {
		return entityTexture;
	}
}