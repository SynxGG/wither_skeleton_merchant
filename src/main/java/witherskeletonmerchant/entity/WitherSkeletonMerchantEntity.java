package witherskeletonmerchant.entity;

import witherskeletonmerchant.init.WitherSkeletonMerchantModEntities;
import witherskeletonmerchant.trade.TradeConfigManager;
import witherskeletonmerchant.trade.TradeDefinition;

import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.PlayMessages;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 2: vanilla merchant GUI backed by standalone JSON trade definitions.
 *
 * Cooldowns are per entity and per trade id. They use world game time, so they
 * keep progressing while the merchant's chunk is unloaded and survive server
 * restarts.
 */
public class WitherSkeletonMerchantEntity extends WanderingTrader {
    private static final String NBT_TRADE_IDS = "WSMTradeIds";
    private static final String NBT_COOLDOWN_ENDS = "WSMCooldownEnds";
    private static final String NBT_COOLDOWN_DURATIONS = "WSMCooldownDurations";

    private final List<String> activeTradeIds = new ArrayList<>();
    private final Map<String, Long> cooldownEnds = new HashMap<>();
    private final Map<String, Long> cooldownDurations = new HashMap<>();

    private boolean pendingTradeConfigReload;
    private boolean pendingPreserveCooldowns = true;
    private boolean needsLegacyTradeMigration;

    public WitherSkeletonMerchantEntity(PlayMessages.SpawnEntity packet, Level level) {
        this(WitherSkeletonMerchantModEntities.WITHER_SKELETON_MERCHANT.get(), level);
    }

