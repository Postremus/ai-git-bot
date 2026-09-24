package org.remus.giteabot.azuredevops;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;

class AzureDevopsApiClientDiffTest {

    private static final String MERGE_BASE = "1111111111111111111111111111111111111111";
    private static final String TARGET_TIP = "2222222222222222222222222222222222222222";
    private static final String HEAD_SHA = "3333333333333333333333333333333333333333";

    private static RepositoryCredentials creds() {
        return RepositoryCredentials.of(
                "https://dev.azure.com", "https://dev.azure.com", "ado_pat");
    }

    @Test
    void getPullRequestDiff_buildsUnifiedDiffFromChangesAndBlobs() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true).build();

        // PR details: base and head commit ids
        server.expect(manyTimes(), anything()).andRespond(request -> {
            String uri = request.getURI().toString();
            String json;
            if (uri.contains("/iterations?")) {
                json = "{\"value\":[{\"id\":1},{\"id\":2}]}";
            } else if (uri.contains("/iterations/2/changes")) {
                json = "{\"changeEntries\":[{\"item\":{\"path\":\"/src/Foo.java\"},"
                        + "\"changeType\":\"edit\"}]}";
            } else if (uri.contains("items?") && uri.contains("base")) {
                json = "{\"content\":\"line1\\nline2\\nline3\\n\"}";
            } else if (uri.contains("items?")) {
                json = "{\"content\":\"line1\\nCHANGED\\nline3\\n\"}";
            } else {
                json = "{\"pullRequestId\":42,"
                        + "\"lastMergeTargetCommit\":{\"commitId\":\"base\"},"
                        + "\"lastMergeSourceCommit\":{\"commitId\":\"head\"}}";
            }
            return withSuccess(json, MediaType.APPLICATION_JSON).createResponse(request);
        });

        String diff = new AzureDevopsApiClient(builder.build(), creds())
                .getPullRequestDiff("contoso", "MyProject/my-service", 42L);

        assertNotNull(diff);
        assertTrue(diff.contains("diff --git a/src/Foo.java b/src/Foo.java"), "was:\n" + diff);
        assertTrue(diff.contains("-line2"), "was:\n" + diff);
        assertTrue(diff.contains("+CHANGED"), "was:\n" + diff);
    }

    @Test
    void getPullRequestDiff_undeleteRendersAsAnAdditionNotADeletion() {
        // "undelete" contains "delete" as a substring. Matching flags by substring would
        // render a restored file as removed — the exact inverse of what happened.
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true).build();
        server.expect(manyTimes(), anything()).andRespond(request -> {
            String uri = request.getURI().toString();
            String json;
            if (uri.contains("/iterations?")) {
                json = "{\"value\":[{\"id\":1,\"commonRefCommit\":{\"commitId\":\""
                        + MERGE_BASE + "\"}}]}";
            } else if (uri.contains("/iterations/1/changes")) {
                json = "{\"changeEntries\":[{\"item\":{\"path\":\"/src/Restored.java\"},"
                        + "\"changeType\":\"undelete\"}]}";
            } else if (uri.contains("items?")) {
                json = "{\"content\":\"back again\\n\"}";
            } else {
                json = "{\"pullRequestId\":42,\"lastMergeSourceCommit\":{\"commitId\":\""
                        + HEAD_SHA + "\"}}";
            }
            return withSuccess(json, MediaType.APPLICATION_JSON).createResponse(request);
        });

        String diff = new AzureDevopsApiClient(builder.build(), creds())
                .getPullRequestDiff("contoso", "MyProject/my-service", 42L);

        assertNotNull(diff);
        assertTrue(diff.contains("--- /dev/null"), "the old side must be absent:\n" + diff);
        assertTrue(diff.contains("+++ b/src/Restored.java"), "was:\n" + diff);
        assertTrue(diff.contains("+back again"), "was:\n" + diff);
        assertFalse(diff.contains("-back again"), "must not render as a deletion:\n" + diff);
    }

    @Test
    void getPullRequestDiff_changeEntryWithIdenticalSidesDoesNotSpendAFileBudgetSlot() {
        // A change entry whose two sides turn out identical renders nothing, so like a
        // folder it must cost neither a MAX_FILES slot nor an "omitted" tally entry.
        // Exactly MAX_FILES + 1 entries, one of them a no-op: counting the no-op would
        // push the last real file over the cap and report a file as omitted that the
        // reviewer could have had.
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true).build();
        StringBuilder entries = new StringBuilder(
                "{\"item\":{\"path\":\"/noop.txt\"},\"changeType\":\"edit\"}");
        for (int i = 0; i < AzureDevopsDiffBuilder.MAX_FILES; i++) {
            entries.append(",{\"item\":{\"path\":\"/file").append(i).append(".txt\"},")
                    .append("\"changeType\":\"edit\"}");
        }
        String changesJson = "{\"changeEntries\":[" + entries + "]}";

        server.expect(manyTimes(), anything()).andRespond(request -> {
            String uri = request.getURI().toString();
            String json;
            if (uri.contains("/iterations?")) {
                json = "{\"value\":[{\"id\":1,\"commonRefCommit\":{\"commitId\":\""
                        + MERGE_BASE + "\"}}]}";
            } else if (uri.contains("/changes")) {
                json = changesJson;
            } else if (uri.contains("items?")) {
                // noop.txt reads the same on both sides; every other file changed.
                json = uri.contains("noop.txt") || uri.contains(MERGE_BASE)
                        ? "{\"content\":\"unchanged\\n\"}"
                        : "{\"content\":\"changed\\n\"}";
            } else {
                json = "{\"lastMergeSourceCommit\":{\"commitId\":\"" + HEAD_SHA + "\"}}";
            }
            return withSuccess(json, MediaType.APPLICATION_JSON).createResponse(request);
        });

        String diff = new AzureDevopsApiClient(builder.build(), creds())
                .getPullRequestDiff("contoso", "MyProject/my-service", 42L);

        assertNotNull(diff);
        assertFalse(diff.contains("noop.txt"), "an identical file renders nothing:\n" + diff);
        assertFalse(diff.contains("Diff truncated"),
                "the no-op entry must not push a real file out of the budget:\n"
                        + diff.substring(Math.max(0, diff.length() - 200)));
        int fileBlocks = diff.split("diff --git a/", -1).length - 1;
        assertEquals(AzureDevopsDiffBuilder.MAX_FILES, fileBlocks,
                "every real file should be rendered");
    }

    @Test
    void getPullRequestDiff_returnsNullOnFailure() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(manyTimes(), anything())
                .andRespond(withSuccess("not json", MediaType.TEXT_PLAIN));

        assertNull(new AzureDevopsApiClient(builder.build(), creds())
                .getPullRequestDiff("contoso", "MyProject/my-service", 42L));
    }

    @Test
    void getPullRequestDiff_leadingSlashStrippedFromPaths() {
        // Azure DevOps item paths start with '/', git diff headers must not.
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true).build();
        server.expect(manyTimes(), anything()).andRespond(request -> {
            String uri = request.getURI().toString();
            String json;
            if (uri.contains("/iterations?")) {
                json = "{\"value\":[{\"id\":1}]}";
            } else if (uri.contains("/changes")) {
                json = "{\"changeEntries\":[{\"item\":{\"path\":\"/a.txt\"},"
                        + "\"changeType\":\"add\"}]}";
            } else if (uri.contains("items?")) {
                json = "{\"content\":\"new\\n\"}";
            } else {
                json = "{\"lastMergeTargetCommit\":{\"commitId\":\"base\"},"
                        + "\"lastMergeSourceCommit\":{\"commitId\":\"head\"}}";
            }
            return withSuccess(json, MediaType.APPLICATION_JSON).createResponse(request);
        });

        String diff = new AzureDevopsApiClient(builder.build(), creds())
                .getPullRequestDiff("contoso", "MyProject/my-service", 42L);

        assertTrue(diff.contains("diff --git a/a.txt b/a.txt"), "was:\n" + diff);
        assertFalse(diff.contains("a//a.txt"), "leading slash must be stripped:\n" + diff);
        // An added file has no old side.
        assertTrue(diff.contains("--- /dev/null"), "was:\n" + diff);
        assertTrue(diff.contains("+++ b/a.txt"), "was:\n" + diff);
    }

    @Test
    void getPullRequestDiff_deleteFetchesOnlyBaseBlob() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true).build();
        AtomicInteger itemsCalls = new AtomicInteger();
        server.expect(manyTimes(), anything()).andRespond(request -> {
            String uri = request.getURI().toString();
            String json;
            if (uri.contains("/iterations?")) {
                json = "{\"value\":[{\"id\":1}]}";
            } else if (uri.contains("/changes")) {
                json = "{\"changeEntries\":[{\"item\":{\"path\":\"/gone.txt\"},"
                        + "\"changeType\":\"delete\"}]}";
            } else if (uri.contains("items?")) {
                itemsCalls.incrementAndGet();
                assertTrue(uri.contains("base"),
                        "delete must fetch the base blob only, was: " + uri);
                json = "{\"content\":\"line1\\nline2\\n\"}";
            } else {
                json = "{\"lastMergeTargetCommit\":{\"commitId\":\"base\"},"
                        + "\"lastMergeSourceCommit\":{\"commitId\":\"head\"}}";
            }
            return withSuccess(json, MediaType.APPLICATION_JSON).createResponse(request);
        });

        String diff = new AzureDevopsApiClient(builder.build(), creds())
                .getPullRequestDiff("contoso", "MyProject/my-service", 42L);

        assertEquals(1, itemsCalls.get(), "delete must fetch exactly one blob (base)");
        assertTrue(diff.contains("diff --git a/gone.txt b/gone.txt"), "was:\n" + diff);
        // /dev/null is what tells ChangedFileContentsEnricher not to read the file at
        // the head commit, and DiffSummary that it was removed rather than changed.
        assertTrue(diff.contains("--- a/gone.txt"), "was:\n" + diff);
        assertTrue(diff.contains("+++ /dev/null"), "was:\n" + diff);
        assertTrue(diff.contains("-line1"), "was:\n" + diff);
        assertTrue(diff.contains("-line2"), "was:\n" + diff);
        assertFalse(diff.contains("+line1"), "delete must not show insertions:\n" + diff);
    }

    @Test
    void getPullRequestDiff_renameEmitsSkippedStubWithoutFetchingContent() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true).build();
        AtomicInteger itemsCalls = new AtomicInteger();
        server.expect(manyTimes(), anything()).andRespond(request -> {
            String uri = request.getURI().toString();
            String json;
            if (uri.contains("/iterations?")) {
                json = "{\"value\":[{\"id\":1}]}";
            } else if (uri.contains("/changes")) {
                json = "{\"changeEntries\":[{\"item\":{\"path\":\"/renamed.txt\"},"
                        + "\"changeType\":\"rename\"}]}";
            } else if (uri.contains("items?")) {
                itemsCalls.incrementAndGet();
                json = "{\"content\":\"should never be fetched\"}";
            } else {
                json = "{\"lastMergeTargetCommit\":{\"commitId\":\"base\"},"
                        + "\"lastMergeSourceCommit\":{\"commitId\":\"head\"}}";
            }
            return withSuccess(json, MediaType.APPLICATION_JSON).createResponse(request);
        });

        String diff = new AzureDevopsApiClient(builder.build(), creds())
                .getPullRequestDiff("contoso", "MyProject/my-service", 42L);

        assertEquals(0, itemsCalls.get(), "rename must not fetch blob content");
        assertTrue(diff.contains("diff --git a/renamed.txt b/renamed.txt"), "was:\n" + diff);
        assertTrue(diff.contains("Diff omitted: file renamed"), "was:\n" + diff);
    }

    @Test
    void getPullRequestDiff_renameStubNamesBothPaths() {
        // A pure rename has no delta to show, so the header is the only place the
        // reviewer can see where the file went.
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true).build();
        server.expect(manyTimes(), anything()).andRespond(request -> {
            String uri = request.getURI().toString();
            String json;
            if (uri.contains("/iterations?")) {
                json = "{\"value\":[{\"id\":1}]}";
            } else if (uri.contains("/changes")) {
                json = "{\"changeEntries\":[{\"item\":{\"path\":\"/src/New.java\"},"
                        + "\"sourceServerItem\":\"/src/Old.java\","
                        + "\"changeType\":\"rename\"}]}";
            } else {
                json = "{\"lastMergeTargetCommit\":{\"commitId\":\"base\"},"
                        + "\"lastMergeSourceCommit\":{\"commitId\":\"head\"}}";
            }
            return withSuccess(json, MediaType.APPLICATION_JSON).createResponse(request);
        });

        String diff = new AzureDevopsApiClient(builder.build(), creds())
                .getPullRequestDiff("contoso", "MyProject/my-service", 42L);

        assertTrue(diff.contains("diff --git a/src/Old.java b/src/New.java"), "was:\n" + diff);
        assertTrue(diff.contains("Diff omitted: file renamed"), "was:\n" + diff);
    }

    @Test
    void getPullRequestDiff_renameCombinedWithEditStillDiffsTheContent() {
        // changeType is a comma-joined flag set: a file moved *and* modified arrives as
        // "edit, rename". Treating it as a plain rename would drop the content change of
        // exactly the files a refactoring pull request touches. The old side must be read
        // at the pre-rename path (sourceServerItem), the new side at item.path.
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true).build();
        List<String> blobPaths = new ArrayList<>();
        server.expect(manyTimes(), anything()).andRespond(request -> {
            String uri = request.getURI().toString();
            String json;
            if (uri.contains("/iterations?")) {
                json = "{\"value\":[{\"id\":1,\"commonRefCommit\":{\"commitId\":\""
                        + MERGE_BASE + "\"}}]}";
            } else if (uri.contains("/changes")) {
                json = "{\"changeEntries\":[{\"item\":{\"path\":\"/new/Name.java\"},"
                        + "\"sourceServerItem\":\"/old/Name.java\","
                        + "\"changeType\":\"edit, rename\"}]}";
            } else if (uri.contains("items?")) {
                blobPaths.add(blobPath(request.getURI().getQuery()));
                json = uri.contains(MERGE_BASE)
                        ? "{\"content\":\"line1\\nline2\\nline3\\n\"}"
                        : "{\"content\":\"line1\\nCHANGED\\nline3\\n\"}";
            } else {
                json = "{\"pullRequestId\":42,"
                        + "\"lastMergeTargetCommit\":{\"commitId\":\"" + TARGET_TIP + "\"},"
                        + "\"lastMergeSourceCommit\":{\"commitId\":\"" + HEAD_SHA + "\"}}";
            }
            return withSuccess(json, MediaType.APPLICATION_JSON).createResponse(request);
        });

        String diff = new AzureDevopsApiClient(builder.build(), creds())
                .getPullRequestDiff("contoso", "MyProject/my-service", 42L);

        assertEquals(List.of("/old/Name.java", "/new/Name.java"), blobPaths,
                "old side must be read at the pre-rename path, new side at the new one");
        assertFalse(diff.contains("Diff omitted"),
                "a rename carrying an edit must not be stubbed out:\n" + diff);
        assertTrue(diff.contains("diff --git a/old/Name.java b/new/Name.java"), "was:\n" + diff);
        assertTrue(diff.contains("--- a/old/Name.java"), "was:\n" + diff);
        assertTrue(diff.contains("+++ b/new/Name.java"), "was:\n" + diff);
        assertTrue(diff.contains("-line2"), "was:\n" + diff);
        assertTrue(diff.contains("+CHANGED"), "was:\n" + diff);
    }

    @Test
    void getPullRequestDiff_returnsNullWhenNoCommitPinsTheOldSide() {
        // Neither the iteration's merge base nor lastMergeTargetCommit is present. Azure
        // DevOps falls back to the default branch when versionDescriptor.version is empty,
        // so fetching anyway would yield a plausible-looking but wrong diff.
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true).build();
        AtomicInteger itemsCalls = new AtomicInteger();
        server.expect(manyTimes(), anything()).andRespond(request -> {
            String uri = request.getURI().toString();
            String json;
            if (uri.contains("/iterations?")) {
                json = "{\"value\":[{\"id\":1}]}";
            } else if (uri.contains("/changes")) {
                json = "{\"changeEntries\":[{\"item\":{\"path\":\"/src/Foo.java\"},"
                        + "\"changeType\":\"edit\"}]}";
            } else if (uri.contains("items?")) {
                itemsCalls.incrementAndGet();
                json = "{\"content\":\"should never be fetched\"}";
            } else {
                json = "{\"pullRequestId\":42,"
                        + "\"lastMergeSourceCommit\":{\"commitId\":\"" + HEAD_SHA + "\"}}";
            }
            return withSuccess(json, MediaType.APPLICATION_JSON).createResponse(request);
        });

        assertNull(new AzureDevopsApiClient(builder.build(), creds())
                .getPullRequestDiff("contoso", "MyProject/my-service", 42L));
        assertEquals(0, itemsCalls.get(), "no blob may be fetched at an unpinned version");
    }

    /** Extracts the decoded {@code path} query parameter of an items request. */
    private static String blobPath(String decodedQuery) {
        return decodedQuery.replaceAll(".*(?:^|&)path=([^&]*).*", "$1");
    }

    @Test
    void getPullRequestDiff_binaryFileEmitsBinaryStub() {
        // Also the regression guard for includeContentMetadata=true: without that query
        // parameter, contentMetadata (and therefore isBinary) would never be present, and
        // the assertion on the request URI below would fail.
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true).build();
        server.expect(manyTimes(), anything()).andRespond(request -> {
            String uri = request.getURI().toString();
            String json;
            if (uri.contains("/iterations?")) {
                json = "{\"value\":[{\"id\":1}]}";
            } else if (uri.contains("/changes")) {
                json = "{\"changeEntries\":[{\"item\":{\"path\":\"/image.png\"},"
                        + "\"changeType\":\"edit\"}]}";
            } else if (uri.contains("items?")) {
                assertTrue(uri.contains("includeContentMetadata=true"),
                        "must request contentMetadata to detect binary files, was: " + uri);
                json = "{\"content\":\"ignored\",\"contentMetadata\":{\"isBinary\":true}}";
            } else {
                json = "{\"lastMergeTargetCommit\":{\"commitId\":\"base\"},"
                        + "\"lastMergeSourceCommit\":{\"commitId\":\"head\"}}";
            }
            return withSuccess(json, MediaType.APPLICATION_JSON).createResponse(request);
        });

        String diff = new AzureDevopsApiClient(builder.build(), creds())
                .getPullRequestDiff("contoso", "MyProject/my-service", 42L);

        assertEquals("diff --git a/image.png b/image.png\n"
                + "Binary files a/image.png and b/image.png differ\n", diff);
    }

    @Test
    void getPullRequestDiff_truncatesAtMaxFilesWithAccurateOmittedCount() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true).build();
        int total = AzureDevopsDiffBuilder.MAX_FILES + 5;
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < total; i++) {
            if (i > 0) {
                entries.append(',');
            }
            entries.append("{\"item\":{\"path\":\"/file").append(i).append(".txt\"},")
                    .append("\"changeType\":\"add\"}");
        }
        String changesJson = "{\"changeEntries\":[" + entries + "]}";

        server.expect(manyTimes(), anything()).andRespond(request -> {
            String uri = request.getURI().toString();
            String json;
            if (uri.contains("/iterations?")) {
                json = "{\"value\":[{\"id\":1}]}";
            } else if (uri.contains("/changes")) {
                json = changesJson;
            } else if (uri.contains("items?")) {
                json = "{\"content\":\"content\\n\"}";
            } else {
                json = "{\"lastMergeTargetCommit\":{\"commitId\":\"base\"},"
                        + "\"lastMergeSourceCommit\":{\"commitId\":\"head\"}}";
            }
            return withSuccess(json, MediaType.APPLICATION_JSON).createResponse(request);
        });

        String diff = new AzureDevopsApiClient(builder.build(), creds())
                .getPullRequestDiff("contoso", "MyProject/my-service", 42L);

        assertTrue(diff.contains("Diff truncated: 5 further files omitted"), "was:\n" + diff);
        int fileBlocks = diff.split("diff --git a/", -1).length - 1;
        assertEquals(AzureDevopsDiffBuilder.MAX_FILES, fileBlocks,
                "exactly MAX_FILES blocks should be rendered, was:\n" + diff);
    }

    @Test
    void getPullRequestDiff_truncatesAtMaxFilesWithFoldersPresent_accurateOmittedCount() {
        // Regression guard: folder entries must not be counted as "rendered" or
        // "omitted" files. The old buggy formula (changes.size() - rendered) gave the
        // right answer only when no folders were present, masking this bug in the
        // folder-free truncation test above.
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true).build();
        int totalFiles = AzureDevopsDiffBuilder.MAX_FILES + 5;
        StringBuilder entries = new StringBuilder();
        entries.append("{\"item\":{\"path\":\"/src\",\"isFolder\":true},\"changeType\":\"add\"}");
        for (int i = 0; i < totalFiles; i++) {
            entries.append(",{\"item\":{\"path\":\"/file").append(i).append(".txt\"},")
                    .append("\"changeType\":\"add\"}");
            if (i % 20 == 0) {
                entries.append(",{\"item\":{\"path\":\"/dir").append(i)
                        .append("\",\"isFolder\":true},\"changeType\":\"add\"}");
            }
        }
        String changesJson = "{\"changeEntries\":[" + entries + "]}";

        server.expect(manyTimes(), anything()).andRespond(request -> {
            String uri = request.getURI().toString();
            String json;
            if (uri.contains("/iterations?")) {
                json = "{\"value\":[{\"id\":1}]}";
            } else if (uri.contains("/changes")) {
                json = changesJson;
            } else if (uri.contains("items?")) {
                json = "{\"content\":\"content\\n\"}";
            } else {
                json = "{\"lastMergeTargetCommit\":{\"commitId\":\"base\"},"
                        + "\"lastMergeSourceCommit\":{\"commitId\":\"head\"}}";
            }
            return withSuccess(json, MediaType.APPLICATION_JSON).createResponse(request);
        });

        String diff = new AzureDevopsApiClient(builder.build(), creds())
                .getPullRequestDiff("contoso", "MyProject/my-service", 42L);

        assertTrue(diff.contains("Diff truncated: 5 further files omitted"), "was:\n" + diff);
        int fileBlocks = diff.split("diff --git a/", -1).length - 1;
        assertEquals(AzureDevopsDiffBuilder.MAX_FILES, fileBlocks,
                "exactly MAX_FILES blocks should be rendered even with folders present, was:\n" + diff);
    }

    @Test
    void getPullRequestDiff_oversizedBlobEmitsSkippedStub() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true).build();
        String hugeContent = "a".repeat(AzureDevopsDiffBuilder.MAX_BLOB_BYTES + 1);
        server.expect(manyTimes(), anything()).andRespond(request -> {
            String uri = request.getURI().toString();
            String json;
            if (uri.contains("/iterations?")) {
                json = "{\"value\":[{\"id\":1}]}";
            } else if (uri.contains("/changes")) {
                json = "{\"changeEntries\":[{\"item\":{\"path\":\"/huge.txt\"},"
                        + "\"changeType\":\"edit\"}]}";
            } else if (uri.contains("items?")) {
                json = "{\"content\":\"" + hugeContent + "\"}";
            } else {
                json = "{\"lastMergeTargetCommit\":{\"commitId\":\"base\"},"
                        + "\"lastMergeSourceCommit\":{\"commitId\":\"head\"}}";
            }
            return withSuccess(json, MediaType.APPLICATION_JSON).createResponse(request);
        });

        String diff = new AzureDevopsApiClient(builder.build(), creds())
                .getPullRequestDiff("contoso", "MyProject/my-service", 42L);

        assertTrue(diff.contains("diff --git a/huge.txt b/huge.txt"), "was:\n" + diff);
        assertTrue(diff.contains("Diff omitted: file exceeds 512 KiB"), "was:\n" + diff);
        assertFalse(diff.contains("@@"), "oversized blob must not be diffed:\n" + diff);
    }

    @Test
    void getPullRequestDiff_blobCapCountsUtf8BytesNotChars() {
        // Three quarters of the char budget, but one and a half times the byte budget:
        // measuring chars would feed a 768 KiB file through the differ.
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true).build();
        String multiByte = "漢".repeat(AzureDevopsDiffBuilder.MAX_BLOB_BYTES / 2);
        server.expect(manyTimes(), anything()).andRespond(request -> {
            String uri = request.getURI().toString();
            String json;
            if (uri.contains("/iterations?")) {
                json = "{\"value\":[{\"id\":1}]}";
            } else if (uri.contains("/changes")) {
                json = "{\"changeEntries\":[{\"item\":{\"path\":\"/kanji.txt\"},"
                        + "\"changeType\":\"edit\"}]}";
            } else if (uri.contains("items?")) {
                json = "{\"content\":\"" + multiByte + "\"}";
            } else {
                json = "{\"lastMergeTargetCommit\":{\"commitId\":\"base\"},"
                        + "\"lastMergeSourceCommit\":{\"commitId\":\"head\"}}";
            }
            return withSuccess(json, MediaType.APPLICATION_JSON).createResponse(request);
        });

        String diff = new AzureDevopsApiClient(builder.build(), creds())
                .getPullRequestDiff("contoso", "MyProject/my-service", 42L);

        assertTrue(multiByte.length() < AzureDevopsDiffBuilder.MAX_BLOB_BYTES,
                "the fixture must be under the budget when counted as chars");
        assertTrue(diff.contains("Diff omitted: file exceeds 512 KiB"), "was:\n" + diff);
    }

    @Test
    void getPullRequestDiff_followsNextSkipAcrossChangePages() {
        // The omitted-file tally is computed from the entry list, so a tally taken from
        // a truncated first page would under-report.
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true).build();
        AtomicInteger changePages = new AtomicInteger();
        server.expect(manyTimes(), anything()).andRespond(request -> {
            String uri = request.getURI().toString();
            String json;
            if (uri.contains("/iterations?")) {
                json = "{\"value\":[{\"id\":1}]}";
            } else if (uri.contains("/changes")) {
                changePages.incrementAndGet();
                json = uri.contains("$skip=2000")
                        ? "{\"changeEntries\":[{\"item\":{\"path\":\"/second-page.txt\"},"
                                + "\"changeType\":\"add\"}]}"
                        : "{\"changeEntries\":[{\"item\":{\"path\":\"/first-page.txt\"},"
                                + "\"changeType\":\"add\"}],\"nextSkip\":2000}";
            } else if (uri.contains("items?")) {
                json = "{\"content\":\"new\\n\"}";
            } else {
                json = "{\"lastMergeTargetCommit\":{\"commitId\":\"base\"},"
                        + "\"lastMergeSourceCommit\":{\"commitId\":\"head\"}}";
            }
            return withSuccess(json, MediaType.APPLICATION_JSON).createResponse(request);
        });

        String diff = new AzureDevopsApiClient(builder.build(), creds())
                .getPullRequestDiff("contoso", "MyProject/my-service", 42L);

        assertEquals(2, changePages.get(), "both pages must be requested");
        assertTrue(diff.contains("diff --git a/first-page.txt b/first-page.txt"), "was:\n" + diff);
        assertTrue(diff.contains("diff --git a/second-page.txt b/second-page.txt"), "was:\n" + diff);
    }

    @Test
    void getPullRequestDiff_blobFetchFailureDegradesToStubWithoutAbortingWholeDiff() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true).build();
        server.expect(manyTimes(), anything()).andRespond(request -> {
            String uri = request.getURI().toString();
            if (uri.contains("/iterations?")) {
                return withSuccess("{\"value\":[{\"id\":1}]}", MediaType.APPLICATION_JSON)
                        .createResponse(request);
            }
            if (uri.contains("/changes")) {
                return withSuccess("{\"changeEntries\":["
                        + "{\"item\":{\"path\":\"/fail.txt\"},\"changeType\":\"edit\"},"
                        + "{\"item\":{\"path\":\"/ok.txt\"},\"changeType\":\"edit\"}]}",
                        MediaType.APPLICATION_JSON).createResponse(request);
            }
            if (uri.contains("items?") && uri.contains("fail.txt")) {
                return withServerError().createResponse(request);
            }
            if (uri.contains("items?") && uri.contains("ok.txt") && uri.contains("base")) {
                return withSuccess("{\"content\":\"line1\\nline2\\n\"}",
                        MediaType.APPLICATION_JSON).createResponse(request);
            }
            if (uri.contains("items?")) {
                return withSuccess("{\"content\":\"line1\\nCHANGED\\n\"}",
                        MediaType.APPLICATION_JSON).createResponse(request);
            }
            return withSuccess("{\"lastMergeTargetCommit\":{\"commitId\":\"base\"},"
                    + "\"lastMergeSourceCommit\":{\"commitId\":\"head\"}}",
                    MediaType.APPLICATION_JSON).createResponse(request);
        });

        String diff = new AzureDevopsApiClient(builder.build(), creds())
                .getPullRequestDiff("contoso", "MyProject/my-service", 42L);

        assertNotNull(diff, "one bad file must not abort the whole PR diff");
        assertTrue(diff.contains("diff --git a/fail.txt b/fail.txt"), "was:\n" + diff);
        assertTrue(diff.contains("Diff omitted: content unavailable"), "was:\n" + diff);
        assertTrue(diff.contains("diff --git a/ok.txt b/ok.txt"), "was:\n" + diff);
        assertTrue(diff.contains("-line2"), "was:\n" + diff);
        assertTrue(diff.contains("+CHANGED"), "was:\n" + diff);
    }

    @Test
    void getPullRequestDiff_readsOldSideAtTheIterationMergeBaseNotTheTargetTip() {
        // The change entries are the PR's own changes, computed against the iteration's
        // commonRefCommit. lastMergeTargetCommit is the target branch tip, which moves
        // independently of the PR — diffing against it would fold unrelated
        // target-branch commits into the patch, inverted, as if the author had made them.
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true).build();
        List<String> blobVersions = new ArrayList<>();
        server.expect(manyTimes(), anything()).andRespond(request -> {
            String uri = request.getURI().toString();
            String json;
            if (uri.contains("/iterations?")) {
                // The merge base of the *latest* iteration is the one that matters.
                json = "{\"value\":["
                        + "{\"id\":1,\"commonRefCommit\":{\"commitId\":\"" + HEAD_SHA + "\"}},"
                        + "{\"id\":2,\"commonRefCommit\":{\"commitId\":\"" + MERGE_BASE + "\"},"
                        + "\"targetRefCommit\":{\"commitId\":\"" + TARGET_TIP + "\"}}]}";
            } else if (uri.contains("/iterations/2/changes")) {
                json = "{\"changeEntries\":[{\"item\":{\"path\":\"/src/Foo.java\"},"
                        + "\"changeType\":\"edit\"}]}";
            } else if (uri.contains("items?")) {
                blobVersions.add(blobVersion(uri));
                json = uri.contains(MERGE_BASE)
                        ? "{\"content\":\"line1\\nline2\\n\"}"
                        : "{\"content\":\"line1\\nCHANGED\\n\"}";
            } else {
                json = "{\"pullRequestId\":42,"
                        + "\"lastMergeTargetCommit\":{\"commitId\":\"" + TARGET_TIP + "\"},"
                        + "\"lastMergeSourceCommit\":{\"commitId\":\"" + HEAD_SHA + "\"}}";
            }
            return withSuccess(json, MediaType.APPLICATION_JSON).createResponse(request);
        });

        String diff = new AzureDevopsApiClient(builder.build(), creds())
                .getPullRequestDiff("contoso", "MyProject/my-service", 42L);

        assertTrue(blobVersions.contains(MERGE_BASE),
                "old side must be read at the latest iteration's merge base, was: " + blobVersions);
        assertFalse(blobVersions.contains(TARGET_TIP),
                "the target branch tip is not the merge base, was: " + blobVersions);
        assertTrue(diff.contains("-line2"), "was:\n" + diff);
        assertTrue(diff.contains("+CHANGED"), "was:\n" + diff);
    }

    /** Extracts {@code versionDescriptor.version} from an items request URI. */
    private static String blobVersion(String uri) {
        return uri.replaceAll(".*[?&]versionDescriptor\\.version=([^&]*).*", "$1");
    }

    @Test
    void getPullRequestDiff_fallsBackToTargetCommitWhenIterationHasNoMergeBase() {
        // A malformed or trimmed iterations response must still produce a diff rather
        // than fetching every old blob at a null version.
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dev.azure.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true).build();
        List<String> blobVersions = new ArrayList<>();
        server.expect(manyTimes(), anything()).andRespond(request -> {
            String uri = request.getURI().toString();
            String json;
            if (uri.contains("/iterations?")) {
                json = "{\"value\":[{\"id\":1}]}";
            } else if (uri.contains("/changes")) {
                json = "{\"changeEntries\":[{\"item\":{\"path\":\"/src/Foo.java\"},"
                        + "\"changeType\":\"edit\"}]}";
            } else if (uri.contains("items?")) {
                blobVersions.add(blobVersion(uri));
                json = uri.contains("version=base")
                        ? "{\"content\":\"line1\\nline2\\n\"}"
                        : "{\"content\":\"line1\\nCHANGED\\n\"}";
            } else {
                json = "{\"lastMergeTargetCommit\":{\"commitId\":\"base\"},"
                        + "\"lastMergeSourceCommit\":{\"commitId\":\"head\"}}";
            }
            return withSuccess(json, MediaType.APPLICATION_JSON).createResponse(request);
        });

        String diff = new AzureDevopsApiClient(builder.build(), creds())
                .getPullRequestDiff("contoso", "MyProject/my-service", 42L);

        assertTrue(blobVersions.contains("base"), "was: " + blobVersions);
        assertTrue(diff.contains("-line2"), "was:\n" + diff);
        assertTrue(diff.contains("+CHANGED"), "was:\n" + diff);
    }
}
