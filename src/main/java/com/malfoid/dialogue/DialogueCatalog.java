package com.malfoid.dialogue;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.malfoid.additional.AdditionalSceneContext;
import com.malfoid.additional.AdditionalSceneContext.Subject;
import com.malfoid.additional.AdditionalTypes.ActivityKind;
import com.malfoid.additional.AdditionalTypes.Branch;
import com.malfoid.additional.AdditionalTypes.OutcomeKind;
import com.malfoid.additional.AdditionalTypes.ResourceKind;
import com.malfoid.additional.AdditionalTypes.SourceKind;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.world.LivingWorldData;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.runelite.api.Skill;

/** An indexed literal catalogue. Contract prose and authoring flags are never executed. */
public final class DialogueCatalog
{
    private static final Set<String> EXCLUDED = new HashSet<>(Arrays.asList(
        "M04-04", "M05-01", "M05-02", "M06-03", "M08-01", "M09-01", "M09-02", "M09-03", "M09-04", "M10-04", "M59-G04"));
    private static final Set<String> OUTFIT_SCENARIOS = new HashSet<>(Arrays.asList("M09", "M32", "M33", "M34"));
    private final Map<String, List<DialogueLine>> byScenario;
    private final Map<String, DialogueLine> byId;
    private final Map<String, String> repeatKeys;
    private final Map<String, SubjectBinding> subjects;
    private final int maximumTextKeys;
    private final int maximumInteractionKeys;
    private final Map<String, Integer> silencePercent;
    private final Set<String> runtimeEnabledScenarios;
    private final Map<String, String> livingWorldFamilies;

    private DialogueCatalog(Map<String, List<DialogueLine>> byScenario, Map<String, DialogueLine> byId,
        Map<String, String> repeatKeys, Map<String, SubjectBinding> subjects, int maximumTextKeys, int maximumInteractionKeys,
        Map<String, Integer> silencePercent, Set<String> runtimeEnabledScenarios)
    {
        this(byScenario, byId, repeatKeys, subjects, maximumTextKeys, maximumInteractionKeys,
            silencePercent, runtimeEnabledScenarios, Collections.emptyMap());
    }

    private DialogueCatalog(Map<String, List<DialogueLine>> byScenario, Map<String, DialogueLine> byId,
        Map<String, String> repeatKeys, Map<String, SubjectBinding> subjects, int maximumTextKeys, int maximumInteractionKeys,
        Map<String, Integer> silencePercent, Set<String> runtimeEnabledScenarios, Map<String, String> livingWorldFamilies)
    {
        this.byScenario = byScenario;
        this.byId = Collections.unmodifiableMap(byId);
        this.repeatKeys = Collections.unmodifiableMap(repeatKeys);
        this.subjects = Collections.unmodifiableMap(subjects);
        this.maximumTextKeys = maximumTextKeys;
        this.maximumInteractionKeys = maximumInteractionKeys;
        this.silencePercent = Collections.unmodifiableMap(silencePercent);
        this.runtimeEnabledScenarios = Collections.unmodifiableSet(runtimeEnabledScenarios);
        this.livingWorldFamilies = Collections.unmodifiableMap(livingWorldFamilies);
    }

    public static DialogueCatalog load(Class<?> resourceOwner) throws IOException
    {
        return load(resourceOwner, null);
    }

    public static DialogueCatalog load(Class<?> resourceOwner, Gson gson) throws IOException
    {
        try (InputStream in = resourceOwner.getResourceAsStream("/com/malfoid/dialogue.json"))
        {
            if (in == null)
            {
                throw new IOException("Missing bundled /com/malfoid/dialogue.json");
            }
            return read(in, gson);
        }
    }

    public static DialogueCatalog read(InputStream input) throws IOException
    {
        return read(input, null);
    }

