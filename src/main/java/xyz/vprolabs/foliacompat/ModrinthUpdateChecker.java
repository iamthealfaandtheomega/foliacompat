package xyz.vprolabs.foliacompat;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public final class ModrinthUpdateChecker {

    private static final String API_URL = "https://api.modrinth.com/v2/project/%s/version";

    private ModrinthUpdateChecker() {}

    public static CompletableFuture<UpdateResult> check(String projectId, String currentVersion, Logger logger) {
        if (projectId == null || projectId.isEmpty()) {
            return CompletableFuture.completedFuture(UpdateResult.none());
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                URI uri = new URI(String.format(API_URL, projectId));
                HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "FoliaCompat/" + currentVersion);
                conn.setRequestProperty("Accept", "application/json");

                int code = conn.getResponseCode();
                if (code != 200) {
                    logger.info("Update check: Modrinth API returned " + code);
                    return UpdateResult.none();
                }

                byte[] data;
                try (InputStream in = conn.getInputStream()) {
                    data = in.readAllBytes();
                }
                String json = new String(data, StandardCharsets.UTF_8);
                List<VersionEntry> versions = parseVersions(json);

                if (versions.isEmpty()) {
                    return UpdateResult.none();
                }

                VersionEntry latest = versions.get(0);

                int cmp = compareVersions(latest.versionNumber, currentVersion);
                if (cmp > 0) {
                    return new UpdateResult(true, latest.versionNumber, latest.downloadUrl);
                }
                return UpdateResult.none();

            } catch (Exception e) {
                logger.info("Update check failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : ""));
                return UpdateResult.none();
            }
        });
    }

    private static List<VersionEntry> parseVersions(String json) {
        List<VersionEntry> versions = new ArrayList<>();
        int idx = 0;
        while (true) {
            idx = json.indexOf('{', idx);
            if (idx < 0) break;
            int end = findMatchingBrace(json, idx);
            if (end < 0) break;
            String obj = json.substring(idx, end + 1);
            idx = end + 1;

            String vnum = extractString(obj, "version_number");
            String vtype = extractString(obj, "version_type");
            String dlUrl = extractPrimaryDownloadUrl(obj);
            if (vnum != null && vtype != null) {
                versions.add(new VersionEntry(vnum, vtype, dlUrl));
            }
        }

        versions.sort((a, b) -> {
            int typeCmp = typePriority(b.vtype) - typePriority(a.vtype);
            if (typeCmp != 0) return typeCmp;
            return compareVersions(b.versionNumber, a.versionNumber);
        });

        return versions;
    }

    private static int typePriority(String type) {
        if (type == null) return 0;
        return switch (type) {
            case "release" -> 4;
            case "beta" -> 3;
            case "alpha" -> 2;
            default -> 1;
        };
    }

    static int compareVersions(String a, String b) {
        String cleanA = a.replaceAll("[^0-9.]", "").replaceAll("^\\.+|\\.+$", "");
        String cleanB = b.replaceAll("[^0-9.]", "").replaceAll("^\\.+|\\.+$", "");
        String[] partsA = cleanA.isEmpty() ? new String[0] : cleanA.split("\\.");
        String[] partsB = cleanB.isEmpty() ? new String[0] : cleanB.split("\\.");
        int max = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < max; i++) {
            int na = i < partsA.length && !partsA[i].isEmpty() ? Integer.parseInt(partsA[i]) : 0;
            int nb = i < partsB.length && !partsB[i].isEmpty() ? Integer.parseInt(partsB[i]) : 0;
            if (na != nb) return Integer.compare(na, nb);
        }
        return 0;
    }

    private static int findMatchingBrace(String s, int open) {
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inString = !inString;
            if (!inString) {
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    private static String extractString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        idx += search.length();
        StringBuilder value = new StringBuilder();
        while (idx < json.length()) {
            char c = json.charAt(idx++);
            if (c == '"') break;
            if (c == '\\' && idx < json.length()) {
                char next = json.charAt(idx++);
                if (next == '"') value.append('"');
                else if (next == '\\') value.append('\\');
                else if (next == 'n') value.append('\n');
                else if (next == 't') value.append('\t');
                else if (next == 'u' && idx + 4 <= json.length()) {
                    value.append((char) Integer.parseInt(json.substring(idx, idx + 4), 16));
                    idx += 4;
                } else value.append(next);
            } else {
                value.append(c);
            }
        }
        return value.toString();
    }

    private static String extractPrimaryDownloadUrl(String json) {
        String search = "\"primary\":true";
        int idx = json.indexOf(search);
        int urlIdx;
        if (idx >= 0) {
            urlIdx = json.lastIndexOf("\"url\":\"", idx);
        } else {
            urlIdx = json.indexOf("\"url\":\"");
        }
        if (urlIdx < 0) return null;
        urlIdx += "\"url\":\"".length();
        StringBuilder url = new StringBuilder();
        while (urlIdx < json.length()) {
            char c = json.charAt(urlIdx++);
            if (c == '"') break;
            if (c == '\\' && urlIdx < json.length()) url.append(json.charAt(urlIdx++));
            else url.append(c);
        }
        return url.toString();
    }

    public static final class UpdateResult {
        public final boolean hasUpdate;
        public final String latestVersion;
        public final String downloadUrl;

        UpdateResult(boolean hasUpdate, String latestVersion, String downloadUrl) {
            this.hasUpdate = hasUpdate;
            this.latestVersion = latestVersion;
            this.downloadUrl = downloadUrl;
        }

        static UpdateResult none() {
            return new UpdateResult(false, null, null);
        }
    }

    private record VersionEntry(String versionNumber, String vtype, String downloadUrl) {}
}
