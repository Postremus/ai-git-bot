package org.remus.giteabot.secret;

import java.util.Optional;

public interface SecretSource {
    /**
     * Resolves a key to a SecretValue.
     *
     * @param key The key of the secret, which should never be null.
     * @return An Optional holding a SecretValue, if the key was successfully resolved to value. Otherwise, an empty Optional.
     *
     * @throws KeyResolveException Is thrown in case: (1) the key itself is invalid for the SecretSource, (2) the unlikely case that the given key is null, (3) or if the application is not allowed to access the secret behind the key.
     */
    Optional<SecretValue> resolve(String key);

    /**
     * Checks whether a key currently resolves to a value.
     * <p>
     * The default implementation calls {@link #resolve(String)}, so it loads the plain secret into
     * memory just to check for its presence. Override it whenever the source can answer without
     * reading the value - e.g., a file source checking that the file exists and is readable, or a
     * vault source querying the secret's metadata - so a validation does not pull secrets into
     * memory eagerly. Keep the default only if the value is in memory anyway, as it is for
     * {@link EnvSecretSource}.
     *
     * @param key The key of the secret, which should never be null.
     * @return true, if {@link #resolve(String)} would return a value for this key.
     *
     * @throws KeyResolveException under the same conditions as {@link #resolve(String)}.
     */
    default boolean resolvable(String key) {
        return resolve(key).isPresent();
    }

    /**
     * The lowercase type name of this secret source, like env, file, vault etc.
     *
     * @return the lowercase type name, should never return null.
     */
    String type();
}
