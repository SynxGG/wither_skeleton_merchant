package witherskeletonmerchant.trade;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One JSON-defined merchant offer.
 *
 * JSON field names intentionally match the user-facing configuration format.
 * Gson fills this class directly, then validate() rejects invalid definitions
 * before they can reach an entity.
 */
public final class TradeDefinition {
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9_.-]+");

    private String id;
    private Boolean enabled;
    private Boolean guaranteed;
    private Integer weight;
    private StackDefinition offer;
    private DemandDefinition demand;
    private Integer max_uses;
    private Long cooldown_ticks;
    private Boolean restock;
    private Integer merchant_xp;
    private Float price_multiplier;

    public String getId() {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public boolean isGuaranteed() {
        return guaranteed != null && guaranteed;
    }

    public int getWeight() {
        return weight == null ? 1 : weight;
    }

    public int getConfiguredMaxUses() {
        return max_uses == null ? 1 : max_uses;
    }

    public int getEffectiveMaxUses() {
        return getConfiguredMaxUses() == -1 ? Integer.MAX_VALUE : getConfiguredMaxUses();
    }

    public long getCooldownTicks() {
        return cooldown_ticks == null ? 0L : cooldown_ticks;
    }

    /**
     * Missing field keeps the pre-v0.2.5 behavior: the offer restocks.
     */
    public boolean shouldRestock() {
        return restock == null || restock;
    }

    public int getMerchantXp() {
        return merchant_xp == null ? 0 : merchant_xp;
    }

    public float getPriceMultiplier() {
        return price_multiplier == null ? 0.0F : price_multiplier;
    }

    public List<String> validate() {
        List<String> errors = new ArrayList<>();

        String normalizedId = getId();
        if (normalizedId.isEmpty()) {
            errors.add("missing field 'id'");
        } else if (!SAFE_ID.matcher(normalizedId).matches()) {
            errors.add("id must match [a-z0-9_.-]+: " + normalizedId);
        }

        if (offer == null) {
            errors.add("missing object 'offer'");
        } else {
            validateStack("offer", offer, errors);
        }

        if (demand == null) {
            errors.add("missing object 'demand'");
        } else {
            if (demand.slot_1 == null) {
                errors.add("missing object 'demand.slot_1'");
            } else {
                validateStack("demand.slot_1", demand.slot_1, errors);
            }
            if (demand.slot_2 != null) {
                validateStack("demand.slot_2", demand.slot_2, errors);
            }
        }

        int maxUses = getConfiguredMaxUses();
        if (maxUses != -1 && maxUses < 1) {
            errors.add("max_uses must be -1 or at least 1");
        }
        if (getCooldownTicks() < 0L) {
            errors.add("cooldown_ticks must be 0 or greater");
        }
        if (getWeight() < 0 || getWeight() > 1_000_000) {
            errors.add("weight must be between 0 and 1000000");
        }
        if (getMerchantXp() < 0) {
            errors.add("merchant_xp must be 0 or greater");
        }
        if (!Float.isFinite(getPriceMultiplier()) || getPriceMultiplier() < 0.0F) {
            errors.add("price_multiplier must be a finite number >= 0");
        }

        return errors;
    }

    public MerchantOffer createMerchantOffer() {
        ItemStack costA = demand.slot_1.createStack();
        ItemStack costB = demand.slot_2 == null ? ItemStack.EMPTY : demand.slot_2.createStack();
        ItemStack result = offer.createStack();

        return new MerchantOffer(
            costA,
            costB,
            result,
            0,
            getEffectiveMaxUses(),
            getMerchantXp(),
            getPriceMultiplier()
        );
    }

    private static void validateStack(String path, StackDefinition definition, List<String> errors) {
        if (definition.item == null || definition.item.isBlank()) {
            errors.add("missing field '" + path + ".item'");
            return;
        }

        ResourceLocation itemId = ResourceLocation.tryParse(definition.item.trim());
        if (itemId == null) {
            errors.add("invalid item id in '" + path + ".item': " + definition.item);
            return;
        }
        if (!ForgeRegistries.ITEMS.containsKey(itemId)) {
            errors.add("unknown item in '" + path + ".item': " + itemId);
            return;
        }

        Item item = ForgeRegistries.ITEMS.getValue(itemId);
        if (item == null || item == Items.AIR) {
            errors.add("item resolves to air in '" + path + ".item': " + itemId);
            return;
        }

        int quantity = definition.getQuantity();
        int maxStackSize = new ItemStack(item).getMaxStackSize();
        if (quantity < 1 || quantity > maxStackSize) {
            errors.add(path + ".quantity must be between 1 and " + maxStackSize + " for " + itemId);
        }
    }

    public static final class DemandDefinition {
        private StackDefinition slot_1;
        private StackDefinition slot_2;
    }

    public static final class StackDefinition {
        private String item;
        private Integer quantity;

        public int getQuantity() {
            return quantity == null ? 1 : quantity;
        }

        public ItemStack createStack() {
            ResourceLocation itemId = ResourceLocation.tryParse(item.trim());
            Item resolved = itemId == null ? Items.AIR : ForgeRegistries.ITEMS.getValue(itemId);
            return resolved == null ? ItemStack.EMPTY : new ItemStack(resolved, getQuantity());
        }
    }
}
