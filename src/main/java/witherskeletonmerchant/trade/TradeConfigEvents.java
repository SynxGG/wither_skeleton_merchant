package witherskeletonmerchant.trade;

import com.mojang.brigadier.CommandDispatcher;
import witherskeletonmerchant.WitherSkeletonMerchantMod;
import witherskeletonmerchant.entity.WitherSkeletonMerchantEntity;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge-bus bootstrap and operator commands.
 * No edit to the MCreator-generated main mod class is required.
 */
@Mod.EventBusSubscriber(
    modid = WitherSkeletonMerchantMod.MODID,
    bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class TradeConfigEvents {
    private TradeConfigEvents() {
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        TradeConfigManager.reload();
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        registerCommands(event.getDispatcher());
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("wsm")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("reload")
                    .executes(context -> reload(context.getSource())))
                .then(Commands.literal("refresh")
                    .executes(context -> refresh(context.getSource(), true)))
                .then(Commands.literal("status")
                    .executes(context -> status(context.getSource())))
                .then(Commands.literal("diagnose")
                    .executes(context -> diagnose(context.getSource())))
        );
    }

    private static int reload(CommandSourceStack source) {
        TradeConfigManager.ReloadResult result = TradeConfigManager.reload();
        TradeConfigManager.GeneralConfig general = TradeConfigManager.getGeneral();

        int refreshed = 0;
        if (general.shouldRerollLoadedMerchantsOnReload()) {
            refreshed = refreshLoadedMerchants(
                source.getServer(),
                general.shouldPreserveCooldownsOnReload()
            );
        }

        int finalRefreshed = refreshed;
        source.sendSuccess(
            () -> Component.literal(
                "WSM: " + result.getEnabledTrades() + " enabled trade(s), "
                    + result.getErrors().size() + " error(s), "
                    + result.getWarnings().size() + " warning(s), "
                    + finalRefreshed + " loaded merchant(s) queued/refreshed."
            ),
            true
        );

        if (!result.getErrors().isEmpty()) {
            source.sendFailure(Component.literal(
                "WSM: some JSON files were rejected. Check latest.log for exact paths and reasons."
            ));
        }
        return result.getErrors().isEmpty() ? 1 : 0;
    }

    private static int refresh(CommandSourceStack source, boolean preserveCooldowns) {
        int refreshed = refreshLoadedMerchants(source.getServer(), preserveCooldowns);
        source.sendSuccess(
            () -> Component.literal(
                "WSM: refreshed or queued " + refreshed + " loaded merchant(s)."
            ),
            true
        );
        return refreshed;
    }

    private static int status(CommandSourceStack source) {
        TradeConfigManager.ensureLoaded();
        TradeConfigManager.GeneralConfig general = TradeConfigManager.getGeneral();
        source.sendSuccess(
            () -> Component.literal(
                "WSM: selection_mode=" + general.getSelectionMode()
                    + ", offers_per_merchant=" + general.getOffersPerMerchant()
                    + ". Use /wsm reload after editing JSON."
            ),
            false
        );
        return 1;
    }

    private static int diagnose(CommandSourceStack source) {
        TradeConfigManager.ensureLoaded();

        int merchants = 0;
        int selected = 0;
        int built = 0;
        int effective = 0;

        for (ServerLevel level : source.getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof WitherSkeletonMerchantEntity merchant) {
                    merchants++;
                    selected += merchant.getLastSelectedDefinitionCount();
                    built += merchant.getLastBuiltOfferCount();
                    effective += merchant.getEffectiveOfferCount();
                }
            }
        }

        int finalMerchants = merchants;
        int finalSelected = selected;
        int finalBuilt = built;
        int finalEffective = effective;

        source.sendSuccess(
            () -> Component.literal(
                "WSM diagnose: config=" + TradeConfigManager.getConfigRoot()
                    + ", enabled=" + TradeConfigManager.getEnabledTradeCount()
                    + ", loaded_merchants=" + finalMerchants
                    + ", selected=" + finalSelected
                    + ", built=" + finalBuilt
                    + ", effective=" + finalEffective
            ),
            false
        );
        return 1;
    }

    private static int refreshLoadedMerchants(MinecraftServer server, boolean preserveCooldowns) {
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof WitherSkeletonMerchantEntity merchant) {
                    merchant.requestTradeConfigReload(preserveCooldowns);
                    count++;
                }
            }
        }
        return count;
    }
}
