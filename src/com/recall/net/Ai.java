package com.recall.net;

import android.content.Context;

import com.recall.data.Model;
import com.recall.util.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider-agnostic front door. Recall runs on free tiers, so the model behind it is
 * expected to change; everything above this class is written once and never has to care.
 */
public class Ai {

    public static final String GEMINI = "gemini";
    public static final String GROQ = "groq";

    // ---- catalogue --------------------------------------------------------

    public static final String[] PROVIDERS = { GEMINI, GROQ };
    public static final String[] PROVIDER_LABELS = {
            "Google Gemini  (free tier)",
            "Groq  (free tier)"
    };

    private static final String[] GEMINI_MODELS = {
            "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite"
    };
    private static final String[] GEMINI_LABELS = {
            "Gemini 3.6 Flash  (best tutor)",
            "Gemini 3.5 Flash",
            "Gemini 3.5 Flash-Lite  (fastest)"
    };
    private static final String[] GROQ_MODELS = {
            "openai/gpt-oss-120b", "llama-3.3-70b-versatile", "openai/gpt-oss-20b"
    };
    private static final String[] GROQ_LABELS = {
            "GPT-OSS 120B  (best tutor)",
            "Llama 3.3 70B",
            "GPT-OSS 20B  (fastest)"
    };

    public static String[] models(String provider) {
        return GROQ.equals(provider) ? GROQ_MODELS : GEMINI_MODELS;
    }

    public static String[] modelLabels(String provider) {
        return GROQ.equals(provider) ? GROQ_LABELS : GEMINI_LABELS;
    }

    public static String defaultModel(String provider) {
        return models(provider)[0];
    }

    public static String keyHint(String provider) {
        if (GROQ.equals(provider)) {
            return "Free key from console.groq.com -- no card needed.";
        }
        return "Free key from aistudio.google.com -- no card needed.";
    }

    // ---- calls ------------------------------------------------------------

    public static String chat(Context ctx, String system, List<Model.Msg> turns)
            throws Exception {
        String key = requireKey(ctx);
        String provider = Prefs.provider(ctx);
        String model = Prefs.model(ctx);
        if (GROQ.equals(provider)) return Groq.chat(key, model, system, turns);
        return Gemini.chat(key, model, system, turns);
    }

    public static JSONObject consolidate(Context ctx, String system, List<Model.Msg> turns)
            throws Exception {
        String key = requireKey(ctx);
        String provider = Prefs.provider(ctx);
        String model = Prefs.model(ctx);
        if (GROQ.equals(provider)) return Groq.consolidate(key, model, system, turns);
        return Gemini.consolidate(key, model, system, turns);
    }

    public static String ping(Context ctx) throws Exception {
        List<Model.Msg> turns = new ArrayList<Model.Msg>();
        turns.add(new Model.Msg("user", "Reply with exactly one word: ready", 0));
        return chat(ctx, "You are a connectivity check. Answer in one word.", turns);
    }

    private static String requireKey(Context ctx) throws Exception {
        String key = Prefs.key(ctx);
        if (key.length() < 10) {
            throw new Http.ApiException(0, "No API key yet. Open Setup and paste a free key.");
        }
        return key;
    }

    // ---- the one structured output Recall needs ---------------------------

    /**
     * Schema for the end-of-session learner-model update.
     *
     * @param strict adds additionalProperties:false, which OpenAI-compatible endpoints
     *               require under strict json_schema but Gemini's OpenAPI subset rejects.
     */
    public static JSONObject updateSchema(boolean strict) throws Exception {
        JSONObject concept = object(strict);
        concept.getJSONObject("properties")
                .put("label", str("The concept, named the way a textbook would name it."))
                .put("confidence", integer(
                        "0-100. How solid they are on it NOW, judged from this session only."));
        concept.put("required", new JSONArray().put("label").put("confidence"));

        JSONObject gap = object(strict);
        gap.getJSONObject("properties")
                .put("concept", str("Which concept this gap belongs to."))
                .put("gap", str("The specific wrong belief, one short sentence, in their terms."));
        gap.put("required", new JSONArray().put("concept").put("gap"));

        JSONObject root = object(strict);
        root.getJSONObject("properties")
                .put("title", str("Three to five words naming what this session was about."))
                .put("summary", str("One or two sentences addressed to the learner as 'you', "
                        + "saying what they actually did and where it broke."))
                .put("concepts", array(concept,
                        "Every concept genuinely covered. Omit passing mentions."))
                .put("gaps_opened", array(gap,
                        "Misconceptions they showed that are still unresolved."))
                .put("gaps_resolved", array(str("The exact text of a gap now cleared."),
                        "Previously open gaps this session cleared. Copy their wording exactly."));
        root.put("required", new JSONArray().put("title").put("summary").put("concepts")
                .put("gaps_opened").put("gaps_resolved"));
        return root;
    }

    private static JSONObject object(boolean strict) throws Exception {
        JSONObject o = new JSONObject();
        o.put("type", "object");
        o.put("properties", new JSONObject());
        if (strict) o.put("additionalProperties", false);
        return o;
    }

    private static JSONObject str(String description) throws Exception {
        return new JSONObject().put("type", "string").put("description", description);
    }

    private static JSONObject integer(String description) throws Exception {
        return new JSONObject().put("type", "integer").put("description", description);
    }

    private static JSONObject array(JSONObject items, String description) throws Exception {
        return new JSONObject().put("type", "array").put("items", items)
                .put("description", description);
    }
}
