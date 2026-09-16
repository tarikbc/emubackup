package com.tarikbc.emubackup;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The upload offset arithmetic, tested exhaustively without a network.
 *
 * <p>Resumable uploads fail in ways that are painful to debug live, and a wrong offset does not
 * error — it silently writes a corrupt archive. So the decision is a pure function and every
 * branch has a case here.
 */
class DriveTest {

    private static final long MB8 = 8L * 1024 * 1024;

    private static UploadDecision d(int code, String range, long attemptedEnd, long total) {
        return DriveApi.decideUploadResume(code, range, attemptedEnd, total);
    }

    @Test
    @DisplayName("200 and 201 both mean the upload finished")
    void completion() {
        assertEquals(UploadDecision.Kind.DONE, d(200, null, MB8, MB8).kind);
        assertEquals(UploadDecision.Kind.DONE, d(201, null, MB8, MB8).kind);
    }

    @Test
    @DisplayName("308 with a Range continues from one past the last byte held")
    void incompleteWithRange() {
        UploadDecision r = d(308, "bytes=0-8388607", MB8, 100 * MB8);
        assertEquals(UploadDecision.Kind.CONTINUE, r.kind);
        assertEquals(MB8, r.nextOffset, "next offset must be last byte + 1");
    }

    @Test
    @DisplayName("308 without a Range means the server has nothing yet, not everything sent")
    void incompleteWithoutRange() {
        UploadDecision r = d(308, null, MB8, 100 * MB8);
        assertEquals(UploadDecision.Kind.CONTINUE, r.kind);
        assertEquals(0, r.nextOffset);
    }

    @Test
    @DisplayName("a server claiming more bytes than were sent fails rather than skipping data")
    void impossibleRangeFails() {
        // Continuing here would leave a hole in the archive and report success.
        UploadDecision r = d(308, "bytes=0-99999999", MB8, 100 * MB8);
        assertEquals(UploadDecision.Kind.FAIL, r.kind);
        assertTrue(r.detail.contains("only"));
    }

    @Test
    @DisplayName("a server holding the whole file but not finalising is queried, not assumed done")
    void fullRangeButNotComplete() {
        UploadDecision r = d(308, "bytes=0-" + (MB8 - 1), MB8, MB8);
        assertEquals(UploadDecision.Kind.RETRY_AFTER_BACKOFF, r.kind);
    }

    @Test
    @DisplayName("an expired session restarts from the beginning")
    void expiredSession() {
        assertEquals(UploadDecision.Kind.RESTART_SESSION, d(404, null, MB8, MB8).kind);
        assertEquals(UploadDecision.Kind.RESTART_SESSION, d(410, null, MB8, MB8).kind);
    }

    @Test
    @DisplayName("rate limits and server errors back off")
    void transientErrors() {
        assertEquals(UploadDecision.Kind.RETRY_AFTER_BACKOFF, d(429, null, MB8, MB8).kind);
        assertEquals(UploadDecision.Kind.RETRY_AFTER_BACKOFF, d(500, null, MB8, MB8).kind);
        assertEquals(UploadDecision.Kind.RETRY_AFTER_BACKOFF, d(503, null, MB8, MB8).kind);
    }

    @Test
    @DisplayName("an auth failure is fatal rather than retried forever")
    void authFailureIsFatal() {
        assertEquals(UploadDecision.Kind.FAIL, d(401, null, MB8, MB8).kind);
        assertEquals(UploadDecision.Kind.FAIL, d(403, null, MB8, MB8).kind);
    }

    @Test void unparseableRangeRestartsFromZeroRatherThanGuessing() {
        UploadDecision r = d(308, "bytes=garbage", MB8, 100 * MB8);
        assertEquals(UploadDecision.Kind.CONTINUE, r.kind);
        assertEquals(0, r.nextOffset);
    }

    @Test void rangeParsing() {
        assertEquals(8388607, DriveApi.parseRangeEnd("bytes=0-8388607"));
        assertEquals(-1, DriveApi.parseRangeEnd("nonsense"));
        assertEquals(-1, DriveApi.parseRangeEnd("bytes=0-abc"));
    }

    @Test
    @DisplayName("backoff grows and is capped")
    void backoff() {
        assertTrue(DriveApi.backoffMs(1) < DriveApi.backoffMs(3));
        assertEquals(30_000L, DriveApi.backoffMs(99));
    }

    @Test
    @DisplayName("an apostrophe in a folder name cannot break the query")
    void queryEscaping() {
        String q = DriveApi.buildFolderQuery("Tarik's Saves", "parent123");
        assertTrue(q.contains("\\'"), "apostrophe was not escaped: " + q);
        assertTrue(q.contains("trashed=false"));
        assertTrue(q.contains("'parent123' in parents"));
    }

    @Test
    @DisplayName("every file in a version declares a content type Drive can serve")
    void mimeTypes() {
        assertEquals("application/zip", DriveApi.mimeFor("eden-saves.full.zip"));
        assertEquals("application/json", DriveApi.mimeFor("manifest.json"));
        assertEquals("application/json", DriveApi.mimeFor("index.json"));
        assertEquals("text/plain; charset=utf-8", DriveApi.mimeFor("RESTORE.txt"));
        // No extension, and it has to stay text or sha256sum -c cannot read a downloaded copy.
        assertEquals("text/plain; charset=utf-8", DriveApi.mimeFor("SHA256SUMS"));
        assertEquals("application/octet-stream", DriveApi.mimeFor("unexpected"));
        assertEquals("application/octet-stream", DriveApi.mimeFor(null));
        // Case cannot decide the type: Drive shows what it is told, whatever the shell wrote.
        assertEquals("application/zip", DriveApi.mimeFor("EDEN-SAVES.FULL.ZIP"));
    }

    @Test void archiveNamesMapBackToTargets() {
        assertEquals("eden-saves", DriveSink.targetOf("eden-saves.full.zip"));
        assertEquals("ps2-states", DriveSink.targetOf("ps2-states.inc.zip"));
        assertEquals("manifest", DriveSink.kindOf("manifest.json"));
        assertEquals("archive", DriveSink.kindOf("eden-saves.full.zip"));
        assertEquals("index", DriveSink.kindOf("index.json"));
    }
}