    public WitherSkeletonMerchantEntity(EntityType<? extends WanderingTrader> entityType, Level level) {
        super(entityType, level);
        this.setMaxUpStep(0.6F);
        this.xpReward = 0;
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    @Override
    public MobType getMobType() {
        return MobType.UNDEAD;
    }

    /**
     * /summon and some entity-loading paths can deserialize an empty
     * MerchantOffers object before the first interaction. Because the field is
     * then non-null, AbstractVillager#getOffers() does not call updateTrades().
     * Populate the JSON offers explicitly as soon as the server entity enters
     * the world.
     */
    @Override
    public void onAddedToWorld() {
        super.onAddedToWorld();
        if (!this.level().isClientSide) {
            ensureConfiguredOffers();
        }
    }

    @Override
    protected void updateTrades() {
        rebuildTradesFromConfig(false);
    }

    /**
     * Keeps vanilla Wandering Trader interaction, while making an empty or
     * invalid JSON configuration visible instead of silently doing nothing.
     */
    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!this.level().isClientSide) {
            ensureConfiguredOffers();

            if (this.getOffers().isEmpty()) {
                player.sendSystemMessage(Component.literal(
                    "Wither Skeleton Merchant: the JSON loader returned no usable offer. "
                        + "Check config/wither_skeleton_merchant/trades and latest.log."
                ));
                return InteractionResult.CONSUME;
            }
        }
        return super.mobInteract(player, hand);
    }

    @Override
    public void notifyTrade(MerchantOffer offer) {
        super.notifyTrade(offer);
        if (!this.level().isClientSide && offer.isOutOfStock()) {
            scheduleCooldownFor(offer);
        }
    }

    @Override
    public void aiStep() {
        super.aiStep();

        if (this.level().isClientSide) {
            return;
        }

        if (needsLegacyTradeMigration && !this.isTrading()) {
            needsLegacyTradeMigration = false;
            rebuildTradesFromConfig(false);
        }

        if (pendingTradeConfigReload && !this.isTrading()) {
            boolean preserve = pendingPreserveCooldowns;
            pendingTradeConfigReload = false;
            pendingPreserveCooldowns = true;
            rebuildTradesFromConfig(preserve);
        }

        if (this.tickCount % 20 == 0) {
            updateCooldowns();
        }
    }

    /**
     * Called by /wsm reload and /wsm refresh.
     * Active trade screens are not mutated in-place; the refresh is applied as
     * soon as the current player closes the menu.
     */
    public void requestTradeConfigReload(boolean preserveCooldowns) {
        if (this.level().isClientSide) {
            return;
        }
        if (this.isTrading()) {
            pendingTradeConfigReload = true;
            pendingPreserveCooldowns = preserveCooldowns;
        } else {
            rebuildTradesFromConfig(preserveCooldowns);
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);

        ListTag ids = new ListTag();
        for (String id : activeTradeIds) {
            ids.add(StringTag.valueOf(id));
        }
        tag.put(NBT_TRADE_IDS, ids);

        CompoundTag ends = new CompoundTag();
        for (Map.Entry<String, Long> entry : cooldownEnds.entrySet()) {
            ends.putLong(entry.getKey(), entry.getValue());
        }
        tag.put(NBT_COOLDOWN_ENDS, ends);

        CompoundTag durations = new CompoundTag();
        for (Map.Entry<String, Long> entry : cooldownDurations.entrySet()) {
            durations.putLong(entry.getKey(), entry.getValue());
        }
        tag.put(NBT_COOLDOWN_DURATIONS, durations);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);

        activeTradeIds.clear();
        cooldownEnds.clear();
        cooldownDurations.clear();

        if (tag.contains(NBT_TRADE_IDS, Tag.TAG_LIST)) {
            ListTag ids = tag.getList(NBT_TRADE_IDS, Tag.TAG_STRING);
            for (int index = 0; index < ids.size(); index++) {
                activeTradeIds.add(ids.getString(index));
            }
        }

        if (tag.contains(NBT_COOLDOWN_ENDS, Tag.TAG_COMPOUND)) {
            CompoundTag ends = tag.getCompound(NBT_COOLDOWN_ENDS);
            for (String id : ends.getAllKeys()) {
                cooldownEnds.put(id, ends.getLong(id));
            }
        }

        if (tag.contains(NBT_COOLDOWN_DURATIONS, Tag.TAG_COMPOUND)) {
            CompoundTag durations = tag.getCompound(NBT_COOLDOWN_DURATIONS);
            for (String id : durations.getAllKeys()) {
                cooldownDurations.put(id, durations.getLong(id));
            }
        }

        // AbstractVillager can deserialize an empty but non-null offers list.
        // In that case getOffers() will no longer call updateTrades(), so an
        // empty/empty comparison must still request a migration.
        MerchantOffers loadedOffers = this.offers;
        needsLegacyTradeMigration =
            loadedOffers == null
                || loadedOffers.isEmpty()
                || activeTradeIds.size() != loadedOffers.size();
    }

    private void ensureConfiguredOffers() {
        TradeConfigManager.ensureLoaded();

        if (this.offers == null || this.offers.isEmpty()) {
            rebuildTradesFromConfig(true);
        }
    }

    private void rebuildTradesFromConfig(boolean preserveCooldowns) {
        TradeConfigManager.ensureLoaded();

        if (!preserveCooldowns) {
            cooldownEnds.clear();
        }

        List<TradeDefinition> selected = TradeConfigManager.selectOffers(this.getRandom());
        MerchantOffers rebuilt = new MerchantOffers();
        activeTradeIds.clear();
        cooldownDurations.clear();

        long now = this.level().getGameTime();
        for (TradeDefinition definition : selected) {
            String id = definition.getId();
            MerchantOffer offer = definition.createMerchantOffer();

            activeTradeIds.add(id);
            cooldownDurations.put(id, definition.getCooldownTicks());

            Long cooldownEnd = cooldownEnds.get(id);
            if (cooldownEnd != null) {
                if (cooldownEnd > now) {
                    offer.setToOutOfStock();
                } else {
                    cooldownEnds.remove(id);
                }
            }

            rebuilt.add(offer);
        }

        this.overrideOffers(rebuilt);
    }

    private void scheduleCooldownFor(MerchantOffer offer) {
        int index = this.getOffers().indexOf(offer);
        if (index < 0 || index >= activeTradeIds.size()) {
            return;
        }

        String id = activeTradeIds.get(index);
        long duration = getCooldownDuration(id);

        if (duration <= 0L) {
            offer.resetUses();
            cooldownEnds.remove(id);
            return;
        }

        cooldownEnds.putIfAbsent(id, this.level().getGameTime() + duration);
    }

    private void updateCooldowns() {
        MerchantOffers offers = this.getOffers();
        long now = this.level().getGameTime();
        int count = Math.min(offers.size(), activeTradeIds.size());

        for (int index = 0; index < count; index++) {
            MerchantOffer offer = offers.get(index);
            String id = activeTradeIds.get(index);

            if (!offer.isOutOfStock()) {
                continue;
            }

            long duration = getCooldownDuration(id);
            if (duration <= 0L) {
                offer.resetUses();
                cooldownEnds.remove(id);
                continue;
            }

            long end = cooldownEnds.computeIfAbsent(id, ignored -> now + duration);
            if (now >= end) {
                offer.resetUses();
                cooldownEnds.remove(id);
            }
        }
    }

    private long getCooldownDuration(String id) {
        Long saved = cooldownDurations.get(id);
        if (saved != null) {
            return saved;
        }

        TradeDefinition current = TradeConfigManager.getById(id);
        long duration = current == null ? 0L : current.getCooldownTicks();
        cooldownDurations.put(id, duration);
        return duration;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.WITHER_SKELETON_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource damageSource) {
        return SoundEvents.WITHER_SKELETON_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.WITHER_SKELETON_DEATH;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.WITHER_SKELETON_STEP, 0.15F, 1.0F);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.is(DamageTypes.IN_FIRE) || source.is(DamageTypes.ON_FIRE)) {
            return false;
        }
        return super.hurt(source, amount);
    }

    public static void init() {
        SpawnPlacements.register(
            WitherSkeletonMerchantModEntities.WITHER_SKELETON_MERCHANT.get(),
            SpawnPlacements.Type.ON_GROUND,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            (entityType, level, reason, pos, random) ->
                level.getDifficulty() != Difficulty.PEACEFUL
                    && Monster.isDarkEnoughToSpawn(level, pos, random)
                    && Mob.checkMobSpawnRules(entityType, level, reason, pos, random)
        );
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MOVEMENT_SPEED, 0.30D)
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.ARMOR, 0.0D)
            .add(Attributes.ATTACK_DAMAGE, 3.0D)
            .add(Attributes.FOLLOW_RANGE, 16.0D);
    }
}
