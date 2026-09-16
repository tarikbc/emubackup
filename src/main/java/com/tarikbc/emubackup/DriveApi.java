package com.tarikbc.emubackup;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Google Drive v3 over plain {@link HttpURLConnection}.
 *
 * <p>No Google SDK. The official client would pull in Guava and a large transitive tree for what
 * amounts to a handful of HTTP calls, and this build vendors dependencies by hand with no
 * shrinker. Raw REST also keeps this class free of {@code android.*}, so the upload logic is
 * JVM-testable.
 *
 * <p>Scope is {@code drive.file}: the app can only see files it created. Google classifies it
 * non-sensitive, so it needs no verification, and unlike {@code drive.appdata} the backups stay
 * visible and downloadable in the user's own Drive. That last part is the no-lock-in rule.
 */
public final class DriveApi {

    private static final String FILES = "https://www.googleapis.com/drive/v3/files";
    private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/files";
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";

    /** A multiple of the required 256 KiB. Balances request overhead against retry cost. */
    static final int CHUNK_BYTES = 8 * 1024 * 1024;

    private static final int MAX_ATTEMPTS = 6;

    public interface TokenSource {
        String accessToken() throws IOException;
        /** Discards the cached token so the next call re-authenticates. */
        void invalidate();
    }

    public interface ProgressListener {
        void onProgress(long bytesSent, long total);
        boolean isCancelled();
    }

    public static final ProgressListener SILENT = new ProgressListener() {
        @Override public void onProgress(long a, long b) {}
        @Override public boolean isCancelled() { return false; }
    };

    public static final class RemoteFile {
        public final String id, name;
        public final long size;
        RemoteFile(String id, String name, long size) {
            this.id = id;
            this.name = name;
            this.size = size;
        }
    }

    /** Thrown when Drive refuses in a way the user has to act on, such as an expired grant. */
    public static final class DriveException extends IOException {
        public final int status;
        public DriveException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final TokenSource tokens;

    public DriveApi(TokenSource tokens) {
        this.tokens = tokens;
    }

    // ------------------------------------------------------------------ folders

    /** Finds a folder by exact name under a parent, or null. */
    public String findFolder(String name, String parentId) throws IOException {
        String url = FILES + "?q=" + enc(buildFolderQuery(name, parentId))
                + "&fields=" + enc("files(id,name)") + "&pageSize=10";
        JSONObject o = json(get(url));
        JSONArray a = o.optJSONArray("files");
        if (a == null || a.length() == 0) return null;
        return a.optJSONObject(0).optString("id", null);
    }

    public String createFolder(String name, String parentId) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("name", name);
            body.put("mimeType", FOLDER_MIME);
            if (parentId != null) body.put("parents", new JSONArray().put(parentId));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        JSONObject o = json(post(FILES + "?fields=id", body.toString()));
        return o.optString("id", null);
    }

    public String ensureFolder(String name, String parentId) throws IOException {
        String id = findFolder(name, parentId);
        return id != null ? id : createFolder(name, parentId);
    }

