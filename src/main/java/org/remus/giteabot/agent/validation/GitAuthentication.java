package org.remus.giteabot.agent.validation;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.SshEndpoint;
import org.remus.giteabot.repository.model.RepositoryCredentials;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The authentication material of one remote git command.
 * <p>
 * HTTP credentials go into a credential-store file, SSH credentials into a private key and
 * a {@code known_hosts} file, all in the private workspace root and never inside the
 * checkout. Providers that opt in send the token as an {@code Authorization: Basic} header
 * instead, passed via {@code GIT_CONFIG_*} so it never appears in process arguments or
 * {@code .git/config}. {@link #clear()} removes every file again.
 */
@Slf4j
class GitAuthentication {

    private final RepositoryCheckout checkout;
    private final Path workspaceRoot;
    private Path credentialsFile;
    private Path sshPrivateKeyFile;
    private Path sshKnownHostsFile;

    GitAuthentication(RepositoryCheckout checkout, Path workspaceRoot) {
        this.checkout = checkout;
        this.workspaceRoot = workspaceRoot;
    }

    /** Writes the files this checkout needs. */
    void write() throws IOException {
        RepositoryCredentials credentials = checkout.credentials();
        if (!credentials.usesSsh()) {
            if (!environment().isEmpty()) {
                // The token travels as an HTTP header, see environment().
                // Otherwise fall through, so opting in can never drop authentication.
                return;
            }
            writeCredentialsFile(credentials.username(), credentials.token());
            return;
        }
        if (credentials.sshPrivateKey() == null || credentials.sshPrivateKey().isBlank()
                || credentials.sshKnownHosts() == null || credentials.sshKnownHosts().isBlank()) {
            throw new IOException("SSH private key and known_hosts are required");
        }
        sshPrivateKeyFile = Files.createTempFile(workspaceRoot, "ssh-key-", ".tmp");
        writeSecretFile(sshPrivateKeyFile, normalizeSecret(credentials.sshPrivateKey()));
        sshKnownHostsFile = Files.createTempFile(workspaceRoot, "known-hosts-", ".tmp");
        writeSecretFile(sshKnownHostsFile, normalizeSecret(credentials.sshKnownHosts()));
    }

    /** {@code gitArgs} preceded by the {@code -c} options that point git at the written files. */
    String[] withConfig(String... gitArgs) {
        String[] config = configArgs();
        String[] command = new String[config.length + gitArgs.length];
        System.arraycopy(config, 0, command, 0, config.length);
        System.arraycopy(gitArgs, 0, command, config.length, gitArgs.length);
        return command;
    }

    String[] configArgs() {
        List<String> args = new ArrayList<>();
        if (credentialsFile != null) {
            args.add("-c");
            args.add("credential.helper=");
            args.add("-c");
            args.add("credential.helper=store --file=" + credentialsFile.toAbsolutePath());
        }
        if (sshPrivateKeyFile != null && sshKnownHostsFile != null) {
            String sshCommand = "ssh -F /dev/null -i " + shellQuote(sshPrivateKeyFile)
                    + " -o UserKnownHostsFile=" + shellQuote(sshKnownHostsFile)
                    + " -o GlobalKnownHostsFile=/dev/null -o IdentitiesOnly=yes"
                    + " -o IdentityAgent=none -o BatchMode=yes -o StrictHostKeyChecking=yes";
            args.add("-c");
            args.add("core.sshCommand=" + sshCommand);
        }
        return args.toArray(String[]::new);
    }

    /** Environment that makes git send the token as a pre-emptive Basic header, scoped to the remote. */
    Map<String, String> environment() {
        RepositoryCredentials credentials = checkout.credentials();
        String remote = checkout.remote();
        if (!checkout.usesAuthorizationHeader() || credentials.usesSsh()
                || credentials.token() == null || credentials.token().isBlank()
                || remote == null || !remote.toLowerCase(Locale.ROOT).matches("https?://.*")) {
            return Map.of();
        }
        String username = credentials.hasUsername() ? credentials.username() : "";
        String basic = Base64.getEncoder().encodeToString(
                (username + ":" + credentials.token()).getBytes(StandardCharsets.UTF_8));
        return Map.of(
                "GIT_CONFIG_COUNT", "1",
                "GIT_CONFIG_KEY_0", "http." + remote + ".extraheader",
                "GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic);
    }

    /** Deletes every written file. @return {@code true} if none is left on disk */
    boolean clear() {
        if (deleteSecretFile(credentialsFile)) {
            credentialsFile = null;
        }
        if (deleteSecretFile(sshPrivateKeyFile)) {
            sshPrivateKeyFile = null;
        }
        if (deleteSecretFile(sshKnownHostsFile)) {
            sshKnownHostsFile = null;
        }
        return credentialsFile == null && sshPrivateKeyFile == null && sshKnownHostsFile == null;
    }

    Path credentialsFile() {
        return credentialsFile;
    }

    Path sshPrivateKeyFile() {
        return sshPrivateKeyFile;
    }

    Path sshKnownHostsFile() {
        return sshKnownHostsFile;
    }

    private void writeCredentialsFile(String username, String token) throws IOException {
        String remote = checkout.remote();
        if (token == null || token.isBlank()
                || remote.toLowerCase(Locale.ROOT).startsWith("file://")
                || isLocalPath(remote)
                || SshEndpoint.isSshRemote(remote)) {
            return;
        }
        if (!WorkspaceDirectories.isManagedRoot(workspaceRoot)) {
            throw new IOException("Workspace does not have a private credential directory");
        }
        String protocol = remote.toLowerCase(Locale.ROOT).startsWith("https://") ? "https://" : "http://";
        String baseUrl = remote.substring(protocol.length());
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String host = baseUrl.contains("/") ? baseUrl.substring(0, baseUrl.indexOf('/')) : baseUrl;
        String credentialUsername = username == null || username.isBlank() ? "oauth2" : username;
        credentialsFile = Files.createTempFile(workspaceRoot, "credentials-", ".store");
        writeSecretFile(credentialsFile, protocol + credentialUsername + ":" + token + "@" + host + "\n");
    }

    private static boolean isLocalPath(String remote) {
        return remote.startsWith("/") || remote.startsWith("\\\\")
                || remote.length() >= 3 && Character.isLetter(remote.charAt(0))
                && remote.charAt(1) == ':'
                && (remote.charAt(2) == '\\' || remote.charAt(2) == '/');
    }

    private static String normalizeSecret(String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        return normalized.endsWith("\n") ? normalized : normalized + "\n";
    }

    private static void writeSecretFile(Path file, String content) throws IOException {
        WorkspaceDirectories.restrictToOwner(file, false);
        Files.writeString(file, content);
    }

    private static String shellQuote(Path value) {
        return "'" + value.toAbsolutePath().normalize().toString().replace("'", "'\"'\"'") + "'";
    }

    private static boolean deleteSecretFile(Path file) {
        if (file == null) {
            return true;
        }
        try {
            Files.deleteIfExists(file);
            return true;
        } catch (IOException e) {
            log.warn("Failed to delete Git authentication file {}: {}", file, e.getMessage());
            return false;
        }
    }
}
