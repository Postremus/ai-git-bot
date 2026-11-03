package org.remus.giteabot.repository;

import java.net.URI;
import java.util.Locale;

/**
 * Resolves the complete, credential-free Git remote of one repository of an integration.
 * Built once per integration by its {@link RepositoryProviderMetadata} and held by the
 * {@link RepositoryConnection}; resolved per repository because a checkout may target any
 * repository the integration reaches, e.g. the fork a pull request comes from.
 * <p>
 * The remote is handed to {@code git} as a command-line argument and is echoed back in
 * git's own error output, so it must never carry a secret: credentials travel separately
 * through the connection's {@linkplain RepositoryConnection#getCredentials() credentials}.
 * A resolver building an HTTP remote must therefore start from
 * {@link #validatedCloneBaseUrl}; {@link #http} does so for the common layout.
 */
@FunctionalInterface
public interface RepositoryRemoteResolver {

    /**
     * Returns the Git remote of {@code owner/repo}.
     *
     * @throws IllegalStateException when the remote cannot be resolved
     */
    String resolve(String owner, String repo);

    /**
     * Returns a resolver for the {@code {base}/{owner}/{repo}.git} HTTP remote most
     * providers use. The base URL is validated once, here; an invalid one is reported
     * on every {@link #resolve} instead, so that it fails Git checkouts only and not the
     * API operations that share the connection.
     */
    static RepositoryRemoteResolver http(String cloneBaseUrl) {
        String base;
        try {
            base = validatedCloneBaseUrl(cloneBaseUrl);
        } catch (IllegalStateException e) {
            return failing(e);
        }
        return (owner, repo) -> {
            if (owner == null || owner.isBlank() || repo == null || repo.isBlank()) {
                throw new IllegalStateException("Repository remote cannot be resolved");
            }
            return base + "/" + owner + "/" + repo + ".git";
        };
    }

    /** Returns a resolver that reports {@code cause} on every {@link #resolve}. */
    static RepositoryRemoteResolver failing(IllegalStateException cause) {
        return (owner, repo) -> {
            throw new IllegalStateException(cause.getMessage(), cause);
        };
    }

    /**
     * Validates an HTTP clone base URL and returns it normalized: lower-case scheme, no
     * trailing slash.
     * <p>
     * A base URL with userinfo would both leak the credential into git's output and
     * corrupt the credential-store host {@code WorkspaceService} derives from the remote.
     * Query and fragment are rejected for the same reason: neither is meaningful in a Git
     * remote, and both would survive into the concatenated URL.
     * <p>
     * Public so that providers building a provider-specific path — Azure DevOps addresses
     * repositories as {@code /{org}/{project}/_git/{name}} — keep the same guarantee
     * instead of reimplementing (and quietly dropping) it.
     *
     * @throws IllegalStateException when the URL is missing, unparseable, not HTTP(S), or
     *                               carries userinfo, a query or a fragment
     */
    static String validatedCloneBaseUrl(String cloneBaseUrl) {
        if (cloneBaseUrl == null || cloneBaseUrl.isBlank()) {
            throw new IllegalStateException("Repository remote cannot be resolved");
        }
        String normalized;
        try {
            URI base = URI.create(cloneBaseUrl);
            if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                    || base.getHost() == null || base.getUserInfo() != null
                    || base.getRawQuery() != null || base.getRawFragment() != null) {
                throw new IllegalStateException("HTTP clone base URL must be credential-free");
            }
            normalized = base.getScheme().toLowerCase(Locale.ROOT)
                    + cloneBaseUrl.substring(base.getScheme().length());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("HTTP clone base URL is invalid", e);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
