package com.recall.net;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * One tiny JSON-over-HTTPS poster shared by every provider.
 *
 * No SDK is used anywhere in this app: the APK is built without Gradle or Maven, so it
 * cannot resolve any vendor SDK's dependencies. Raw HTTP against each provider's
 * documented wire format is the supported fallback in that situation.
 */
public class Http {

    public static class ApiException extends Exception {
        public final int status;
        public ApiException(int status, String msg) { super(msg); this.status = status; }
    }

    public interface ErrorReader {
        /** Pull a human message out of a provider's error body, or return null. */
        String message(String body);
    }

    public static JSONObject postJson(String url, String[] headers, JSONObject body,
                                      ErrorReader reader) throws Exception {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestMethod("POST");
            con.setRequestProperty("content-type", "application/json");
            for (int i = 0; i + 1 < headers.length; i += 2) {
                con.setRequestProperty(headers[i], headers[i + 1]);
            }
            con.setConnectTimeout(20000);
            con.setReadTimeout(180000);
            con.setDoOutput(true);

            byte[] payload = body.toString().getBytes("UTF-8");
            con.setFixedLengthStreamingMode(payload.length);
            OutputStream os = con.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int code = con.getResponseCode();
            String text = read(code >= 400 ? con.getErrorStream() : con.getInputStream());
            if (code >= 400) throw new ApiException(code, friendly(code, text, reader));
            try {
                return new JSONObject(text);
            } catch (org.json.JSONException bad) {
                throw new ApiException(0, "The reply was not readable. Try again.");
            }
        } catch (ApiException e) {
            throw e;
        } catch (java.net.SocketTimeoutException e) {
            throw new ApiException(0, "That took too long. Try again.");
        } catch (java.net.UnknownHostException e) {
            throw new ApiException(0, "No connection. Recall needs the internet to tutor.");
        } catch (java.io.IOException e) {
            throw new ApiException(0, "Could not reach the provider. Check the connection and try again.");
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /**
     * Models sometimes wrap a JSON object in prose or a fence. Consolidation has to
     * survive that or the session is lost.
     */
    public static JSONObject parseObject(String raw) throws ApiException {
        String t = raw == null ? "" : raw.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl >= 0) t = t.substring(nl + 1).trim();
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3).trim();
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) t = t.substring(start, end + 1);
        try {
            return new JSONObject(t);
        } catch (Exception bad) {
            throw new ApiException(0, "The tutor's summary was not readable. Try saving again.");
        }
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder b = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) b.append(line).append('\n');
        r.close();
        return b.toString();
    }

    private static String friendly(int code, String rawBody, ErrorReader reader) {
        String detail = null;
        try { detail = reader.message(rawBody); } catch (Exception ignored) { }

        boolean keyProblem = detail != null && detail.toLowerCase(java.util.Locale.US).contains("api key");
        if (code == 401 || code == 403 || keyProblem) {
            return "That API key was rejected. Check it in Setup.";
        }
        if (code == 404) {
            return "That model is not available to your key. Pick another one in Setup."
                    + (detail == null ? "" : "\n\n" + detail);
        }
        if (code == 429) {
            return "You have hit the free tier's rate limit. Wait a minute, or switch model "
                    + "in Setup.";
        }
        if (code >= 500) {
            return "The provider is having trouble right now. Try again shortly.";
        }
        if (detail != null && detail.length() > 0) return detail;
        return "Request failed (HTTP " + code + ").";
    }
}