    public static DialogueCatalog read(InputStream input, Gson gson) throws IOException
    {
        try
        {
            InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
            JsonObject root = gson == null ? new JsonParser().parse(reader).getAsJsonObject() : gson.fromJson(reader, JsonObject.class);
            require(root.get("schemaVersion").getAsInt() == 1, "Unsupported dialogue schema");
            Set<String> registry = new HashSet<>();
            for (JsonElement id : root.getAsJsonArray("registry"))
            {
                require(registry.add(id.getAsString()), "Duplicate scenario ID");
            }
            Map<String, String> repeatKeys = new HashMap<>();
            JsonObject policy = root.getAsJsonObject("speechGuardPolicy");
            require(policy.getAsJsonObject("repeatCooldownSeconds").get("minimumInclusive").getAsInt() == 30
                && policy.getAsJsonObject("repeatCooldownSeconds").get("maximumInclusive").getAsInt() == 60,
                "Unexpected repeat policy");
            Set<String> uniqueKeys = new HashSet<>();
            for (Map.Entry<String, JsonElement> entry : policy.getAsJsonObject("interactionRepeatKeys").entrySet())
            {
                require(registry.contains(entry.getKey()), "Unknown reactive scenario");
                String key = entry.getValue().isJsonNull() ? null : entry.getValue().getAsString();
                repeatKeys.put(entry.getKey(), key);
                if (key != null)
                {
                    uniqueKeys.add(key);
                }
            }
            require(uniqueKeys.size() <= policy.get("maximumInteractionKeys").getAsInt(), "Repeat guard capacity exceeded");
            Map<String, SubjectBinding> subjects = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("situationSpeechPolicy").getAsJsonObject("byLogicalKey").entrySet())
            {
                JsonObject value = entry.getValue().getAsJsonObject();
                JsonObject binding = value.getAsJsonObject("subjectBinding");
                require(uniqueKeys.contains(entry.getKey()), "Unknown situation key");
                subjects.put(entry.getKey(), new SubjectBinding(entry.getKey(), "MOMENT".equals(value.get("repeatClass").getAsString()), binding));
            }
            require(subjects.keySet().equals(uniqueKeys), "Missing situation classification");
            Set<String> runtimeEnabledScenarios = new HashSet<>();
            for (JsonElement id : root.getAsJsonArray("runtimeEnabledScenarios"))
            {
                require(registry.contains(id.getAsString()), "Unknown runtime-enabled scenario");
                runtimeEnabledScenarios.add(id.getAsString());
                SubjectBinding subject = subjects.get(repeatKeys.get(id.getAsString()));
                require(subject == null || subject.hasRuntimeExtractor(), "Enabled scenario needs typed subject extractor");
            }
            Map<String, Integer> silencePercent = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("silencePercentByScenario").entrySet())
            {
                int percent = entry.getValue().getAsInt();
                require(registry.contains(entry.getKey()) && percent >= 0 && percent <= 100, "Invalid silent branch");
                silencePercent.put(entry.getKey(), percent);
            }
            Map<String, DialogueLine> byId = new HashMap<>();
            Map<String, List<DialogueLine>> byScenario = new HashMap<>();
            Set<String> enabledTextKeys = new HashSet<>();
            for (JsonElement element : root.getAsJsonArray("entries"))
            {
                JsonObject entry = element.getAsJsonObject();
                String id = entry.get("id").getAsString();
                String scenario = entry.get("scenario").getAsString();
                String text = entry.get("text").getAsString();
                String status = entry.get("status").getAsString();
                require(registry.contains(scenario), "Unknown scenario: " + scenario);
                require("enabled".equals(status) || "disabled".equals(status), "Unknown entry status");
                boolean enabled = "enabled".equals(status);
                validateText(text);
                require(!enabled || !EXCLUDED.contains(id), "Enabled excluded line: " + id);
                validateOwnerAndOutfitLine(id, scenario, text, enabled);
                Map<String, String> conditions = new HashMap<>();
                for (Map.Entry<String, JsonElement> condition : entry.getAsJsonObject("conditions").entrySet())
                {
                    validateCondition(id, scenario, condition.getKey(), condition.getValue());
                    conditions.put(condition.getKey(), condition.getValue().getAsString());
                }
                String presentation = entry.get("presentation").getAsString();
                require("OVERHEAD".equals(presentation) || "LOCAL_EXAMINE".equals(presentation)
                    || "LOCAL_NOTICE".equals(presentation), "Unknown presentation");
                DialogueLine line = new DialogueLine(id, scenario, text, entry.get("emotion").getAsString(), conditions, enabled, presentation);
                require(byId.put(id, line) == null, "Duplicate line ID: " + id);
                byScenario.computeIfAbsent(scenario, ignored -> new ArrayList<>()).add(line);
                if (enabled && line.isOverhead())
                {
                    enabledTextKeys.add(line.getNormalizedText());
                }
            }
            require(enabledTextKeys.size() <= policy.get("maximumTextKeys").getAsInt(), "Text guard capacity exceeded");
            byScenario.replaceAll((key, value) -> Collections.unmodifiableList(value));
            return new DialogueCatalog(Collections.unmodifiableMap(byScenario), byId, repeatKeys, subjects,
                policy.get("maximumTextKeys").getAsInt(), policy.get("maximumInteractionKeys").getAsInt(), silencePercent, runtimeEnabledScenarios);
        }
        catch (IllegalArgumentException | IllegalStateException | NullPointerException e)
        {
            throw new IOException("Invalid bundled dialogue: " + e.getMessage(), e);
        }
    }

    public List<DialogueLine> getLines(String scenario)
    {
        return byScenario.getOrDefault(scenario, Collections.emptyList());
    }

    /** Addendum F remains one exact resource; no second authored list is maintained. */
    public DialogueCatalog withLivingWorld(LivingWorldData data)
    {
        Map<String, List<DialogueLine>> lines = new HashMap<>(byScenario);
        Map<String, DialogueLine> ids = new HashMap<>(byId);
        Map<String, String> keys = new HashMap<>(repeatKeys);
        Map<String, SubjectBinding> bindings = new HashMap<>(subjects);
        Set<String> enabled = new HashSet<>(runtimeEnabledScenarios);
        Map<String, String> families = new HashMap<>(livingWorldFamilies);
        Set<String> oldTexts = new HashSet<>(), newTexts = new HashSet<>();
        for (DialogueLine line : byId.values())
        {
            if (line.isEnabled() && line.isOverhead()) { oldTexts.add(line.getNormalizedText()); }
        }
        Set<String> newKeys = new HashSet<>();
        addLivingWorldGroups(data.places, "PLACE", lines, ids, keys, bindings, enabled, families, newTexts, newKeys);
        addLivingWorldGroups(data.placeKinds, "PLACE", lines, ids, keys, bindings, enabled, families, newTexts, newKeys);
        addLivingWorldGroups(data.creatures, "CREATURE", lines, ids, keys, bindings, enabled, families, newTexts, newKeys);
        addLivingWorldGroups(data.scenery, "SCENERY", lines, ids, keys, bindings, enabled, families, newTexts, newKeys);
        addLivingWorldGroups(data.moments, "MOMENT", lines, ids, keys, bindings, enabled, families, newTexts, newKeys);
        newTexts.removeAll(oldTexts);
        newKeys.removeAll(subjects.keySet());
        return new DialogueCatalog(Collections.unmodifiableMap(lines), ids, keys, bindings,
            maximumTextKeys + newTexts.size(), maximumInteractionKeys + newKeys.size(),
            new HashMap<>(silencePercent), enabled, families);
    }

    private static void addLivingWorldGroups(List<? extends LivingWorldData.Group> groups, String family,
        Map<String, List<DialogueLine>> scenarios, Map<String, DialogueLine> ids, Map<String, String> keys,
        Map<String, SubjectBinding> bindings, Set<String> enabled, Map<String, String> families,
        Set<String> textKeys, Set<String> interactionKeys)
    {
        for (LivingWorldData.Group group : groups)
        {
            // E.5's F01/F02 speech belongs to the higher-priority base E.9 catalogue.
            if (group.lines.isEmpty()) { continue; }
            require(!scenarios.containsKey(group.id) && !families.containsKey(group.id), "Duplicate living-world scenario");
            List<DialogueLine> lines = new ArrayList<>();
            for (LivingWorldData.Line authored : group.lines)
            {
                validateText(authored.text);
                DialogueLine line = new DialogueLine(authored.id, group.id, authored.text, authored.emotion,
                    Collections.emptyMap(), true, "OVERHEAD");
                require(ids.put(authored.id, line) == null, "Duplicate living-world line ID");
                lines.add(line); textKeys.add(line.getNormalizedText());
            }
            String key = "PLACE".equals(family) ? "W-PLACE"
                : group instanceof LivingWorldData.Moment ? ((LivingWorldData.Moment) group).logicalKey : group.id;
            scenarios.put(group.id, Collections.unmodifiableList(lines)); keys.put(group.id, key);
            enabled.add(group.id); families.put(group.id, family); interactionKeys.add(key);
            JsonObject binding = new JsonObject(); binding.addProperty("type", "NOT_APPLICABLE");
            bindings.put(key, new SubjectBinding(key, true, binding));
        }
    }

    public boolean isLivingWorldScenario(String scenario) { return livingWorldFamilies.containsKey(scenario); }
    public String livingWorldFamily(String scenario) { return livingWorldFamilies.get(scenario); }

    public DialogueLine getLine(String id) { return byId.get(id); }
    public int size() { return byId.size(); }
    public String repeatKey(String scenario) { return repeatKeys.get(scenario); }
    public boolean isReactive(String scenario) { return repeatKeys.containsKey(scenario) && repeatKeys.get(scenario) != null; }
    public boolean isRuntimeScenarioEnabled(String scenario) { return runtimeEnabledScenarios.contains(scenario); }
    public int getMaximumTextKeys() { return maximumTextKeys; }
    public int getMaximumInteractionKeys() { return maximumInteractionKeys; }
    public int getSilencePercent(String scenario) { return silencePercent.getOrDefault(scenario, 0); }

    public List<String> getApprovedExamineTexts()
    {
        List<String> result = new ArrayList<>();
        for (DialogueLine line : getLines("U02"))
        {
            if (line.isEnabled())
            {
                result.add(line.getText());
            }
        }
        return Collections.unmodifiableList(result);
    }

    public String getLocalText(String id)
    {
        DialogueLine line = byId.get(id);
        return line != null && line.isEnabled() && !line.isOverhead() ? line.getText() : null;
    }

    /** Null means unknown typed subject; moments need no fingerprint. */
    public Long situationFingerprint(String scenario, Map<String, String> facts)
    {
        if (additionalIndex(scenario) != 0) { return null; }
        String logicalKey = repeatKey(scenario);
        SubjectBinding binding = subjects.get(logicalKey);
        if (binding == null || binding.moment)
        {
            return null;
        }
        String subject = binding.subject(facts);
        if (subject == null)
        {
            return null;
        }
        return fingerprint(logicalKey, subject);
    }

    /** New contracts use their immutable source context, never presentation strings as evidence. */
    public Long situationFingerprint(SceneCandidate candidate)
    {
        if (additionalIndex(candidate.getScenarioId()) == 0)
        {
            return situationFingerprint(candidate.getScenarioId(), candidate.getConditionFacts());
        }
        if (!hasValidOriginalContext(candidate)) { return null; }
        String key = repeatKey(candidate.getScenarioId());
        SubjectBinding binding = subjects.get(key);
        return binding == null || binding.moment ? null : fingerprint(key, canonicalAdditionalSubject(candidate.getAdditionalContext()));
    }

    String canonicalAdditionalSubject(AdditionalSceneContext context)
    {
        SubjectBinding binding = subjects.get(repeatKey(context.ruleId));
        return binding == null || binding.moment ? null : binding.subject(context.subject);
    }

    /** Original ownership only. The observer separately proves current first-paint validity. */
    public boolean hasValidOriginalContext(SceneCandidate candidate)
    {
        int index = additionalIndex(candidate.getScenarioId());
        AdditionalSceneContext context = candidate.getAdditionalContext();
        if (index == 0) { return context == null; }
        if (context == null || context.ruleIndex != index || context.sourceKind == SourceKind.UNKNOWN
            || context.scene.sessionGeneration != candidate.getSessionGeneration()
            || context.scene.sceneRevision != candidate.getSceneRevision()
            || context.originalDeadline != candidate.getExpiresTick()
            || context.occurrenceTick > candidate.getCreatedTick() || context.presenceEpoch > context.occurrenceTick
            || !sourceAndClaimsValid(context))
        {
            return false;
        }
        SubjectBinding binding = subjects.get(repeatKey(candidate.getScenarioId()));
        return binding != null && (binding.moment ? knownMomentSubject(context) : binding.subject(context.subject) != null);
    }

    private static int additionalIndex(String scenario)
    {
        if (scenario == null || !scenario.startsWith("M")) { return 0; }
        try
        {
            int value = Integer.parseInt(scenario.substring(1));
            return value >= 71 && value <= 170 ? value : 0;
        }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static Long fingerprint(String logicalKey, String subject)
    {
        if (subject == null) { return null; }
        long hash = 0xcbf29ce484222325L;
        for (byte value : (logicalKey + "\u0000" + subject).getBytes(StandardCharsets.UTF_8))
        {
            hash ^= value & 255;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static boolean knownMomentSubject(AdditionalSceneContext context)
    {
        Subject subject = context.subject;
        return subject.type == Subject.Type.UNIT || subject.type == Subject.Type.ANNOUNCEMENT_KIND
            && subject.outcomeKind != null && subject.outcomeKind.ruleIndex == context.ruleIndex;
    }

    private static boolean sourceAndClaimsValid(AdditionalSceneContext context)
    {
        int id = context.ruleIndex;
        SourceKind source = context.sourceKind;
        long claims = context.permittedClaimMask;
        switch (id)
        {
            case 71: case 72: case 73: case 74: case 78: case 81: case 87: case 88:
                return claims == 1 && source == SourceKind.SPAM_MESSAGE;
            case 80: case 82: case 83: case 84: case 85: case 86: case 94: case 95:
                return claims == 1 && source == SourceKind.GAME_MESSAGE;
            case 96: case 99: return claims == 97 && source == SourceKind.GAME_MESSAGE;
            case 77: case 79: case 89: case 90: case 97: case 103:
                return claims == 1 && (source == SourceKind.SPAM_MESSAGE || source == SourceKind.GAME_MESSAGE);
            case 100: return claims == 1 && (source == SourceKind.SPAM_MESSAGE || source == SourceKind.GAME_MESSAGE || source == SourceKind.MESSAGE_BOX);
            case 91: return claims == 16 && source == SourceKind.GROUND_ITEM_SPAWN
                && context.subject.type == Subject.Type.UNIT && context.sourceSerial == context.causeSerial
                && context.occurrenceTick <= Long.MAX_VALUE - 12
                && context.originalDeadline == context.occurrenceTick + 12
                && context.payload.definitionId == 11849 && context.payload.currentLive
                && context.payload.entitySerial > 0 && context.payload.position != null;
            case 92: case 93: case 104: case 131: case 132: case 133: case 134:
                return claims == 2 && source == SourceKind.NUMERIC;
            case 101: return claims == 16 && source == SourceKind.HITSPLAT;
            case 102: return claims == 2 && source == SourceKind.VARP;
            case 105: return claims == 2 && source == SourceKind.VARBIT;
            case 106: return claims == 12 && source == SourceKind.INVENTORY;
            case 114: case 115: case 116: case 124: return claims == 4 && source == SourceKind.EQUIPMENT;
            case 123: return claims == 6 && source == SourceKind.VARBIT
                && context.subject.type == Subject.Type.RESOURCE_KIND
                && context.subject.resourceKind == ResourceKind.STANDARD_RUNE_POUCH
                && context.sourceSerial == context.causeSerial
                && context.occurrenceTick <= Long.MAX_VALUE - 12
                && context.originalDeadline == context.occurrenceTick + 12
                && context.payload.definitionId == 12791 && context.payload.currentLive
                && context.payload.entitySerial > 0 && context.payload.activitySerial > 0;
            case 121: case 122: case 125: case 127: case 129: case 130: case 135:
                return claims == 4 && source == SourceKind.INVENTORY;
            case 107: case 108: case 109: case 110: case 111: case 112: case 117: case 126:
                return claims == 1 && source == SourceKind.GAME_MESSAGE;
            case 113: case 118: case 119: case 120: case 128: case 136: case 137: case 138: case 139: case 140:
                return claims == 1 && (source == SourceKind.GAME_MESSAGE || source == SourceKind.SPAM_MESSAGE);
            case 142: return claims == 1 && source == SourceKind.GAME_MESSAGE
                && reportedMoment(context, OutcomeKind.COLLECTION_LOG_ENTRY)
                && context.payload.branch == Branch.UNKNOWN && context.payload.activity == ActivityKind.UNKNOWN
                && context.payload.value == -1 && context.payload.activitySerial == -1
                && reportedText(context.payload.displayText, 96);
            case 147: return claims == 65 && source == SourceKind.GAME_MESSAGE
                && reportedMoment(context, OutcomeKind.COMBAT_ACHIEVEMENT)
                && achievementTier(context.payload.branch) && context.payload.activity == ActivityKind.UNKNOWN
                && context.payload.value == -1 && context.payload.activitySerial == -1
                && reportedText(context.payload.displayText, 120);
            case 160: return claims == 65 && source == SourceKind.GAME_MESSAGE
                && reportedMoment(context, OutcomeKind.SEPULCHRE_FLOOR)
                && context.payload.branch == Branch.UNKNOWN && context.payload.activity == ActivityKind.SEPULCHRE
                && context.payload.value >= 1 && context.payload.value <= 5
                && context.payload.activitySerial == -1 && context.payload.displayText.isEmpty();
            case 162: return claims == 1 && source == SourceKind.GAME_MESSAGE
                && reportedMoment(context, OutcomeKind.TZHAAR_WAVE)
                && context.payload.branch == Branch.UNKNOWN && context.payload.activitySerial > 0
                && context.payload.displayText.isEmpty()
                && (context.payload.activity == ActivityKind.FIGHT_CAVE
                    && (context.payload.value == 31 || context.payload.value == 61 || context.payload.value == 63)
                    || context.payload.activity == ActivityKind.INFERNO
                    && (context.payload.value == 35 || context.payload.value == 67 || context.payload.value == 68 || context.payload.value == 69));
            case 163: return claims == 1 && source == SourceKind.GAME_MESSAGE
                && context.subject.type == Subject.Type.ANNOUNCEMENT_KIND
                && context.subject.outcomeKind == OutcomeKind.PERSONAL_BEST
                && context.sourceSerial == context.causeSerial && context.occurrenceTick <= Long.MAX_VALUE - 20
                && context.originalDeadline == context.occurrenceTick + 20
                && context.payload.branch == Branch.UNKNOWN && context.payload.activity == ActivityKind.THEATRE
                && context.payload.durationMillis > 0 && context.payload.durationMillis <= 128849018879990L
                && context.payload.durationMillis % 10 == 0 && context.payload.displayText.isEmpty()
                && context.payload.value == -1 && context.payload.secondaryValue == -1 && context.payload.definitionId == -1
                && context.payload.entitySerial == -1 && context.payload.activitySerial == -1
                && context.payload.position == null && !context.payload.currentLive;
            case 141: case 149: case 151:
                return claims == 1 && source == SourceKind.GAME_MESSAGE;
            case 144: case 159: case 165:
                return claims == 65 && source == SourceKind.GAME_MESSAGE;
            case 145: return claims == 72 && source == SourceKind.GAME_MESSAGE;
            case 148: return claims == 32 && source == SourceKind.VARBIT;
            case 150: return claims == 16 && source == SourceKind.ACTOR_INTERACTION
                && context.subject.type == Subject.Type.UNIT;
            case 152: case 157: return claims == 1 && source == SourceKind.SPAM_MESSAGE;
            case 153: case 154: case 156: case 170: return claims == 8 && source == SourceKind.UI;
            case 155: return claims == 1 && source == SourceKind.TRADE_REQUEST;
            case 158: return claims == 65 && (source == SourceKind.GAME_MESSAGE || source == SourceKind.SPAM_MESSAGE);
            case 168: return claims == 16 && source == SourceKind.FOLLOWER;
            // Unproved selected-entity/current-association paths remain closed.
            default: return false;
        }
    }

    private static boolean reportedMoment(AdditionalSceneContext context, OutcomeKind kind)
    {
        return context.subject.type == Subject.Type.ANNOUNCEMENT_KIND && context.subject.outcomeKind == kind
            && context.sourceSerial == context.causeSerial && context.occurrenceTick <= Long.MAX_VALUE - 20
            && context.originalDeadline == context.occurrenceTick + 20
            && context.payload.secondaryValue == -1 && context.payload.definitionId == -1
            && context.payload.durationMillis == -1 && context.payload.entitySerial == -1
            && context.payload.position == null && !context.payload.currentLive;
    }

    private static boolean achievementTier(Branch branch)
    {
        return branch == Branch.EASY || branch == Branch.MEDIUM || branch == Branch.HARD
            || branch == Branch.ELITE || branch == Branch.MASTER || branch == Branch.GRANDMASTER;
    }

    private static boolean reportedText(String value, int limit)
    {
        if (value.trim().isEmpty() || value.codePointCount(0, value.length()) > limit) { return false; }
        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || c == '<' || c == '>' || c == '@') { return false; }
            if (Character.isHighSurrogate(c))
            {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) { return false; }
            }
            else if (Character.isLowSurrogate(c)) { return false; }
        }
        return true;
    }

    public boolean isSituation(String scenario)
    {
        SubjectBinding binding = subjects.get(repeatKey(scenario));
        return binding != null && !binding.moment;
    }

    public static String normalizeText(String text)
    {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        boolean space = false;
        for (int offset = 0; offset < normalized.length();)
        {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint))
            {
                space = result.length() > 0;
            }
            else
            {
                if (space)
                {
                    result.append(' ');
                }
                result.appendCodePoint(codePoint);
                space = false;
            }
        }
        return result.toString();
    }

    private static void validateText(String text)
    {
        require(!normalizeText(text).isEmpty() && text.codePointCount(0, text.length()) <= 180, "Invalid line length");
        require(text.indexOf('<') < 0 && text.indexOf('>') < 0, "Markup in line");
        for (int offset = 0; offset < text.length();)
        {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            require(!Character.isISOControl(cp) && cp != 0xfffd, "Control or invalid character in line");
        }
    }

    private static void validateCondition(String id, String scenario, String name, JsonElement value)
    {
        require(value.isJsonPrimitive(), "Nonprimitive dialogue condition");
        boolean valid = false;
        switch (name)
        {
            case "newRealLevel": valid = "M04".equals(scenario) && value.getAsJsonPrimitive().isNumber() && value.getAsInt() == 99; break;
            case "skill": valid = "M06".equals(scenario) && value.getAsJsonPrimitive().isString()
                && Arrays.asList("FISHING", "WOODCUTTING", "MINING").contains(value.getAsString()); break;
            case "itemPossessionConfirmed": valid = "M05-05".equals(id) && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean(); break;
            case "objectKind": valid = "M08".equals(scenario) && value.getAsJsonPrimitive().isString()
                && Arrays.asList("TREE", "FOUNTAIN").contains(value.getAsString()); break;
            case "parentSpeechVisibleAtDeparture": valid = ("M07-04".equals(id) || "M07-05".equals(id))
                && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean(); break;
            case "changedSlot": valid = "M09".equals(scenario) && value.getAsJsonPrimitive().isString() && "CAPE".equals(value.getAsString()); break;
            case "recentReunionWithinTicks": valid = "M10".equals(scenario) && value.getAsJsonPrimitive().isNumber() && value.getAsInt() == 100; break;
            case "talkStreak": valid = "U01".equals(scenario) && value.getAsJsonPrimitive().isNumber() && value.getAsInt() >= 1 && value.getAsInt() <= 3; break;
            case "playerHpAtMostPercent": valid = "U01".equals(scenario) && value.getAsJsonPrimitive().isNumber() && value.getAsInt() == 25; break;
            case "combatConfirmed":
            case "localNight": valid = "U01".equals(scenario) && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean(); break;
            default: break;
        }
        require(valid, "Unsupported condition " + name + " for " + id);
    }

    private static void validateOwnerAndOutfitLine(String id, String scenario, String text, boolean enabled)
    {
        if ("M09-H01".equals(id))
        {
            require("M09".equals(scenario) && "Hmph.".equals(text), "Changed owner line: " + id);
        }
        else if ("M09-H02".equals(id))
        {
            require("M09".equals(scenario) && "...Hmph.".equals(text), "Changed owner line: " + id);
        }
        else if ("F03-01".equals(id))
        {
            require("F03".equals(scenario) && "Where are you taking me, Potter?!".equals(text), "Changed owner line: " + id);
        }
        if (enabled && "M09".equals(scenario))
        {
            require(Arrays.asList("M09-05", "M09-H01", "M09-H02", "M09-T01", "M09-T02", "M09-T03").contains(id), "Unreviewed outfit line: " + id);
        }
        if (enabled && OUTFIT_SCENARIOS.contains(scenario))
        {
            String lower = text.toLowerCase(Locale.ROOT);
            for (String word : new String[] {"ensemble", "attire", "headpiece", "garment", "wardrobe", "overhaul", "triumphant"})
            {
                require(!lower.contains(word), "Withdrawn outfit wording: " + id);
            }
        }
    }

    private static void require(boolean condition, String message)
    {
        if (!condition)
        {
            throw new IllegalArgumentException(message);
        }
    }

    private static final class SubjectBinding
    {
        private final String logicalKey;
        private final boolean moment;
        private final String type;
        private final String fixedSubject;

        private SubjectBinding(String logicalKey, boolean moment, JsonObject binding)
        {
            this.logicalKey = logicalKey;
            this.moment = moment;
            this.type = binding.get("type").getAsString();
            JsonElement values = binding.has("itemIds") ? binding.get("itemIds") : binding.get("skills");
            this.fixedSubject = values == null ? null : values.toString();
            require(Arrays.asList("NOT_APPLICABLE", "ACTUAL_REGION", "ACTUAL_REGION_AND_SELECTED_SKILL",
                "QUALIFYING_NPC_RESOLVED_NAME", "FOUR_SLOT_EQUIPMENT_IDS", "MATCHED_HEAD_ITEM_ID",
                "NAMED_PREDICATE_ITEM_ID_SET", "ANCHOR_BAG_ITEM_ID_SET", "SELECTED_COMBAT_SKILL_SET",
                "SELECTED_PRIMARY_SKILL", "FIXED_SKILL_SET", "CURRENT_OBJECT_KIND", "UNIT",
                "SKILL", "RESOURCE_KIND", "FINITE_ITEM_FAMILY", "ANNOUNCEMENT_KIND", "RECOVERY_SUBJECT",
                "WIDGET_KIND", "WORLD_OBJECT_KIND").contains(type), "Unknown subject binding");
            String expected = additionalSubjectType(logicalKey);
            if (expected != null) { require(expected.equals(type), "Changed finite additional subject binding"); }
            else { require(!Arrays.asList("SKILL", "RESOURCE_KIND", "FINITE_ITEM_FAMILY", "ANNOUNCEMENT_KIND",
                "RECOVERY_SUBJECT", "WIDGET_KIND", "WORLD_OBJECT_KIND").contains(type), "Unmapped typed subject binding"); }
        }

        private boolean hasRuntimeExtractor()
        {
            return moment || !"NOT_APPLICABLE".equals(type);
        }

        private static String additionalSubjectType(String key)
        {
            switch (key)
            {
                case "M71": case "M73": case "M75": case "M77": case "M79": case "M81": case "M100": return "SKILL";
                case "M114": return "RESOURCE_KIND";
                case "M118": case "M121": case "M125": case "M127": case "M130": case "M135": return "FINITE_ITEM_FAMILY";
                case "M128": return "ANNOUNCEMENT_KIND";
                case "M131": return "RECOVERY_SUBJECT";
                case "M153": case "M154": case "M156": case "M170": return "WIDGET_KIND";
                case "M169": return "WORLD_OBJECT_KIND";
                default: return null;
            }
        }

        private String subject(Subject subject)
        {
            if (!type.equals(subject.type.name())) { return null; }
            switch (type)
            {
                case "UNIT": return "UNIT";
                case "SKILL":
                    Skill expected = "M71".equals(logicalKey) ? Skill.COOKING : "M73".equals(logicalKey) ? Skill.THIEVING
                        : "M77".equals(logicalKey) ? Skill.MINING : "M79".equals(logicalKey) ? Skill.WOODCUTTING
                        : "M81".equals(logicalKey) ? Skill.FISHING : Skill.HUNTER;
                    return subject.skill == expected ? expected.name() : null;
                case "RESOURCE_KIND":
                    return subject.resourceKind == null || "UNKNOWN".equals(subject.resourceKind.name()) ? null : subject.resourceKind.name();
                case "FINITE_ITEM_FAMILY":
                    if (subject.itemFamily == null) { return null; }
                    String family = subject.itemFamily.name();
                    switch (logicalKey)
                    {
                        case "M118": return "DODGY_NECKLACE".equals(family) || "RING_OF_DUELING".equals(family) ? family : null;
                        case "M121": return "OIL_LANTERN".equals(family) ? family : null;
                        case "M125": return "STANDARD_PRAYER_POTION".equals(family) ? family : null;
                        case "M127": return "TOY_HORSE".equals(family) || "DWARVEN_ROCK_CAKE".equals(family) ? family : null;
                        case "M130": return "SMALL_COMFORT_SNACK".equals(family) ? family : null;
                        case "M135": return "BEGINNER_CLUE".equals(family) ? "BEGINNER_CLUE_SCROLL" : null;
                        default: return null;
                    }
                case "ANNOUNCEMENT_KIND":
                    return subject.outcomeKind == com.malfoid.additional.AdditionalTypes.OutcomeKind.CAT_NEEDS_FOOD ? "OWN_CAT_NEEDS_FOOD" : null;
                case "RECOVERY_SUBJECT":
                    if (subject.recoveryKind == null) { return null; }
                    switch (subject.recoveryKind)
                    {
                        case RUN_ENERGY: return subject.skill == null ? "RUN_ENERGY" : null;
                        case PRAYER: return subject.skill == null ? "PRAYER_POINTS" : null;
                        case SKILL:
                            return Arrays.asList(Skill.AGILITY, Skill.THIEVING, Skill.CRAFTING, Skill.WOODCUTTING,
                                Skill.FISHING, Skill.COOKING, Skill.FARMING).contains(subject.skill)
                                ? "RESTORED_CURRENT_SKILL:" + subject.skill.name() : null;
                        default: return null;
                    }
                case "WIDGET_KIND":
                    if (subject.uiKind == null) { return null; }
                    switch (logicalKey)
                    {
                        case "M153": return subject.uiKind == com.malfoid.additional.AdditionalTypes.UiKind.WORLD_MAP ? "WORLD_MAP_OPEN" : null;
                        case "M154": return subject.uiKind == com.malfoid.additional.AdditionalTypes.UiKind.SHOP ? "SHOP_POST_CLOSE" : null;
                        case "M156": return subject.uiKind == com.malfoid.additional.AdditionalTypes.UiKind.FAIRY_RING ? "FAIRY_RING_SELECTION" : null;
                        case "M170": return subject.uiKind == com.malfoid.additional.AdditionalTypes.UiKind.CLUE_PUZZLE ? "CLUE_PUZZLE_BOX_OPEN" : null;
                        default: return null;
                    }
                case "WORLD_OBJECT_KIND":
                    return subject.worldObjectKind == com.malfoid.additional.AdditionalTypes.WorldObjectKind.NEARBY_CAMPFIRE ? "NEARBY_CAMPFIRE" : null;
                default: return null;
            }
        }

        private String subject(Map<String, String> facts)
        {
            switch (type)
            {
                case "UNIT": return "UNIT";
                case "NAMED_PREDICATE_ITEM_ID_SET":
                case "FIXED_SKILL_SET": return fixedSubject;
                case "ACTUAL_REGION": return knownInteger(facts.get("actualRegion"), false);
                case "ACTUAL_REGION_AND_SELECTED_SKILL":
                    String region = knownInteger(facts.get("actualRegion"), false);
                    String skill = knownSkill(facts.get("skill"));
                    return region == null || skill == null ? null : region + ":" + skill;
                case "QUALIFYING_NPC_RESOLVED_NAME":
                    String name = known(facts.get("npcResolvedName"));
                    return name == null ? null : known(normalizeText(name.replaceAll("<[^>]*>", "")));
                case "FOUR_SLOT_EQUIPMENT_IDS": return integerList(facts.get("equipmentIds"), 4, false, true);
                case "MATCHED_HEAD_ITEM_ID": return knownInteger(facts.get("matchedHeadItemId"), false);
                case "ANCHOR_BAG_ITEM_ID_SET": return integerList(facts.get("anchorBagItemIds"), -1, true, false);
                case "SELECTED_COMBAT_SKILL_SET": return skillSet(facts.getOrDefault("selectedCombatSkills", facts.get("skillSet")));
                case "SELECTED_PRIMARY_SKILL": return knownSkill(facts.get("skill"));
                case "CURRENT_OBJECT_KIND":
                    String kind = facts.get("objectKind");
                    return "TREE".equals(kind) || "FOUNTAIN".equals(kind) ? kind : null;
                default: return null;
            }
        }

        private static String known(String value)
        {
            return value == null || value.trim().isEmpty() || "UNKNOWN".equals(value) ? null : value;
        }

        private static String knownInteger(String value, boolean allowEmpty)
        {
            try
            {
                int number = Integer.parseInt(value);
                return number >= (allowEmpty ? -1 : 0) ? Integer.toString(number) : null;
            }
            catch (NumberFormatException ignored)
            {
                return null;
            }
        }

        private static String knownSkill(String value)
        {
            try { return Skill.valueOf(value).name(); }
            catch (IllegalArgumentException | NullPointerException ignored) { return null; }
        }

        private static String integerList(String value, int count, boolean sortedDistinct, boolean allowEmpty)
        {
            if (known(value) == null) { return null; }
            String[] parts = value.split(",", -1);
            if (count >= 0 && parts.length != count || parts.length > 28) { return null; }
            List<String> canonical = new ArrayList<>();
            TreeSet<Integer> sorted = new TreeSet<>();
            for (String part : parts)
            {
                String item = knownInteger(part.trim(), allowEmpty);
                if (item == null) { return null; }
                if (sortedDistinct) { sorted.add(Integer.parseInt(item)); }
                else { canonical.add(item); }
            }
            if (sortedDistinct)
            {
                for (Integer item : sorted) { canonical.add(item.toString()); }
            }
            return String.join(",", canonical);
        }

        private static String skillSet(String value)
        {
            if (known(value) == null) { return null; }
            TreeSet<Skill> skills = new TreeSet<>();
            for (String part : value.split(",", -1))
            {
                String skill = knownSkill(part.trim());
                if (skill == null) { return null; }
                skills.add(Skill.valueOf(skill));
            }
            if (skills.size() != 3) { return null; }
            List<String> names = new ArrayList<>();
            for (Skill skill : skills) { names.add(skill.name()); }
            return String.join(",", names);
        }
    }
}
