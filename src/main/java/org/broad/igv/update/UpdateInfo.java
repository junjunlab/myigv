package org.broad.igv.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One GitHub release, reduced to the fields the updater needs.
 *
 * <p>Parsed from the {@code /releases/latest} payload.  The class is deliberately a plain data
 * holder so the download/install code can be exercised with a hand written JSON file
 * ({@code -Dmyigv.update.feed=...}).</p>
 */
public class UpdateInfo {

    /** Release version without the leading {@code v}. */
    public String version = "";

    /** Release tag as published, e.g. {@code v1.0.4}. */
    public String tagName = "";

    /** Web page for the release (used as the manual-download fallback). */
    public String htmlUrl = "";

    /** Release notes (release body), possibly empty. */
    public String releaseNotes = "";

    /** Attached build artifacts. */
    public List<Asset> assets = new ArrayList<>();

    /** One downloadable file attached to the release. */
    public static class Asset {

        public String name = "";
        public String url = "";
        /** Declared size in bytes, or -1 when the feed does not report one. */
        public long size = -1;

        public Asset() {
        }

        public Asset(String name, String url, long size) {
            this.name = name;
            this.url = url;
            this.size = size;
        }

        @Override
        public String toString() {
            return name + " (" + size + " bytes)";
        }
    }

    /**
     * Parse the GitHub release JSON.
     *
     * @param json raw JSON body
     * @return parsed release, or {@code null} when the body is not a release object
     */
    public static UpdateInfo fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            return null;
        }
        if (root == null || !root.isJsonObject()) {
            return null;
        }
        JsonObject object = root.getAsJsonObject();
        UpdateInfo info = new UpdateInfo();
        info.tagName = optString(object, "tag_name");
        info.version = AppVersion.normalize(info.tagName);
        if (info.version.isEmpty()) {
            info.version = AppVersion.normalize(optString(object, "name"));
        }
        info.htmlUrl = optString(object, "html_url");
        info.releaseNotes = optString(object, "body");

        JsonElement assetsElement = object.get("assets");
        if (assetsElement != null && assetsElement.isJsonArray()) {
            JsonArray array = assetsElement.getAsJsonArray();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject assetObject = element.getAsJsonObject();
                Asset asset = new Asset();
                asset.name = optString(assetObject, "name");
                asset.url = optString(assetObject, "browser_download_url");
                if (asset.url.isEmpty()) {
                    asset.url = optString(assetObject, "url");
                }
                JsonElement sizeElement = assetObject.get("size");
                asset.size = sizeElement == null || sizeElement.isJsonNull() ? -1 : sizeElement.getAsLong();
                if (!asset.name.isEmpty() && !asset.url.isEmpty()) {
                    info.assets.add(asset);
                }
            }
        }
        if (info.version.isEmpty()) {
            return null;
        }
        return info;
    }

    private static String optString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return "";
        }
        return element.getAsString().trim();
    }

    /**
     * Pick the artifact the updater should download: the app-image archive first (small, it is
     * just the application directory), then the platform installer.
     *
     * @return the chosen asset, or {@code null} when the release has nothing usable
     */
    public Asset pickPayload() {
        Asset archive = pickBySuffix(isMac() ? "-mac-app.zip" : "-win-app.zip");
        if (archive != null) {
            return archive;
        }
        Asset installer = pickBySuffix(isMac() ? ".dmg" : ".exe");
        if (installer != null) {
            return installer;
        }
        return null;
    }

    /**
     * @param suffix case-insensitive file name suffix, e.g. {@code ".dmg"}
     * @return the first asset whose name ends with {@code suffix}, or {@code null}
     */
    public Asset pickBySuffix(String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            return null;
        }
        String lowerSuffix = suffix.toLowerCase(Locale.ROOT);
        for (Asset asset : assets) {
            if (asset != null && asset.name != null
                    && asset.name.toLowerCase(Locale.ROOT).endsWith(lowerSuffix)) {
                return asset;
            }
        }
        return null;
    }

    public static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    @Override
    public String toString() {
        return "UpdateInfo{version='" + version + "', assets=" + assets + "}";
    }
}
