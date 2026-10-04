package org.remus.giteabot.secret;

import org.remus.giteabot.util.DetailedException;
import org.springframework.context.MessageSourceResolvable;

import java.util.List;

/**
 * Thrown when a configuration about to be saved holds {@code ${type:key}} references that cannot be
 * resolved. Carries every problem separately, so a form can list them one by one.
 */
public class UnresolvableSecretReferencesException extends DetailedException {

    public UnresolvableSecretReferencesException(List<MessageSourceResolvable> problems) {
        super("flash.secretReferencesUnresolvable", problems);
    }
}
