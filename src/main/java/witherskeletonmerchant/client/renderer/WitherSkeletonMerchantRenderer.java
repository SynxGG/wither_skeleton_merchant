package witherskeletonmerchant.client.renderer;

import witherskeletonmerchant.entity.WitherSkeletonMerchantEntity;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * Temporary Stage 1 renderer.
 *
 * It uses the vanilla Wither Skeleton baked layer so the trading code can be
 * validated before the custom Blockbench model is integrated.
 */
public class WitherSkeletonMerchantRenderer
    extends MobRenderer<WitherSkeletonMerchantEntity, HumanoidModel<WitherSkeletonMerchantEntity>> {

    private static final ResourceLocation TEXTURE = new ResourceLocation(
        "wither_skeleton_merchant",
        "textures/entities/wither_skeleton.png"
    );

    public WitherSkeletonMerchantRenderer(EntityRendererProvider.Context context) {
        super(
            context,
            new HumanoidModel<>(context.bakeLayer(ModelLayers.WITHER_SKELETON)),
            0.5F
        );
    }

    @Override
    public ResourceLocation getTextureLocation(WitherSkeletonMerchantEntity entity) {
        return TEXTURE;
    }
}
