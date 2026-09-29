package org.remus.giteabot.agent.validation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.remus.giteabot.repository.model.RepositoryCredentials;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitAuthenticationTest {

    @TempDir
    Path tempDir;

    private WorkspaceDirectories directories;
    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        directories = new WorkspaceDirectories(tempDir.resolve("workspaces"));
        root = directories.createRoot();
    }

    @AfterEach
    void tearDown() {
        directories.delete(root);
    }

    @Test
    void ssh_usesIntegrationKeyAndPinnedHostKeys() throws IOException {
        RepositoryCredentials credentials = RepositoryCredentials
                .of("https://gitea.example.com", "git@gitea.example.com:owner/repo.git", "token")
                .withSsh("private key", "gitea.example.com ssh-ed25519 host-key");
        GitAuthentication authentication = new GitAuthentication(
                new RepositoryCheckout(credentials.cloneUrl(), credentials, false), root);

        authentication.write();

        Path privateKey = authentication.sshPrivateKeyFile();
        Path knownHosts = authentication.sshKnownHostsFile();
        assertThat(Files.readString(privateKey)).isEqualTo("private key\n");
        assertThat(Files.readString(knownHosts)).isEqualTo("gitea.example.com ssh-ed25519 host-key\n");
        assertThat(authentication.withConfig("clone", credentials.cloneUrl()))
                .containsExactly(
                        "-c",
                        "core.sshCommand=ssh -F /dev/null -i '" + privateKey.toAbsolutePath() + "'"
                                + " -o UserKnownHostsFile='" + knownHosts.toAbsolutePath() + "'"
                                + " -o GlobalKnownHostsFile=/dev/null -o IdentitiesOnly=yes"
                                + " -o IdentityAgent=none -o BatchMode=yes -o StrictHostKeyChecking=yes",
                        "clone", credentials.cloneUrl());

        assertThat(authentication.clear()).isTrue();
        assertThat(authentication.sshPrivateKeyFile()).isNull();
        assertThat(authentication.sshKnownHostsFile()).isNull();
        assertThat(privateKey).doesNotExist();
        assertThat(knownHosts).doesNotExist();
    }

    @Test
    void credentialsFile_keepsTokenOutsideRepositoryAndClearRemovesIt() throws IOException {
        GitAuthentication authentication = new GitAuthentication(new RepositoryCheckout(
                "https://git.example.com/owner/repo.git",
                RepositoryCredentials.of("https://git.example.com", "https://git.example.com", "test-token"),
                false), root);

        authentication.write();

        Path credentials = authentication.credentialsFile();
        assertThat(credentials.getParent()).isEqualTo(root);
        assertThat(credentials.getFileName().toString()).startsWith("credentials-");
        assertThat(Files.readString(credentials)).isEqualTo("https://oauth2:test-token@git.example.com\n");
        assertThat(authentication.withConfig("fetch")).containsExactly(
                "-c", "credential.helper=",
                "-c", "credential.helper=store --file=" + credentials.toAbsolutePath(),
                "fetch");

        assertThat(authentication.clear()).isTrue();
        assertThat(credentials).doesNotExist();
    }

    @Test
    void credentialsFile_preservesConfiguredUsernameWithUppercaseScheme() throws IOException {
        GitAuthentication authentication = new GitAuthentication(new RepositoryCheckout(
                "HTTPS://bitbucket.org/owner/repo.git",
                RepositoryCredentials.of("https://api.bitbucket.org", "HTTPS://bitbucket.org", "alice", "app-password"),
                false), root);

        authentication.write();

        assertThat(Files.readString(authentication.credentialsFile()))
                .isEqualTo("https://alice:app-password@bitbucket.org\n");
        authentication.clear();
    }

    @Test
    void credentialsFile_rejectsUnmanagedRoot() throws IOException {
        Path unmanaged = Files.createDirectories(tempDir.resolve("unmanaged"));
        GitAuthentication authentication = new GitAuthentication(new RepositoryCheckout(
                "https://git.example.com/owner/repo.git",
                RepositoryCredentials.of("https://git.example.com", "https://git.example.com", "test-token"),
                false), unmanaged);

        assertThatThrownBy(authentication::write)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("private credential directory");
    }

    @Test
    void authorizationHeader_sendsTokenAsBasicHeaderWithoutCredentialStore() throws IOException {
        String remote = "https://dev.azure.com/org/project/_git/repo";
        GitAuthentication authentication = new GitAuthentication(new RepositoryCheckout(remote,
                RepositoryCredentials.of("https://dev.azure.com", "https://dev.azure.com", "pat"), true), root);

        authentication.write();

        assertThat(authentication.credentialsFile()).isNull();
        assertThat(authentication.configArgs()).isEmpty();
        assertThat(authentication.environment()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "GIT_CONFIG_COUNT", "1",
                "GIT_CONFIG_KEY_0", "http." + remote + ".extraheader",
                "GIT_CONFIG_VALUE_0", "Authorization: Basic "
                        + Base64.getEncoder().encodeToString(":pat".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void authorizationHeader_notUsedByDefault() {
        GitAuthentication authentication = new GitAuthentication(new RepositoryCheckout(
                "https://git.example.com/owner/repo.git",
                RepositoryCredentials.of("https://git.example.com", "https://git.example.com", "token"),
                false), root);

        assertThat(authentication.environment()).isEmpty();
    }

    @Test
    void localRemote_writesNoFiles() throws IOException {
        GitAuthentication authentication = new GitAuthentication(new RepositoryCheckout(
                "/srv/git/repo.git", RepositoryCredentials.of("", "/srv/git/repo.git", "token"), false), root);

        authentication.write();

        assertThat(authentication.credentialsFile()).isNull();
        assertThat(authentication.configArgs()).isEmpty();
    }
}
