package org.remus.giteabot.agent.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceDirectoriesTest {

    @TempDir
    Path tempDir;

    @Test
    void createRoot_usesConfiguredBaseDirectoryAndMarksRoot() throws IOException {
        Path base = tempDir.resolve("sandbox-workspaces");
        WorkspaceDirectories directories = new WorkspaceDirectories(base);

        Path root = directories.createRoot();

        assertThat(root.getParent()).isEqualTo(base.toAbsolutePath().normalize());
        assertThat(root.getFileName().toString()).startsWith("agent-workspace-");
        assertThat(WorkspaceDirectories.isManagedRoot(root)).isTrue();
        assertThat(directories.delete(root)).isTrue();
        assertThat(root).doesNotExist();
        assertThat(base).exists();
    }

    @Test
    void isManagedRoot_rejectsDirectoryWithoutMarker() throws IOException {
        assertThat(WorkspaceDirectories.isManagedRoot(Files.createDirectories(tempDir.resolve("plain")))).isFalse();
        assertThat(WorkspaceDirectories.isManagedRoot(null)).isFalse();
    }

    @Test
    void failedDeletionIsRetriedBeforeNextRoot() throws IOException {
        FailOnceDirectories directories = new FailOnceDirectories(tempDir.resolve("workspaces"));
        Path failed = directories.createRoot();

        assertThat(directories.delete(failed)).isFalse();
        assertThat(failed).exists();

        Path next = directories.createRoot();
        assertThat(failed).doesNotExist();
        directories.delete(next);
    }

    private static final class FailOnceDirectories extends WorkspaceDirectories {
        private boolean fail = true;

        FailOnceDirectories(Path baseDir) {
            super(baseDir);
        }

        @Override
        void deleteRecursively(Path dir) throws IOException {
            if (fail) {
                fail = false;
                throw new IOException("simulated cleanup failure");
            }
            super.deleteRecursively(dir);
        }
    }
}
