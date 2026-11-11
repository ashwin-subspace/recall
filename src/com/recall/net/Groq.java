package com.recall.net;

import com.recall.data.Model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/** Groq, using its OpenAI-compatible chat completions endpoint. Keys: console.groq.com. */
public class Groq {

    private static final String URL = "https://api.groq.com/openai/v1/chat/completions";

    private static final Http.ErrorReader ERRORS = new Http.ErrorReader() {
        public String message(String body) {
            try {
                JSONObject e = new JSONObject(body).optJSONObject("error");
                return e == null ? null : e.optString("message", null);
            } catch (Exception ignored) { return null; }
        }
    };

    private static String[] headers(String key) {
        return new String[]{"authorization", "Bearer " + key};
    }

    private static JSONObject request(String model, String system, List<Model.Msg> turns,
                                      int maxTokens) throws Exception {
        JSONArray messages = new JSONArray();
        messages.put(msg("system", system));
        for (int i = 0; i < turns.size(); i++) {
            Model.Msg m = turns.get(i);
            messages.put(msg("user".equals(m.role) ? "user" : "assistant", m.text));
        }
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_completion_tokens", maxTokens);
        return body;
    }

    private static JSONObject msg(String role, String content) throws Exception {
        JSONObject m = new JSONObject();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    public static String chat(String key, String model, String system, List<Model.Msg> turns)
            throws Exception {
        JSONObject res = Http.postJson(URL, headers(key),
                request(model, system, turns, 1500), ERRORS);
        return text(res);
    }

    public static JSONObject consolidate(String key, String model, String system,
                                         List<Model.Msg> turns) throws Exception {
        JSONObject body = request(model, system, turns, 4000);

        JSONObject schema = new JSONObject();
        schema.put("name", "update_learner_model");
        schema.put("strict", true);
        schema.put("schema", Ai.updateSchema(true));

        JSONObject format = new JSONObject();
        format.put("type", "json_schema");
        format.put("json_schema", schema);
        body.put("response_format", format);

        try {
            JSONObject res = Http.postJson(URL, headers(key), body, ERRORS);
            return Http.parseObject(text(res));
        } catch (Http.ApiException e) {
            if (!schemaRejected(e)) throw e;
        }
        // Llama on Groq often rejects strict json_schema. json_object still returns JSON.
        JSONObject loose = request(model, system, turns, 4000);
        loose.put("response_format", new JSONObject().put("type", "json_object"));
        JSONObject res = Http.postJson(URL, headers(key), loose, ERRORS);
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
     * Open reasoning models on Groq return their scratchpad in a separate "reasoning"
     * field, which we ignore -- only "content" is the answer.
     */
    private static String text(JSONObject res) throws Exception {
        JSONArray choices = res.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new Http.ApiException(0, "Empty response from Groq.");
        }
        JSONObject c0 = choices.getJSONObject(0);
        JSONObject message = c0.optJSONObject("message");
        String content = message == null ? "" : message.optString("content", "");
        if (content == null || content.trim().length() == 0) {
            String why = c0.optString("finish_reason", "");
            if ("length".equals(why)) {
                throw new Http.ApiException(0, "The reply ran past the length limit. "
                        + "Try a shorter question.");
            }
            throw new Http.ApiException(0, "Empty response from Groq.");
        }
        return content.trim();
    }
}
