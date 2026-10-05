package org.remus.giteabot.secret;

import org.springframework.context.MessageSourceResolvable;

/**
 * Thrown when a key cannot be resolved, e.g. because it is invalid for its {@link SecretSource}.
 * <p>
 * Translatable as {@link MessageSourceResolvable}, so a form can show the reason in the user's
 * language. {@link #getMessage()} always stays the English default message, which keeps logs
 * readable regardless of the locale. Arguments must never contain a secret value.
 */
public class KeyResolveException extends RuntimeException implements MessageSourceResolvable {

    private final String code;
    private final Object[] arguments;

    /** Untranslated: forms show the message as it is. */
    public KeyResolveException(String message) {
        this(null, null, message);
    }

    /** Untranslated: forms show the message as it is. */
    public KeyResolveException(String message, Throwable cause) {
        super(message, cause);
        this.code = null;
        this.arguments = null;
    }

    /**
     * @param code           message key in messages*.properties
     * @param arguments      arguments for the key's placeholders, never a secret value
     * @param defaultMessage English message, used for {@link #getMessage()} and when the key is missing
     */
    public KeyResolveException(String code, Object[] arguments, String defaultMessage) {
        super(defaultMessage);
        this.code = code;
        this.arguments = arguments;
    }

    @Override
    public String[] getCodes() {
        return code == null ? null : new String[]{code};
    }

    @Override
    public Object[] getArguments() {
        return arguments;
    }

    @Override
    public String getDefaultMessage() {
        return getMessage();
    }
}
