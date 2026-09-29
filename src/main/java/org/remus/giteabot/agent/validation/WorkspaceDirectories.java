package org.remus.giteabot.agent.validation;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates and deletes the private temporary roots that hold one workspace each:
 * the {@value #REPOSITORY_DIRECTORY_NAME} checkout plus the authentication files
 * of in-flight remote commands.
 * <p>
 * A root whose deletion failed may still hold a credential file, so it is
 * remembered and deleted again before the next root is created.
 */
@Slf4j
class WorkspaceDirectories {

    static final String REPOSITORY_DIRECTORY_NAME = "repository";
    private static final String ROOT_MARKER = ".agent-workspace";
    private static final Set<PosixFilePermission> OWNER_DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> OWNER_FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private final Path baseDir;
    private final Set<Path> pendingDeletions = ConcurrentHashMap.newKeySet();

    /** @param baseDir parent for new roots, or {@code null} for the system temporary directory */
    WorkspaceDirectories(Path baseDir) {
        this.baseDir = baseDir == null ? null : baseDir.toAbsolutePath().normalize();
    }

    /** Creates a new owner-only root carrying the workspace marker. */
    Path createRoot() throws IOException {
        for (Path pending : List.copyOf(pendingDeletions)) {
            delete(pending);
        }
        Path root;
        if (baseDir == null) {
            root = Files.createTempDirectory("agent-workspace-");
        } else {
            Files.createDirectories(baseDir);
            root = Files.createTempDirectory(baseDir, "agent-workspace-");
        }
        try {
            restrictToOwner(root, true);
            Files.createFile(root.resolve(ROOT_MARKER));
            return root;
        } catch (IOException | RuntimeException e) {
            delete(root);
            throw e;
        }
    }

    /**
     * Deletes a root with everything in it.
     *
     * @return {@code false} if something could not be deleted; the root is then retried
     *         before the next {@link #createRoot()}
     */
    boolean delete(Path root) {
        try {
            deleteRecursively(root);
            pendingDeletions.remove(root);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to clean up workspace {}: {}", root, e.getMessage());
            pendingDeletions.add(root);
            return false;
        }
    }

    /** Whether {@code root} was created by {@link #createRoot()}. */
    static boolean isManagedRoot(Path root) {
        return root != null && Files.isRegularFile(root.resolve(ROOT_MARKER), LinkOption.NOFOLLOW_LINKS);
    }

    /** Applies owner-only permissions where the host filesystem supports them. */
    static void restrictToOwner(Path path, boolean directory) throws IOException {
        try {
            Files.setPosixFilePermissions(path,
                    directory ? OWNER_DIRECTORY_PERMISSIONS : OWNER_FILE_PERMISSIONS);
            return;
        } catch (UnsupportedOperationException ignored) {
            // Use the native ACL view when POSIX permissions are unavailable.
        }
        AclFileAttributeView aclView = Files.getFileAttributeView(
                path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (aclView == null) {
            throw new IOException("Owner-only permissions are unsupported for " + path);
        }
        AclEntry ownerAccess = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS))
                .setPermissions(AclEntryPermission.values())
                .build();
        aclView.setAcl(List.of(ownerAccess));
    }

    /** Test seam: overridden to simulate a deletion failure. */
    void deleteRecursively(Path dir) throws IOException {
        if (Files.notExists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> paths;
        try (var stream = Files.walk(dir)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        IOException failure = null;
        for (Path path : paths) {
            try {
                Files.delete(path);
            } catch (IOException e) {
                log.warn("Failed to delete {}: {}", path, e.getMessage());
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
