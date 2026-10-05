package org.remus.giteabot.util;

import org.springframework.context.MessageSourceResolvable;

import java.util.List;

/**
 * An exception that carries several user-facing problems, not just one message.
 * {@link org.remus.giteabot.web.FormErrors} shows the translated summary - this exception as
 * {@link MessageSourceResolvable} - as the error banner and each translated detail as a bullet below it.
 * <p>
 * Translations live in messages*.properties. A detail may carry other {@link MessageSourceResolvable}s
 * as arguments; the {@link org.springframework.context.MessageSource} resolves those first. Since
 * a message with arguments is formatted by {@link java.text.MessageFormat}, an apostrophe in its
 * text has to be written as {@code ''}.
 */
public abstract class DetailedException extends IllegalArgumentException implements MessageSourceResolvable {

    private final String code;
    private final List<MessageSourceResolvable> details;

    /**
     * @param code    message key of the summary line shown above the details
     * @param details the individual problems; their default messages make up {@link #getMessage()}
     */
    protected DetailedException(String code, List<? extends MessageSourceResolvable> details) {
        super(String.join("; ", details.stream().map(MessageSourceResolvable::getDefaultMessage).toList()));
        this.code = code;
        this.details = List.copyOf(details);
    }

    @Override
    public String[] getCodes() {
        return new String[]{code};
    }

    /** Falls back to the joined details when the summary key is missing in a translation. */
    @Override
    public String getDefaultMessage() {
        return getMessage();
    }

    public List<MessageSourceResolvable> getDetails() {
        return details;
    }
}
