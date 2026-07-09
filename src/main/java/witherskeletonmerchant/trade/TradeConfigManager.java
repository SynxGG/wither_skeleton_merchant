package witherskeletonmerchant.trade;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import witherskeletonmerchant.WitherSkeletonMerchantMod;

import net.minecraft.util.RandomSource;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Loads standalone JSON files from:
 * config/wither_skeleton_merchant/general.json
 * config/wither_skeleton_merchant/trades/*.json
 *
 * Reloads are atomic: entities always see either the old complete snapshot or
 * the new complete snapshot, never a half-loaded directory.
 */
public final class TradeConfigManager {
    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private static final Path ROOT = FMLPaths.CONFIGDIR.get().resolve("wither_skeleton_merchant");
    private static final Path GENERAL_FILE = ROOT.resolve("general.json");
    private static final Path TRADES_DIR = ROOT.resolve("trades");

    private static final String DEFAULT_GENERAL = """
        {
          "schema_version": 1,
          "selection_mode": "all",
          "offers_per_merchant": -1,
          "reroll_loaded_merchants_on_reload": true,
          "preserve_cooldowns_on_reload": true,
          "log_loaded_trades": true
        }
        """;

    private static final String DEFAULT_WITHER_SKULL_TRADE = """
        {
          "id": "wither_skull",
          "enabled": true,
          "guaranteed": true,
          "weight": 10,
          "offer": {
            "item": "minecraft:wither_skeleton_skull",
            "quantity": 1
          },
          "demand": {
            "slot_1": {
              "item": "minecraft:diamond",
              "quantity": 2
            },
            "slot_2": {
              "item": "minecraft:emerald",
              "quantity": 1
            }
          },
          "max_uses": 1,
          "cooldown_ticks": 72000,
          "restock": true,
          "merchant_xp": 0,
          "price_multiplier": 0.0
        }
        """;

    private static volatile Snapshot snapshot = Snapshot.empty();
    private static volatile boolean loaded;

    private TradeConfigManager() {
    }

    public static void ensureLoaded() {
        if (!loaded) {
            reload();
        }
    }

    public static synchronized ReloadResult reload() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        try {
            createDefaultsIfMissing();
        } catch (IOException exception) {
            errors.add("Unable to create config directory/defaults: " + exception.getMessage());
        }

        GeneralConfig general = loadGeneral(errors);
        Map<String, TradeDefinition> byId = new LinkedHashMap<>();
        int filesRead = 0;

        if (Files.isDirectory(TRADES_DIR)) {
            try (Stream<Path> stream = Files.list(TRADES_DIR)) {
                List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();

                for (Path file : files) {
                    filesRead++;
                    TradeDefinition definition = loadTrade(file, errors);
                    if (definition == null) {
                        continue;
                    }

                    List<String> validationErrors = definition.validate();
                    if (!validationErrors.isEmpty()) {
                        for (String error : validationErrors) {
                            errors.add(file.getFileName() + ": " + error);
                        }
                        continue;
                    }

                    String id = definition.getId();
                    if (byId.containsKey(id)) {
                        errors.add(file.getFileName() + ": duplicate trade id '" + id + "'");
                        continue;
                    }
                    byId.put(id, definition);
                }
            } catch (IOException exception) {
                errors.add("Unable to list trades directory: " + exception.getMessage());
            }
        }

        List<TradeDefinition> all = new ArrayList<>(byId.values());
        all.sort(Comparator.comparing(TradeDefinition::getId));

        List<TradeDefinition> enabled = all.stream()
            .filter(TradeDefinition::isEnabled)
            .toList();

        if (enabled.isEmpty()) {
            warnings.add("No enabled trade is currently available. The merchant GUI will have no offers.");
        }
        if ("weighted".equals(general.getSelectionMode())
            && enabled.stream().noneMatch(TradeDefinition::isGuaranteed)
            && enabled.stream().noneMatch(definition -> definition.getWeight() > 0)) {
            warnings.add("Weighted mode has no guaranteed trade and every enabled weight is 0.");
        }

