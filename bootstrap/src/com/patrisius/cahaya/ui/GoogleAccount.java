package com.patrisius.cahaya.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Google sign-in for Cahaya via the OAuth 2.0 device authorization flow
 * (Google's flow for TVs and limited-input devices), v0.10.8.
 *
 * Why device flow, not Credential Manager / Firebase: Cahaya's working
 * build is the zero-dependency manual pipeline (no Gradle, no Jetpack or
 * Firebase AARs), and a TV remote can't type a password. Device flow
 * needs only HttpURLConnection + org.json, both in the framework:
 *   1. POST /device/code -> user_code + verification_url
 *   2. TV shows the code; user enters it at google.com/device on a phone
 *   3. TV polls /token until approval, then fetches /userinfo
 *
 * v0.10.10 correction: Google's device-flow token endpoint DOES
 * require client_secret in the /token poll (and refresh) requests —
 * see developers.google.com/identity/protocols/oauth2/limited-input-device,
 * Step 4. v0.10.9 omitted it, so Google answered every poll with
 * HTTP 400 "Missing required parameter: client_secret", and the old
 * poll loop swallowed all non-200 responses as "keep waiting" — hence
 * a phone that said "Berhasil!" while the TV dialog never moved.
 * For installed TV apps Google treats this secret as distributed with
 * the app, not a server secret, and the scopes here are only
 * email/profile. Even so, the real value is NEVER committed to the
 * public repo: it is injected at build time from the Notion vault
 * ("Cahaya Google OAuth") and the committed source keeps the
 * placeholder below. A build from git alone will show the missing-
 * secret error in the dialog instead of hanging.
 */
public class GoogleAccount {

    public static final String CLIENT_ID = "1075062656076-6ddc1c67off2sqsjg7peejqlfkv9pmi6.apps.googleusercontent.com";
    /** Injected at build time from Notion; placeholder in git. */
    public static final String CLIENT_SECRET = "YOUR_TV_CLIENT_SECRET";
    public static final String SCOPE = "email profile";

    private static final String DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo";
    private static final String REVOKE_URL = "https://oauth2.googleapis.com/revoke";

    private static final String PREFS = "cahaya_google_account";

    public interface Callback<T> {
        void onResult(T result);
        void onError(Exception e);
    }

    public static class DeviceCode {
        public String deviceCode;
        public String userCode;
        public String verificationUrl;
        public String verificationUrlComplete;
        public int expiresIn = 1800;
        public int interval = 5;
    }

    public static class Profile {
        public String name;
        public String email;
        public String picture;
    }

    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());

    public static boolean isConfigured() {
        return CLIENT_ID != null && !CLIENT_ID.startsWith("YOUR_");
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isSignedIn(Context ctx) {
        return prefs(ctx).getString("refresh_token", null) != null;
    }

    public static String displayName(Context ctx) {
        return prefs(ctx).getString("name", null);
    }

    public static String displayEmail(Context ctx) {
        return prefs(ctx).getString("email", null);
    }

    /** Step 1: ask Google for a user code to display on the TV. */
    public static void requestDeviceCode(final Callback<DeviceCode> cb) {
        io.execute(() -> {
            try {
                String body = "client_id=" + URLEncoder.encode(CLIENT_ID, "UTF-8")
                        + "&scope=" + URLEncoder.encode(SCOPE, "UTF-8");
                JSONObject j = new JSONObject(postForm(DEVICE_CODE_URL, body));
                DeviceCode d = new DeviceCode();
                d.deviceCode = j.optString("device_code", "");
                d.userCode = j.optString("user_code", "");
                d.verificationUrl = j.optString("verification_url",
                        "https://www.google.com/device");
                d.verificationUrlComplete = j.optString("verification_url_complete", "");
                d.expiresIn = j.optInt("expires_in", 1800);
                d.interval = j.optInt("interval", 5);
                if (d.deviceCode.isEmpty() || d.userCode.isEmpty()) {
                    throw new Exception("Google returned no device code");
                }
                post(cb, d, null);
            } catch (Exception e) {
                post(cb, null, e);
            }
        });
    }

    /**
     * Step 2: poll /token until the user approves on their phone.
     * Returns a cancellable task; call cancel() when the dialog closes.
     * On success the profile is stored and the callback fires on the
     * main thread with the signed-in profile.
     */
    public static class PollTask {
        private volatile boolean cancelled = false;

        public void cancel() {
            cancelled = true;
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    public static PollTask pollForToken(final Context ctx, final DeviceCode d,
                                        final Callback<Profile> cb) {
        final PollTask task = new PollTask();
        io.execute(() -> {
            try {
                int intervalSec = Math.max(5, d.interval);
                long deadline = System.currentTimeMillis()
                        + (long) d.expiresIn * 1000L;
                while (!task.cancelled && System.currentTimeMillis() < deadline) {
                    Thread.sleep((long) intervalSec * 1000L);
                    if (task.cancelled) {
                        return;
                    }
                    String body = "client_id=" + URLEncoder.encode(CLIENT_ID, "UTF-8")
                            + "&client_secret=" + URLEncoder.encode(CLIENT_SECRET, "UTF-8")
                            + "&device_code=" + URLEncoder.encode(d.deviceCode, "UTF-8")
                            + "&grant_type=urn:ietf:params:oauth:grant-type:device_code";
                    JSONObject j;
                    try {
                        // Poll responses are HTTP 400 for pending/denied/
                        // expired, so parse the body instead of throwing
                        // it away: a swallowed 400 was the v0.10.9 hang.
                        j = new JSONObject(postFormRaw(TOKEN_URL, body));
                    } catch (Exception e) {
                        // Network blip while polling: keep waiting until deadline.
                        continue;
                    }
                    if (j.has("access_token")) {
                        String access = j.optString("access_token", "");
                        String refresh = j.optString("refresh_token", "");
                        long expiresAt = System.currentTimeMillis()
                                + (long) j.optInt("expires_in", 3600) * 1000L;
                        Profile p = fetchUserInfo(access);
                        prefs(ctx).edit()
                                .putString("access_token", access)
                                .putString("refresh_token", refresh)
                                .putLong("expires_at", expiresAt)
                                .putString("name", p.name)
                                .putString("email", p.email)
                                .putString("picture", p.picture != null ? p.picture : "")
                                .apply();
                        post(cb, p, null);
                        return;
                    }
                    String err = j.optString("error", "");
                    if ("authorization_pending".equals(err)) {
                        continue;
                    } else if ("slow_down".equals(err)) {
                        intervalSec += 5;
                    } else if ("access_denied".equals(err)) {
                        throw new Exception("Sign-in denied on your phone");
                    } else if ("expired_token".equals(err)) {
                        throw new Exception("Code expired — please try again");
                    } else {
                        String desc = j.optString("error_description", "");
                        throw new Exception("Google sign-in failed: " + err
                                + (desc.isEmpty() ? "" : " — " + desc));
                    }
                }
                if (!task.cancelled) {
                    throw new Exception("Code expired — please try again");
                }
            } catch (Exception e) {
                if (!task.cancelled) {
                    post(cb, null, e);
                }
            }
        });
        return task;
    }

    /** Refresh the access token from the stored refresh token. */
    public static void refreshAccessToken(final Context ctx,
                                          final Callback<Boolean> cb) {
        io.execute(() -> {
            try {
                String refresh = prefs(ctx).getString("refresh_token", null);
                if (refresh == null) {
                    post(cb, false, null);
                    return;
                }
                String body = "client_id=" + URLEncoder.encode(CLIENT_ID, "UTF-8")
                        + "&client_secret=" + URLEncoder.encode(CLIENT_SECRET, "UTF-8")
                        + "&refresh_token=" + URLEncoder.encode(refresh, "UTF-8")
                        + "&grant_type=refresh_token";
                JSONObject j = new JSONObject(postFormRaw(TOKEN_URL, body));
                if (!j.has("access_token")) {
                    // Refresh token revoked/expired: sign the user out locally.
                    prefs(ctx).edit().clear().apply();
                    post(cb, false, null);
                    return;
                }
                long expiresAt = System.currentTimeMillis()
                        + (long) j.optInt("expires_in", 3600) * 1000L;
                prefs(ctx).edit()
                        .putString("access_token", j.optString("access_token", ""))
                        .putLong("expires_at", expiresAt)
                        .apply();
                post(cb, true, null);
            } catch (Exception e) {
                post(cb, null, e);
            }
        });
    }

    /** Sign out: revoke the refresh token at Google, then clear local state. */
    public static void signOut(final Context ctx, final Callback<Void> cb) {
        io.execute(() -> {
            try {
                String refresh = prefs(ctx).getString("refresh_token", null);
                if (refresh != null) {
                    try {
                        postForm(REVOKE_URL, "token="
                                + URLEncoder.encode(refresh, "UTF-8"));
                    } catch (Exception ignored) {
                        // Revocation is best-effort; local sign-out still stands.
                    }
                }
            } finally {
                prefs(ctx).edit().clear().apply();
                post(cb, null, null);
            }
        });
    }

    private static Profile fetchUserInfo(String accessToken) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(USERINFO_URL).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Authorization", "Bearer " + accessToken);
        c.setRequestProperty("Accept", "application/json");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new Exception("Could not fetch Google profile (HTTP " + code + ")");
        }
        BufferedReader r = new BufferedReader(
                new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line);
        }
        r.close();
        JSONObject j = new JSONObject(sb.toString());
        Profile p = new Profile();
        p.name = j.optString("name", j.optString("email", "Google user"));
        p.email = j.optString("email", "");
        p.picture = j.optString("picture", "");
        return p;
    }

    /** POST and return the body whatever the HTTP status (callers parse
     *  error JSON themselves). Throws only on a real network failure. */
    private static String postFormRaw(String urlStr, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("Accept", "application/json");
        OutputStream out = c.getOutputStream();
        out.write(body.getBytes("UTF-8"));
        out.flush();
        out.close();
        int code = c.getResponseCode();
        java.io.InputStream in = code >= 200 && code < 300
                ? c.getInputStream() : c.getErrorStream();
        if (in == null) {
            return "";
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line);
        }
        r.close();
        return sb.toString();
    }

    private static String postForm(String urlStr, String body) throws Exception {
        String resp = postFormRaw(urlStr, body);
        // postForm callers (device-code, revoke) treat any error body
        // as a failure; the poll loop uses postFormRaw directly.
        // Re-check status via a second look is not available here, so
        // treat a body containing an "error" field as a failure.
        JSONObject j = null;
        try {
            j = new JSONObject(resp);
        } catch (Exception ignored) {
        }
        if (j != null && j.has("error")) {
            throw new Exception("Google error: " + resp);
        }
        return resp;
    }

    private static <T> void post(Callback<T> cb, T result, Exception e) {
        main.post(() -> {
            if (e == null) {
                cb.onResult(result);
            } else {
                cb.onError(e);
            }
        });
    }
}
