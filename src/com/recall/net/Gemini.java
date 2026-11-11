package com.recall.net;

import com.recall.data.Model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/** Google Gemini API. Free tier keys come from aistudio.google.com. */
public class Gemini {

    private static final String BASE =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    private static final Http.ErrorReader ERRORS = new Http.ErrorReader() {
        public String message(String body) {
            try {
                JSONObject e = new JSONObject(body).optJSONObject("error");
                return e == null ? null : e.optString("message", null);
            } catch (Exception ignored) { return null; }
        }
    };

    private static String url(String model, String key) {
        return BASE + model + ":generateContent";
    }

    private static String[] headers(String key) {
        return new String[]{"x-goog-api-key", key};
    }

    /** Gemini calls the assistant "model" and carries the system prompt separately. */
    private static JSONObject request(String system, List<Model.Msg> turns, int maxTokens)
            throws Exception {
        JSONObject body = new JSONObject();

        JSONObject sys = new JSONObject();
        sys.put("parts", new JSONArray().put(new JSONObject().put("text", system)));
        body.put("system_instruction", sys);

        JSONArray contents = new JSONArray();
        for (int i = 0; i < turns.size(); i++) {
            Model.Msg m = turns.get(i);
            JSONObject turn = new JSONObject();
            turn.put("role", "user".equals(m.role) ? "user" : "model");
            turn.put("parts", new JSONArray().put(new JSONObject().put("text", m.text)));
            contents.put(turn);
        }
        body.put("contents", contents);

        JSONObject cfg = new JSONObject();
        cfg.put("maxOutputTokens", maxTokens);
        body.put("generationConfig", cfg);
        return body;
    }

    public static String chat(String key, String model, String system, List<Model.Msg> turns)
            throws Exception {
        JSONObject body = request(system, turns, 1500);
        JSONObject res = Http.postJson(url(model, key), headers(key), body, ERRORS);
        return text(res);
    }

    public static JSONObject consolidate(String key, String model, String system,
                                         List<Model.Msg> turns) throws Exception {
        JSONObject body = request(system, turns, 4000);
        JSONObject cfg = body.getJSONObject("generationConfig");
        cfg.put("responseMimeType", "application/json");
        // Gemini takes an OpenAPI-subset schema: no additionalProperties, no strict flag.
        cfg.put("responseSchema", Ai.updateSchema(false));
        try {
            JSONObject res = Http.postJson(url(model, key), headers(key), body, ERRORS);
            return Http.parseObject(text(res));
        } catch (Http.ApiException e) {
            if (!schemaRejected(e)) throw e;
        }
        // Some model ids reject responseSchema and still honour JSON mime type.
        JSONObject loose = request(system, turns, 4000);
        loose.getJSONObject("generationConfig").put("responseMimeType", "application/json");
        JSONObject res = Http.postJson(url(model, key), headers(key), loose, ERRORS);
        return Http.parseObject(text(res));
    }

    private static boolean schemaRejected(Http.ApiException e) {
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.US);
        if (m.indexOf("api key") >= 0 || m.indexOf("rejected") >= 0) return false;
        if (m.indexOf("no connection") >= 0 || m.indexOf("could not reach") >= 0) return false;
        if (m.indexOf("too long") >= 0 || m.indexOf("rate limit") >= 0) return false;
        return e.status == 400 || m.indexOf("not readable") >= 0 || m.indexOf("empty response") >= 0;
    }

    /**
     * Pull the answer out. Gemini 3 Flash models think before answering, and reasoning
     * arrives as parts flagged "thought" -- those must be skipped or they end up in the
     * tutor's speech bubble.
     */
    private static String text(JSONObject res) throws Exception {
        JSONObject feedback = res.optJSONObject("promptFeedback");
        if (feedback != null && feedback.optString("blockReason", "").length() > 0) {
            throw new Http.ApiException(0, "Gemini declined to answer that one ("
                    + feedback.optString("blockReason") + ").");
        }

        JSONArray candidates = res.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new Http.ApiException(0, "Empty response from Gemini.");
        }
        JSONObject c0 = candidates.getJSONObject(0);

        StringBuilder b = new StringBuilder();
        JSONObject content = c0.optJSONObject("content");
        if (content != null) {
            JSONArray parts = content.optJSONArray("parts");
            for (int i = 0; parts != null && i < parts.length(); i++) {
                JSONObject p = parts.optJSONObject(i);
                if (p == null || p.optBoolean("thought", false)) continue;
                String t = p.optString("text", "");
                if (t.length() == 0) continue;
                if (b.length() > 0) b.append("\n\n");
                b.append(t);
            }
        }

        if (b.length() == 0) {
            String why = c0.optString("finishReason", "");
            if ("MAX_TOKENS".equals(why)) {
                throw new Http.ApiException(0, "The reply ran past the length limit. "
                        + "Try a shorter question.");
            }
            if (why.length() > 0 && !"STOP".equals(why)) {
                throw new Http.ApiException(0, "Gemini stopped early (" + why + ").");
            }
            throw new Http.ApiException(0, "Empty response from Gemini.");
        }
        return b.toString().trim();
    }
}
