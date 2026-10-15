package shortestpath.profiles;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import shortestpath.accounts.Account;
import shortestpath.accounts.Diary;
import shortestpath.accounts.PlantedSpiritTree;
import shortestpath.accounts.Poh;
import shortestpath.accounts.Spellbook;
import shortestpath.accounts.Unlock;

/**
 * Loads the corpus repository's {@code account-profiles-v1.json} into {@link Account} objects:
 * each profile's JSON fields map onto {@link Account.Builder} calls, and callers compile the
 * result through {@code AccountCompiler} when they need the compiled client state. The real file
 * lives in the sibling corpus checkout at {@code accounts/account-profiles-v1.json}
 * ({@code formatVersion: 1}); the committed fixture under
 * {@code src/test/resources/profiles/} keeps tests hermetic.
 *
 * <p>The file is not vendored into this repo — the canonical accounts here stay Java-only
 * ({@code CanonicalAccounts}), matching the corpus README's convention. Consumers pass a
 * {@link Path} to wherever their corpus checkout lives; {@link #loadResource(String)} covers the
 * committed fixture so tests do not depend on the sibling checkout's presence.
 *
 * <p>This is a pure data mapping: it knows the JSON field names and which builder call each one
 * feeds, and nothing about what a fact means — compiling facts into varbits and varplayers is
 * {@code AccountCompiler}'s job. The {@code varbits}/{@code varplayers} members are passed
 * through to the builder's raw-variable hooks unchanged.
 *
 * <p>The format is closed: an unknown {@code formatVersion}, a missing {@code profiles} map, and
 * member names or values the v1 shape does not carry all fail with
 * {@link IllegalArgumentException} rather than silently mis-parse.
 */
public final class CorpusAccountProfiles {
    private static final int FORMAT_VERSION = 1;

    private static final Set<String> TOP_LEVEL_KEYS =
        Set.of("benchmarkNowMinutes", "formatVersion", "profiles");
    private static final Set<String> PROFILE_KEYS = Set.of(
        "bank", "completedQuests", "diaries", "equipment", "fairyRingsUnlocked", "inventory",
        "levels", "plantedSpiritTrees", "poh", "runePouch", "runtime", "varbits", "varplayers");
    private static final Set<String> POH_KEYS = Set.of(
        "fairyRing", "jewelleryBox", "location", "mountedDigsite", "mountedGlory",
        "mountedMythical", "mountedXerics", "obelisk", "portals", "spiritTree");
    private static final Set<String> PORTAL_KEYS = Set.of("destinations", "mode");
    private static final Set<String> RUNTIME_KEYS =
        Set.of("arriveInsidePoh", "minigameTeleport", "spellbook");
    private static final Set<String> MINIGAME_TELEPORT_KEYS = Set.of("state", "minutes");

    /** Diary tier names as the JSON spells them. */
    private static final Map<String, Diary.Tier> DIARY_TIERS = Map.of(
        "NoDiary", Diary.Tier.NONE,
        "Easy", Diary.Tier.EASY,
        "Medium", Diary.Tier.MEDIUM,
        "Hard", Diary.Tier.HARD,
        "Elite", Diary.Tier.ELITE);

    /** POH jewellery box names as the JSON spells them. */
    private static final Map<String, Poh.JewelleryBox> JEWELLERY_BOXES = Map.of(
        "NoJewelleryBox", Poh.JewelleryBox.NONE,
        "FancyJewelleryBox", Poh.JewelleryBox.FANCY,
        "OrnateJewelleryBox", Poh.JewelleryBox.ORNATE);

    /**
     * Completed-quest names that are reward unlocks rather than {@link Quest} enum entries —
     * RuneLite's quest list does not include them, and {@link Unlock} carries their varbits.
     */
    private static final Map<String, Unlock> REWARD_UNLOCKS = Map.of(
        "Architectural Alliance", Unlock.ARCHITECTURAL_ALLIANCE,
        "The Tale of the Righteous", Unlock.TALE_OF_THE_RIGHTEOUS);

