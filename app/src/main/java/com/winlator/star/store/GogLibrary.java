package com.winlator.star.store;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Backend for the GOG library: token refresh, owned-game enumeration,
 * per-game metadata fetch, SteamGridDB cover lookup, DLC association, and
 * on-disk caching. Extracted verbatim from the original GogGamesActivity so
 * the Compose UI (GogScreen) can reuse the exact, tested network logic without
 * touching any View code.
 *
 * All methods are blocking and must be called off the main thread.
 */
public final class GogLibrary {

    private static final String TAG = "BH_GOG";
    private static final String PREFS = "bh_gog_prefs";
    private static final String CACHE_KEY = "gog_library_cache";
    private static final String SGDB_KEY = "cf89227f12c773bb1117b6b109ae1659";

    private GogLibrary() {}

    /** Status callback so the UI can show "Fetching game list…" etc. */
    public interface Progress {
        void status(String msg);
    }

    public static boolean isLoggedIn(Context ctx) {
        return prefs(ctx).getString("access_token", null) != null;
    }

    public static String username(Context ctx) {
        return prefs(ctx).getString("username", "Unknown");
    }

    public static void signOut(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, 0);
    }

    // ── OAuth login ──────────────────────────────────────────────────────────

    /**
     * GOG implicit-flow auth URL (tokens arrive in the redirect fragment).
     *
     * NOTE: deliberately omits {@code layout=client2}. That layout serves the
     * GOG Galaxy desktop-client embedded login, an iframe that waits for a
     * parent-window postMessage handshake that doesn't exist in a plain
     * WebView — so it renders blank white. Without it GOG serves the standard
     * responsive web login, which works in a WebView.
     */
    public static final String AUTH_URL =
            "https://auth.gog.com/auth"
            + "?client_id=46899977096215655"
            + "&redirect_uri=https%3A%2F%2Fembed.gog.com%2Fon_login_success%3Forigin%3Dclient"
            + "&response_type=token";

    /** Redirect URL prefix that signals login success. */
    public static final String REDIRECT_PREFIX = "https://embed.gog.com/on_login_success";

    /**
     * Fetches the username via userData.json and persists tokens to prefs.
     * Blocking — call off the main thread. Returns true if tokens were saved.
     */
    public static boolean completeLogin(Context ctx, String accessToken,
                                        String refreshToken, String userId) {
        if (accessToken == null) return false;
        String username = "Unknown";
        try {
            URL url = new URL("https://embed.gog.com/userData.json");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            conn.disconnect();
            String parsed = GogLoginActivity.parseJsonStringField(sb.toString(), "username");
            if (parsed != null) username = parsed;
        } catch (Exception ignored) {}

        SharedPreferences.Editor ed = prefs(ctx).edit();
        ed.putString("access_token", accessToken);
        if (refreshToken != null) ed.putString("refresh_token", refreshToken);
        if (userId != null) ed.putString("user_id", userId);
        ed.putString("username", username);
        int nowSec = (int) (System.currentTimeMillis() / 1000L);
        ed.putInt("bh_gog_login_time", nowSec);
        ed.putInt("bh_gog_expires_in", 3600);
        ed.apply();
        return true;
    }

    // ── Cache ────────────────────────────────────────────────────────────────

    public static List<GogGame> loadCache(Context ctx) {
        String json = prefs(ctx).getString(CACHE_KEY, null);
        if (json == null) return null;
        try {
            JSONArray arr = new JSONArray(json);
            List<GogGame> games = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                games.add(new GogGame(
                        o.getString("gameId"),
                        o.getString("title"),
                        o.optString("imageUrl", ""),
                        o.optString("description", ""),
                        o.optString("developer", ""),
                        o.optString("category", ""),
                        o.optInt("generation", 1)));
            }
            Collections.sort(games, (a, b) -> a.title.compareToIgnoreCase(b.title));
            return games;
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveCache(Context ctx, List<GogGame> games) {
        try {
            JSONArray arr = new JSONArray();
            for (GogGame g : games) {
                JSONObject o = new JSONObject();
                o.put("gameId", g.gameId);
                o.put("title", g.title);
                o.put("imageUrl", g.imageUrl);
                o.put("description", g.description);
                o.put("developer", g.developer);
                o.put("category", g.category);
                o.put("generation", g.generation);
                arr.put(o);
            }
            prefs(ctx).edit().putString(CACHE_KEY, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    // ── Library sync (blocking) ──────────────────────────────────────────────

    /**
     * Syncs the signed-in user's GOG library. Returns the sorted game list, or
     * throws SyncException with a user-facing message on failure.
     */
    public static List<GogGame> sync(Context ctx, Progress progress) throws SyncException {
        SharedPreferences prefs = prefs(ctx);
        status(progress, "Checking token…");

        String token = prefs.getString("access_token", null);
        if (token == null) throw new SyncException("Not logged in");

        int loginTime = prefs.getInt("bh_gog_login_time", 0);
        int expiresIn = prefs.getInt("bh_gog_expires_in", 3600);
        int nowSec = (int) (System.currentTimeMillis() / 1000L);
        if (loginTime == 0 || nowSec >= loginTime + expiresIn) {
            status(progress, "Refreshing token…");
            String newToken = GogTokenRefresh.refresh(ctx);
            if (newToken == null) throw new SyncException("Session expired — please sign in again");
            token = newToken;
        }

        status(progress, "Fetching game list…");
        String gamesJson = httpGet("https://embed.gog.com/user/data/games", token);
        if (gamesJson == null) throw new SyncException("Failed to fetch library");

        List<String> ids = new ArrayList<>();
        try {
            JSONArray ownedArr = new JSONObject(gamesJson).optJSONArray("owned");
            if (ownedArr != null) {
                for (int i = 0; i < ownedArr.length(); i++) {
                    String id = String.valueOf(ownedArr.getLong(i));
                    if (!"1801418160".equals(id)) ids.add(id);
                }
            }
        } catch (Exception e) {
            throw new SyncException("Error parsing library");
        }

        if (ids.isEmpty()) throw new SyncException("No games found in library");

        status(progress, "Syncing " + ids.size() + " games…");

        final String finalToken = token;
        final Map<String, List<String[]>> dlcBuffer = new HashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Future<GogGame>> futures = new ArrayList<>();
        for (String id : ids) {
            futures.add(pool.submit(() -> fetchGame(ctx, id, finalToken, dlcBuffer)));
        }
        pool.shutdown();

        List<GogGame> games = new ArrayList<>();
        for (Future<GogGame> f : futures) {
            try {
                GogGame g = f.get();
                if (g != null) games.add(g);
            } catch (Exception ignored) {}
        }

        saveDlcBuffer(ctx, dlcBuffer);
        Collections.sort(games, (a, b) -> a.title.compareToIgnoreCase(b.title));
        saveCache(ctx, games);
        return games;
    }

    /** Fetches metadata + generation for a single game ID. Returns null to skip. */
    private static GogGame fetchGame(Context ctx, String id, String token,
                                     Map<String, List<String[]>> dlcBuffer) {
        SharedPreferences prefs = prefs(ctx);
        try {
            String productJson = httpGet(
                    "https://api.gog.com/products/" + id + "?expand=downloads,description", token);
            if (productJson == null) return null;

            JSONObject prod = new JSONObject(productJson);
            if (prod.optBoolean("is_secret", false)) return null;
            if ("dlc".equals(prod.optString("game_type"))) {
                storeDlcInBuffer(dlcBuffer, id, prod);
                return null;
            }

            JSONObject titleObj = prod.optJSONObject("title");
            String titleStr = titleObj != null ? titleObj.optString("*") : null;
            if (titleStr == null) titleStr = prod.optString("title");
            if (titleStr == null || titleStr.isEmpty()) return null;

            String imageUrl = sgdbFetchCover(titleStr);
            if (imageUrl.isEmpty()) {
                JSONObject images = prod.optJSONObject("images");
                imageUrl = images != null ? images.optString("icon", "") : "";
                if (imageUrl == null || imageUrl.isEmpty())
                    imageUrl = images != null ? images.optString("background", "") : "";
                if (imageUrl == null) imageUrl = "";
            }

            JSONObject descObj = prod.optJSONObject("description");
            String desc = descObj != null ? descObj.optString("lead", "") : "";
            if (desc == null) desc = "";

            JSONObject company = prod.optJSONObject("developers");
            String developer = company != null ? company.optString("name", "") : prod.optString("developer", "");
            if (developer == null) developer = "";

            JSONArray genres = prod.optJSONArray("genres");
            String category = "";
            if (genres != null && genres.length() > 0) {
                JSONObject g = genres.optJSONObject(0);
                if (g != null) category = g.optString("name", "");
            }

            int generation = 1;
            try {
                String buildsJson = httpGet(
                        "https://api.gog.com/products/" + id + "/os/windows/builds?generation=2", token);
                if (buildsJson != null) {
                    JSONArray bitems = new JSONObject(buildsJson).optJSONArray("items");
                    if (bitems != null && bitems.length() > 0) generation = 2;
                }
            } catch (Exception ignored) {}

            prefs.edit().putInt("gog_gen_" + id, generation).apply();

            String releaseDate = prod.optString("release_date", "");
            if (releaseDate != null && !releaseDate.isEmpty()) {
                prefs.edit().putString("gog_release_" + id, releaseDate).apply();
            }
            int rating = prod.optInt("rating", -1);
            if (rating >= 0) {
                prefs.edit().putInt("gog_rating_" + id, rating).apply();
            }

            if (prefs.getLong("gog_size_" + id, -1) <= 0) {
                long size = GogDownloadManager.fetchInstallSizeBytes(id, token);
                if (size > 0) prefs.edit().putLong("gog_size_" + id, size).apply();
            }

            return new GogGame(id, titleStr, imageUrl, desc, developer, category, generation);
        } catch (Exception e) {
            Log.w(TAG, "fetchGame " + id + " error: " + e.getMessage());
            return null;
        }
    }

    private static synchronized void storeDlcInBuffer(Map<String, List<String[]>> dlcBuffer,
                                                      String dlcId, JSONObject prod) {
        try {
            String dlcTitle = "";
            JSONObject titleObj = prod.optJSONObject("title");
            if (titleObj != null) dlcTitle = titleObj.optString("*", "");
            if (dlcTitle.isEmpty()) dlcTitle = prod.optString("title", "");
            if (dlcTitle.isEmpty()) dlcTitle = "Unknown DLC";

            String baseId = "";
            JSONObject reqGame = prod.optJSONObject("required_game");
            if (reqGame != null) baseId = reqGame.optString("id", "");
            if (baseId.isEmpty()) {
                JSONArray reqArr = prod.optJSONArray("requiredGames");
                if (reqArr != null && reqArr.length() > 0)
                    baseId = reqArr.optString(0, "");
            }
            if (baseId.isEmpty()) return;

            List<String[]> list = dlcBuffer.get(baseId);
            if (list == null) { list = new ArrayList<>(); dlcBuffer.put(baseId, list); }
            list.add(new String[]{dlcId, dlcTitle});
        } catch (Exception e) {
            Log.w(TAG, "storeDlcInBuffer failed: " + e.getMessage());
        }
    }

    private static synchronized void saveDlcBuffer(Context ctx, Map<String, List<String[]>> dlcBuffer) {
        SharedPreferences prefs = prefs(ctx);
        for (Map.Entry<String, List<String[]>> entry : dlcBuffer.entrySet()) {
            try {
                JSONArray arr = new JSONArray();
                for (String[] dlc : entry.getValue()) {
                    JSONObject obj = new JSONObject();
                    obj.put("id", dlc[0]);
                    obj.put("title", dlc[1]);
                    arr.put(obj);
                }
                prefs.edit().putString("gog_dlcs_" + entry.getKey(), arr.toString()).apply();
            } catch (Exception ignored) {}
        }
    }

    // ── Network helpers ──────────────────────────────────────────────────────

    private static String httpGet(String url, String token) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);
            if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token);
            if (conn.getResponseCode() != 200) { conn.disconnect(); return null; }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            conn.disconnect();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns the first SteamGridDB 600x900 cover URL for the title, or "" on failure. */
    private static String sgdbFetchCover(String title) {
        try {
            String encoded = URLEncoder.encode(title, "UTF-8");
            String searchJson = httpGet(
                    "https://www.steamgriddb.com/api/v2/search/autocomplete/" + encoded, SGDB_KEY);
            if (searchJson == null) return "";
            JSONArray results = new JSONObject(searchJson).optJSONArray("data");
            if (results == null || results.length() == 0) return "";
            int gameId = results.getJSONObject(0).getInt("id");

            String gridsJson = httpGet(
                    "https://www.steamgriddb.com/api/v2/grids/game/" + gameId
                            + "?dimensions=600x900&mimes=image/jpeg,image/png&limit=1",
                    SGDB_KEY);
            if (gridsJson == null) return "";
            JSONArray grids = new JSONObject(gridsJson).optJSONArray("data");
            if (grids == null || grids.length() == 0) return "";
            return grids.getJSONObject(0).optString("url", "");
        } catch (Exception e) {
            return "";
        }
    }

    private static void status(Progress p, String msg) {
        if (p != null) p.status(msg);
    }

    /** Thrown by {@link #sync} with a user-facing message. */
    public static class SyncException extends Exception {
        public SyncException(String msg) { super(msg); }
    }
}