    public List<RemoteFile> listFolder(String parentId) throws IOException {
        List<RemoteFile> out = new ArrayList<>();
        String pageToken = null;
        do {
            String url = FILES + "?q=" + enc("'" + parentId + "' in parents and trashed=false")
                    + "&fields=" + enc("nextPageToken,files(id,name,size)") + "&pageSize=1000"
                    + (pageToken == null ? "" : "&pageToken=" + enc(pageToken));
            JSONObject o = json(get(url));
            JSONArray a = o.optJSONArray("files");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject f = a.optJSONObject(i);
                    if (f == null) continue;
                    out.add(new RemoteFile(f.optString("id", null), f.optString("name", null),
                            f.optLong("size", 0)));
                }
            }
            pageToken = o.optString("nextPageToken", null);
        } while (pageToken != null && !pageToken.isEmpty());
        return out;
    }

    public void delete(String fileId) throws IOException {
        HttpURLConnection c = open(FILES + "/" + fileId, "DELETE");
        int code = c.getResponseCode();
        if (code != 204 && code != 200 && code != 404) {
            throw new DriveException(code, "delete failed: " + errorText(c));
        }
        c.disconnect();
    }

    public InputStream download(String fileId) throws IOException {
        HttpURLConnection c = open(FILES + "/" + fileId + "?alt=media", "GET");
        // Without this the stack advertises gzip and transparently inflates, which makes
        // Content-Length meaningless for progress reporting.
        c.setRequestProperty("Accept-Encoding", "identity");
        int code = c.getResponseCode();
        if (code != 200) throw new DriveException(code, "download failed: " + errorText(c));
        return c.getInputStream();
    }

    // ------------------------------------------------------------------ upload

    /**
     * Uploads a file with a resumable session, retrying and resuming as needed.
     *
     * @return the created file's id
     */
    public String upload(String parentId, String name, File local, Map<String, String> appProps,
                         ProgressListener listener) throws IOException {
        long total = local.length();
        String session = beginSession(parentId, name, total, appProps);
        long offset = 0;
        int attempts = 0;

        while (true) {
            if (listener.isCancelled()) throw new IOException("cancelled");
            if (attempts++ > MAX_ATTEMPTS) {
                throw new IOException("giving up after " + MAX_ATTEMPTS + " attempts uploading " + name);
            }

            UploadDecision d;
            try {
                d = sendChunk(session, local, offset, total, listener);
            } catch (DriveException e) {
                throw e;
            } catch (IOException e) {
                // A dropped connection is the normal case on a handheld. Ask the server what it
                // actually has rather than assuming, then continue from there.
                d = queryStatus(session, total);
            }

            switch (d.kind) {
                case DONE:
                    listener.onProgress(total, total);
                    return d.detail;
                case CONTINUE:
                    offset = d.nextOffset;
                    attempts = 0;
                    break;
                case RESTART_SESSION:
                    session = beginSession(parentId, name, total, appProps);
                    offset = 0;
                    break;
                case RETRY_AFTER_BACKOFF:
                    sleep(backoffMs(attempts));
                    d = queryStatus(session, total);
                    if (d.kind == UploadDecision.Kind.DONE) return d.detail;
                    if (d.kind == UploadDecision.Kind.CONTINUE) offset = d.nextOffset;
                    break;
                case FAIL:
                default:
                    throw new IOException("upload failed: " + d.detail);
            }
        }
    }

    /**
     * The content type Drive will record for a backup file.
     *
     * <p>Drive believes whatever it is told here and never sniffs the bytes, so declaring
     * everything as a zip made the manifest, the checksums and the restore notes download as
     * unopenable archives. Getting this right is what keeps a backup recoverable by hand from
     * drive.google.com, which {@code docs/FORMAT.md} promises.
     */
    static String mimeFor(String name) {
        if (name == null) return "application/octet-stream";
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".txt")) return "text/plain; charset=utf-8";
        // SHA256SUMS carries no extension and is read by sha256sum -c, so it must stay text.
        if (lower.endsWith("sha256sums")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    private String beginSession(String parentId, String name, long total, Map<String, String> props)
            throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("name", name);
            body.put("mimeType", mimeFor(name));
            if (parentId != null) body.put("parents", new JSONArray().put(parentId));
            if (props != null && !props.isEmpty()) {
                JSONObject p = new JSONObject();
                for (Map.Entry<String, String> e : props.entrySet()) p.put(e.getKey(), e.getValue());
                body.put("appProperties", p);
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }

        HttpURLConnection c = open(UPLOAD + "?uploadType=resumable&fields=id", "POST");
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("X-Upload-Content-Type", mimeFor(name));
        c.setRequestProperty("X-Upload-Content-Length", String.valueOf(total));
        c.setDoOutput(true);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(payload.length);
        try (OutputStream o = c.getOutputStream()) {
            o.write(payload);
        }
        int code = c.getResponseCode();
        if (code != 200 && code != 201) {
            throw new DriveException(code, "could not start upload: " + errorText(c));
        }
        String location = c.getHeaderField("Location");
        c.disconnect();
        if (location == null) throw new IOException("upload session returned no Location header");
        return location;
    }

    private UploadDecision sendChunk(String session, File local, long offset, long total,
                                     ProgressListener listener) throws IOException {
        int len = (int) Math.min(CHUNK_BYTES, total - offset);
        if (len <= 0) return queryStatus(session, total);

        HttpURLConnection c = open(session, "PUT");
        c.setRequestProperty("Content-Range",
                "bytes " + offset + "-" + (offset + len - 1) + "/" + total);
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(len);

        try (FileInputStream in = new FileInputStream(local); OutputStream out = c.getOutputStream()) {
            skipFully(in, offset);
            byte[] buf = new byte[64 * 1024];
            int remaining = len;
            while (remaining > 0) {
                if (listener.isCancelled()) throw new IOException("cancelled");
                int n = in.read(buf, 0, Math.min(buf.length, remaining));
                if (n < 0) break;
                out.write(buf, 0, n);
                remaining -= n;
                listener.onProgress(offset + (len - remaining), total);
            }
        }

        int code = c.getResponseCode();
        String range = c.getHeaderField("Range");
        String bodyText = code >= 200 && code < 300 ? readAll(c.getInputStream()) : errorText(c);
        c.disconnect();

        UploadDecision d = decideUploadResume(code, range, offset + len, total);
        if (d.kind == UploadDecision.Kind.DONE) {
            String id = null;
            try {
                id = new JSONObject(bodyText).optString("id", null);
            } catch (JSONException ignored) {
                // The upload succeeded; a body we cannot parse costs the id, not the data.
            }
            return new UploadDecision(UploadDecision.Kind.DONE, total, id);
        }
        return d;
    }

    /** Asks the server how much it has, with a zero-length PUT. */
    private UploadDecision queryStatus(String session, long total) throws IOException {
        HttpURLConnection c = open(session, "PUT");
        c.setRequestProperty("Content-Range", "bytes */" + total);
        c.setFixedLengthStreamingMode(0);
        c.setDoOutput(true);
        try (OutputStream o = c.getOutputStream()) {
            o.flush();
        }
        int code = c.getResponseCode();
        String range = c.getHeaderField("Range");
        String bodyText = code >= 200 && code < 300 ? readAll(c.getInputStream()) : errorText(c);
        c.disconnect();

        UploadDecision d = decideUploadResume(code, range, -1, total);
        if (d.kind == UploadDecision.Kind.DONE) {
            String id = null;
            try {
                id = new JSONObject(bodyText).optString("id", null);
            } catch (JSONException ignored) {
            }
            return new UploadDecision(UploadDecision.Kind.DONE, total, id);
        }
        return d;
    }

    // ------------------------------------------------------- pure decision logic

    /**
     * Decides what a resumable-upload response means. Pure, so it is tested exhaustively.
     *
     * @param rangeHeader   the {@code Range} response header, or null
     * @param attemptedEnd  the offset just past what was sent, or negative for a status query
     */
    static UploadDecision decideUploadResume(int code, String rangeHeader, long attemptedEnd, long total) {
        if (code == 200 || code == 201) {
            return new UploadDecision(UploadDecision.Kind.DONE, total, null);
        }
        if (code == 308) {
            // 308 means incomplete. An absent Range header means the server has nothing yet,
            // which is different from "as much as we just sent".
            if (rangeHeader == null || rangeHeader.isEmpty()) {
                return new UploadDecision(UploadDecision.Kind.CONTINUE, 0, "no Range header");
            }
            long last = parseRangeEnd(rangeHeader);
            if (last < 0) {
                return new UploadDecision(UploadDecision.Kind.CONTINUE, 0, "unparseable Range: " + rangeHeader);
            }
            long next = last + 1;
            if (next >= total) {
                // Everything is there but the server has not finalised. Query rather than assume.
                return new UploadDecision(UploadDecision.Kind.RETRY_AFTER_BACKOFF, total,
                        "server holds the whole file but has not completed");
            }
            if (attemptedEnd >= 0 && next > attemptedEnd) {
                // The server claims more than was ever sent. Continuing would skip bytes and
                // silently corrupt the archive, so this fails rather than guesses.
                return new UploadDecision(UploadDecision.Kind.FAIL, next,
                        "server reported " + next + " bytes but only " + attemptedEnd + " were sent");
            }
            return new UploadDecision(UploadDecision.Kind.CONTINUE, next, null);
        }
        if (code == 404 || code == 410) {
            return new UploadDecision(UploadDecision.Kind.RESTART_SESSION, 0, "session expired");
        }
        if (code == 429 || (code >= 500 && code < 600)) {
            return new UploadDecision(UploadDecision.Kind.RETRY_AFTER_BACKOFF, 0, "server said " + code);
        }
        if (code == 401 || code == 403) {
            return new UploadDecision(UploadDecision.Kind.FAIL, 0, "not authorised (" + code + ")");
        }
        return new UploadDecision(UploadDecision.Kind.FAIL, 0, "unexpected status " + code);
    }

    /** Parses the end offset from {@code bytes=0-524287}. Returns -1 when unusable. */
    static long parseRangeEnd(String range) {
        int dash = range.lastIndexOf('-');
        if (dash < 0) return -1;
        try {
            return Long.parseLong(range.substring(dash + 1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** A folder lookup query with the name escaped, so an apostrophe cannot break it. */
    static String buildFolderQuery(String name, String parentId) {
        StringBuilder q = new StringBuilder();
        q.append("name='").append(escapeQuery(name)).append("'");
        q.append(" and mimeType='").append(FOLDER_MIME).append("'");
        q.append(" and trashed=false");
        if (parentId != null) q.append(" and '").append(escapeQuery(parentId)).append("' in parents");
        return q.toString();
    }

    static String escapeQuery(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    static long backoffMs(int attempt) {
        return Math.min(30_000L, 1000L * (1L << Math.min(attempt, 5)));
    }

    // ------------------------------------------------------------------ plumbing

    private HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(30_000);
        c.setReadTimeout(120_000);
        // Mandatory. 308 is "Permanent Redirect" in HTTP terms, and the underlying stack will
        // happily follow it, destroying the resumable protocol in a way that is baffling to debug.
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Authorization", "Bearer " + tokens.accessToken());
        return c;
    }

    private String get(String url) throws IOException {
        HttpURLConnection c = open(url, "GET");
        int code = c.getResponseCode();
        if (code == 401) {
            tokens.invalidate();
            throw new DriveException(401, "Drive access expired");
        }
        if (code != 200) throw new DriveException(code, errorText(c));
        String body = readAll(c.getInputStream());
        c.disconnect();
        return body;
    }

    private String post(String url, String jsonBody) throws IOException {
        HttpURLConnection c = open(url, "POST");
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setDoOutput(true);
        byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(payload.length);
        try (OutputStream o = c.getOutputStream()) {
            o.write(payload);
        }
        int code = c.getResponseCode();
        if (code == 401) {
            tokens.invalidate();
            throw new DriveException(401, "Drive access expired");
        }
        if (code != 200 && code != 201) throw new DriveException(code, errorText(c));
        String body = readAll(c.getInputStream());
        c.disconnect();
        return body;
    }

    private static JSONObject json(String body) throws IOException {
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            throw new IOException("Drive returned unparseable JSON", e);
        }
    }

    /** {@code getInputStream} throws for any status at or above 400; the detail is on the error stream. */
    private static String errorText(HttpURLConnection c) {
        try {
            InputStream in = c.getErrorStream();
            return in == null ? "" : readAll(in);
        } catch (IOException e) {
            return "";
        }
    }

    static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) {
                if (in.read() < 0) throw new IOException("unexpected end of file while seeking");
                left--;
            } else {
                left -= s;
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, String> props(String version, String target, String kind) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("eb.version", version);
        if (target != null) m.put("eb.target", target);
        m.put("eb.kind", kind);
        return m;
    }
}