    /** Display-name lookup tables, built once on first use. */
    private static final class DisplayNames {
        static final Map<String, Skill> SKILLS = skills();
        static final Map<String, Quest> QUESTS = quests();

        private static Map<String, Skill> skills() {
            Map<String, Skill> byName = new LinkedHashMap<>();
            for (Skill skill : Skill.values()) {
                byName.put(skill.getName(), skill);
            }
            return byName;
        }

        private static Map<String, Quest> quests() {
            Map<String, Quest> byName = new LinkedHashMap<>();
            for (Quest quest : Quest.values()) {
                byName.put(quest.getName(), quest);
            }
            return byName;
        }
    }

    private CorpusAccountProfiles() { }

    /** Loads every profile in a corpus {@code account-profiles-v1.json} file, keyed by name. */
    public static Map<String, Account> load(Path file) {
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read account profiles from " + file, e);
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException(file + " is not a JSON object", e);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException(file + " is not valid JSON", e);
        }
        return parse(root);
    }

    /** Loads a corpus account-profiles file from the test classpath. */
    public static Map<String, Account> loadResource(String resource) {
        JsonObject root;
        try (InputStream stream = CorpusAccountProfiles.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalArgumentException("no classpath resource: " + resource);
            }
            root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                .getAsJsonObject();
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException(resource + " is not a JSON object", e);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException(resource + " is not valid JSON", e);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + resource, e);
        }
        return parse(root);
    }

    private static Map<String, Account> parse(JsonObject root) {
        requireKeys(root, TOP_LEVEL_KEYS, "account profiles file");
        JsonElement version = root.get("formatVersion");
        if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
                || version.getAsInt() != FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported account profiles formatVersion: "
                + (version == null ? "absent" : version));
        }
        JsonElement profiles = root.get("profiles");
        if (profiles == null || !profiles.isJsonObject()) {
            throw new IllegalArgumentException("account profiles file has no profiles map");
        }
        Long benchmarkNowMinutes = root.has("benchmarkNowMinutes")
            ? root.get("benchmarkNowMinutes").getAsLong() : null;

        Map<String, Account> accounts = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : profiles.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                throw new IllegalArgumentException("profile " + entry.getKey() + " is not an object");
            }
            accounts.put(entry.getKey(), profile(entry.getKey(), entry.getValue().getAsJsonObject(),
                benchmarkNowMinutes));
        }
        return accounts;
    }

    private static Account profile(String name, JsonObject json, Long benchmarkNowMinutes) {
        requireKeys(json, PROFILE_KEYS, "profile " + name);
        Account.Builder account = Account.builder();
        if (benchmarkNowMinutes != null) {
            account.nowMinutes(benchmarkNowMinutes);
        }
        if (json.has("levels")) {
            for (Map.Entry<String, JsonElement> level : json.getAsJsonObject("levels").entrySet()) {
                int value = level.getValue().getAsInt();
                switch (level.getKey()) {
                    case "Quest": account.questPoints(value); break;
                    case "Total": account.totalLevel(value); break;
                    default:
                        Skill skill = DisplayNames.SKILLS.get(level.getKey());
                        if (skill == null) {
                            throw new IllegalArgumentException(
                                "profile " + name + ": unknown skill " + level.getKey());
                        }
                        account.level(skill, value);
                }
            }
        }
        if (json.has("completedQuests")) {
            for (JsonElement element : json.getAsJsonArray("completedQuests")) {
                String displayName = element.getAsString();
                Quest quest = DisplayNames.QUESTS.get(displayName);
                if (quest != null) {
                    account.quest(quest, QuestState.FINISHED);
                    continue;
                }
                Unlock reward = REWARD_UNLOCKS.get(displayName);
                if (reward == null) {
                    throw new IllegalArgumentException(
                        "profile " + name + ": unknown quest " + displayName);
                }
                account.unlock(reward);
            }
        }
        if (json.has("diaries")) {
            for (Map.Entry<String, JsonElement> diary : json.getAsJsonObject("diaries").entrySet()) {
                Diary.Tier tier = DIARY_TIERS.get(diary.getValue().getAsString());
                if (tier == null) {
                    throw new IllegalArgumentException("profile " + name
                        + ": unknown diary tier " + diary.getValue().getAsString());
                }
                account.diary(diary(name, diary.getKey()), tier);
            }
        }
        if (json.has("fairyRingsUnlocked")) {
            if (json.get("fairyRingsUnlocked").getAsBoolean()) {
                account.unlock(Unlock.FAIRY_RINGS);
            } else {
                account.lock(Unlock.FAIRY_RINGS);
            }
        }
        items(json, "inventory", name).forEach(account::inventory);
        items(json, "equipment", name).forEach(account::equipment);
        items(json, "runePouch", name).forEach(account::runePouch);
        items(json, "bank", name).forEach(account::bank);
        if (json.has("inventory")) {
            account.inventoryContainer();
        }
        if (json.has("equipment")) {
            account.equipmentContainer();
        }
        if (json.has("bank")) {
            account.bankContainer();
        }
        if (json.has("plantedSpiritTrees")) {
            List<PlantedSpiritTree> trees = new ArrayList<>();
            for (JsonElement element : json.getAsJsonArray("plantedSpiritTrees")) {
                try {
                    trees.add(PlantedSpiritTree.valueOf(element.getAsString()));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("profile " + name
                        + ": unknown planted spirit tree " + element.getAsString(), e);
                }
            }
            account.plantedSpiritTrees(trees.toArray(new PlantedSpiritTree[0]));
        }
        if (json.has("poh")) {
            account.poh(poh(name, json.getAsJsonObject("poh")));
        }
        if (json.has("runtime")) {
            runtime(name, account, json.getAsJsonObject("runtime"));
        }
        intMap(json, "varbits", name).forEach(account::varbit);
        intMap(json, "varplayers", name).forEach(account::varplayer);
        return account.build();
    }

    private static Poh poh(String profile, JsonObject json) {
        requireKeys(json, POH_KEYS, "profile " + profile + " poh");
        String locationName = required(json, "location", profile).getAsString();
        Poh.Location location;
        try {
            location = Poh.Location.valueOf(locationName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("profile " + profile
                + ": unknown POH location " + locationName, e);
        }
        String boxName = required(json, "jewelleryBox", profile).getAsString();
        Poh.JewelleryBox jewelleryBox = JEWELLERY_BOXES.get(boxName);
        if (jewelleryBox == null) {
            throw new IllegalArgumentException(
                "profile " + profile + ": unknown jewellery box " + boxName);
        }
        JsonObject portals = required(json, "portals", profile).getAsJsonObject();
        requireKeys(portals, PORTAL_KEYS, "profile " + profile + " poh portals");
        String mode = required(portals, "mode", profile).getAsString();
        List<String> destinations = null;
        switch (mode) {
            case "all":
                destinations = null;
                // mode "all" ignores the list — a non-empty destinations
                // member contradicts it and is rejected like the format's
                // other dead weight.
                if (portals.has("destinations")
                        && !(portals.get("destinations").isJsonArray()
                            && portals.getAsJsonArray("destinations").size() == 0)) {
                    throw new IllegalArgumentException("profile " + profile
                        + ": portals mode 'all' ignores the destinations member");
                }
                break;
            case "selected":
                destinations = new ArrayList<>();
                for (JsonElement element : required(portals, "destinations", profile)
                        .getAsJsonArray()) {
                    destinations.add(element.getAsString());
                }
                break;
            default:
                throw new IllegalArgumentException(
                    "profile " + profile + ": unknown portals mode " + mode);
        }
        return new Poh(location,
            required(json, "fairyRing", profile).getAsBoolean(),
            required(json, "spiritTree", profile).getAsBoolean(),
            required(json, "obelisk", profile).getAsBoolean(),
            jewelleryBox,
            required(json, "mountedGlory", profile).getAsBoolean(),
            required(json, "mountedXerics", profile).getAsBoolean(),
            required(json, "mountedDigsite", profile).getAsBoolean(),
            required(json, "mountedMythical", profile).getAsBoolean(),
            destinations);
    }

    private static void runtime(String profile, Account.Builder account, JsonObject json) {
        requireKeys(json, RUNTIME_KEYS, "profile " + profile + " runtime");
        String spellbookName = required(json, "spellbook", profile).getAsString();
        Spellbook spellbook;
        try {
            spellbook = Spellbook.valueOf(spellbookName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "profile " + profile + ": unknown spellbook " + spellbookName, e);
        }
        account.spellbook(spellbook);
        JsonObject cooldown = required(json, "minigameTeleport", profile).getAsJsonObject();
        requireKeys(cooldown, MINIGAME_TELEPORT_KEYS, "profile " + profile + " minigameTeleport");
        switch (required(cooldown, "state", profile).getAsString()) {
            case "ready":
                break;
            case "usedAt":
                // The minute of the last use, on the same clock as benchmarkNowMinutes.
                account.minigameTeleportUsedAt(
                    required(cooldown, "minutes", profile).getAsLong());
                break;
            default:
                throw new IllegalArgumentException("profile " + profile
                    + ": unknown minigame teleport state " + cooldown.get("state").getAsString());
        }
        // arriveInsidePoh is the web planner's house-arrival mode: a runtime hint for how a route
        // starts, not account state. Account has no field for it; the harness's profile/settings
        // layer owns arrival behaviour.
    }

    /** A region name like {@code KourendKebos} or {@code LumbridgeDraynor} to its diary. */
    private static Diary diary(String profile, String name) {
        try {
            return Diary.valueOf(name.replaceAll("(.)([A-Z])", "$1_$2").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "profile " + profile + ": unknown diary " + name, e);
        }
    }

    /** An item map of item-id keys to counts; a key that is not an item id fails closed. */
    private static Map<Integer, Integer> items(JsonObject profile, String field, String name) {
        Map<Integer, Integer> items = new LinkedHashMap<>();
        if (!profile.has(field)) {
            return items;
        }
        for (Map.Entry<String, JsonElement> entry : profile.getAsJsonObject(field).entrySet()) {
            items.put(itemId(name, field, entry.getKey()), entry.getValue().getAsInt());
        }
        return items;
    }

    private static int itemId(String profile, String field, String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("profile " + profile + ": " + field
                + " key is not an item id: " + key, e);
        }
    }

    private static Map<Integer, Integer> intMap(JsonObject profile, String field, String name) {
        Map<Integer, Integer> values = new LinkedHashMap<>();
        if (!profile.has(field)) {
            return values;
        }
        for (Map.Entry<String, JsonElement> entry : profile.getAsJsonObject(field).entrySet()) {
            try {
                values.put(Integer.parseInt(entry.getKey()), entry.getValue().getAsInt());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("profile " + profile + ": " + field
                    + " key is not a variable id: " + entry.getKey(), e);
            }
        }
        return values;
    }

    /** A member the v1 shape requires; absent members fail closed. */
    private static JsonElement required(JsonObject object, String key, String profile) {
        JsonElement element = object.get(key);
        if (element == null) {
            throw new IllegalArgumentException(
                "profile " + profile + ": missing field " + key);
        }
        return element;
    }

    private static void requireKeys(JsonObject object, Set<String> known, String what) {
        for (String key : object.keySet()) {
            if (!known.contains(key)) {
                throw new IllegalArgumentException(what + ": unknown field " + key);
            }
        }
    }
}