        for (TradeDefinition definition : enabled) {
            if (!definition.shouldRestock() && definition.getCooldownTicks() > 0L) {
                warnings.add(
                    "Trade '" + definition.getId()
                        + "': cooldown_ticks is ignored because restock is false."
                );
            }
            if (!definition.shouldRestock() && definition.getConfiguredMaxUses() == -1) {
                warnings.add(
                    "Trade '" + definition.getId()
                        + "': restock=false has no effect while max_uses is -1."
                );
            }
        }

        snapshot = new Snapshot(
            general,
            List.copyOf(all),
            List.copyOf(enabled),
            Collections.unmodifiableMap(new HashMap<>(byId))
        );
        loaded = true;

        for (String error : errors) {
            WitherSkeletonMerchantMod.LOGGER.error("[WSM trades] {}", error);
        }
        for (String warning : warnings) {
            WitherSkeletonMerchantMod.LOGGER.warn("[WSM trades] {}", warning);
        }
        if (general.shouldLogLoadedTrades()) {
            WitherSkeletonMerchantMod.LOGGER.info(
                "[WSM trades] Loaded {} valid JSON trade(s), {} enabled, {} error(s), {} warning(s)",
                all.size(), enabled.size(), errors.size(), warnings.size()
            );
        }

        return new ReloadResult(filesRead, all.size(), enabled.size(), errors, warnings);
    }

    public static GeneralConfig getGeneral() {
        ensureLoaded();
        return snapshot.general;
    }

    public static TradeDefinition getById(String id) {
        ensureLoaded();
        return snapshot.byId.get(id);
    }

    /**
     * Immutable snapshot of every enabled and validated definition.
     * Exposed so an entity can recover if weighted selection unexpectedly
     * returns no result while valid definitions still exist.
     */
    public static List<TradeDefinition> getEnabledTrades() {
        ensureLoaded();
        return snapshot.enabled;
    }

    public static int getEnabledTradeCount() {
        ensureLoaded();
        return snapshot.enabled.size();
    }

    public static Path getConfigRoot() {
        return ROOT;
    }

    public static List<TradeDefinition> selectOffers(RandomSource random) {
        ensureLoaded();
        GeneralConfig general = snapshot.general;
        List<TradeDefinition> enabled = snapshot.enabled;

        if (enabled.isEmpty()) {
            return List.of();
        }
        if ("all".equals(general.getSelectionMode()) || general.getOffersPerMerchant() == -1) {
            return enabled;
        }

        int target = general.getOffersPerMerchant();
        List<TradeDefinition> selected = new ArrayList<>();
        List<TradeDefinition> candidates = new ArrayList<>();

        for (TradeDefinition definition : enabled) {
            if (definition.isGuaranteed()) {
                selected.add(definition);
            } else if (definition.getWeight() > 0) {
                candidates.add(definition);
            }
        }

        while (selected.size() < target && !candidates.isEmpty()) {
            long totalWeight = 0L;
            for (TradeDefinition candidate : candidates) {
                totalWeight += candidate.getWeight();
            }
            if (totalWeight <= 0L) {
                break;
            }

            long roll = Math.floorMod(random.nextLong(), totalWeight);
            int selectedIndex = candidates.size() - 1;
            long cursor = 0L;
            for (int index = 0; index < candidates.size(); index++) {
                cursor += candidates.get(index).getWeight();
                if (roll < cursor) {
                    selectedIndex = index;
                    break;
                }
            }
            selected.add(candidates.remove(selectedIndex));
        }

        if (selected.isEmpty() && !enabled.isEmpty()) {
            WitherSkeletonMerchantMod.LOGGER.error(
                "[WSM trades] Selection produced 0 offer(s) despite {} enabled definition(s). "
                    + "Falling back to all enabled definitions. mode={}, offers_per_merchant={}",
                enabled.size(),
                general.getSelectionMode(),
                general.getOffersPerMerchant()
            );
            return enabled;
        }

        return List.copyOf(selected);
    }

    private static GeneralConfig loadGeneral(List<String> errors) {
        try (Reader reader = Files.newBufferedReader(GENERAL_FILE, StandardCharsets.UTF_8)) {
            GeneralConfig config = GSON.fromJson(reader, GeneralConfig.class);
            if (config == null) {
                errors.add("general.json is empty");
                return new GeneralConfig();
            }
            List<String> validation = config.validate();
            if (!validation.isEmpty()) {
                for (String error : validation) {
                    errors.add("general.json: " + error);
                }
                return new GeneralConfig();
            }
            return config;
        } catch (IOException | JsonParseException exception) {
            errors.add("Unable to parse general.json: " + exception.getMessage());
            return new GeneralConfig();
        }
    }

    private static TradeDefinition loadTrade(Path file, List<String> errors) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            TradeDefinition definition = GSON.fromJson(reader, TradeDefinition.class);
            if (definition == null) {
                errors.add(file.getFileName() + ": file is empty");
            }
            return definition;
        } catch (IOException | JsonParseException exception) {
            errors.add(file.getFileName() + ": " + exception.getMessage());
            return null;
        }
    }

    private static void createDefaultsIfMissing() throws IOException {
        Files.createDirectories(TRADES_DIR);
        writeIfMissing(GENERAL_FILE, DEFAULT_GENERAL);
        writeIfMissing(TRADES_DIR.resolve("wither_skull.json"), DEFAULT_WITHER_SKULL_TRADE);
    }

    private static void writeIfMissing(Path path, String content) throws IOException {
        if (!Files.exists(path)) {
            Files.writeString(
                path,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            );
        }
    }

    private record Snapshot(
        GeneralConfig general,
        List<TradeDefinition> all,
        List<TradeDefinition> enabled,
        Map<String, TradeDefinition> byId
    ) {
        private static Snapshot empty() {
            return new Snapshot(new GeneralConfig(), List.of(), List.of(), Map.of());
        }
    }

    public static final class GeneralConfig {
        private Integer schema_version;
        private String selection_mode;
        private Integer offers_per_merchant;
        private Boolean reroll_loaded_merchants_on_reload;
        private Boolean preserve_cooldowns_on_reload;
        private Boolean log_loaded_trades;

        public int getSchemaVersion() {
            return schema_version == null ? 1 : schema_version;
        }

        public String getSelectionMode() {
            return selection_mode == null ? "all" : selection_mode.trim().toLowerCase(Locale.ROOT);
        }

        public int getOffersPerMerchant() {
            return offers_per_merchant == null ? -1 : offers_per_merchant;
        }

        public boolean shouldRerollLoadedMerchantsOnReload() {
            return reroll_loaded_merchants_on_reload == null || reroll_loaded_merchants_on_reload;
        }

        public boolean shouldPreserveCooldownsOnReload() {
            return preserve_cooldowns_on_reload == null || preserve_cooldowns_on_reload;
        }

        public boolean shouldLogLoadedTrades() {
            return log_loaded_trades == null || log_loaded_trades;
        }

        private List<String> validate() {
            List<String> errors = new ArrayList<>();
            if (getSchemaVersion() != 1) {
                errors.add("schema_version must currently be 1");
            }
            if (!"all".equals(getSelectionMode()) && !"weighted".equals(getSelectionMode())) {
                errors.add("selection_mode must be 'all' or 'weighted'");
            }
            int offers = getOffersPerMerchant();
            if (offers != -1 && offers < 1) {
                errors.add("offers_per_merchant must be -1 or at least 1");
            }
            return errors;
        }
    }

    public static final class ReloadResult {
        private final int filesRead;
        private final int validTrades;
        private final int enabledTrades;
        private final List<String> errors;
        private final List<String> warnings;

        private ReloadResult(
            int filesRead,
            int validTrades,
            int enabledTrades,
            List<String> errors,
            List<String> warnings
        ) {
            this.filesRead = filesRead;
            this.validTrades = validTrades;
            this.enabledTrades = enabledTrades;
            this.errors = List.copyOf(errors);
            this.warnings = List.copyOf(warnings);
        }

        public int getFilesRead() {
            return filesRead;
        }

        public int getValidTrades() {
            return validTrades;
        }

        public int getEnabledTrades() {
            return enabledTrades;
        }

        public List<String> getErrors() {
            return errors;
        }

        public List<String> getWarnings() {
            return warnings;
        }
    }
}
