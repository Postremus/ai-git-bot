package org.remus.giteabot.secret;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.support.DefaultMessageSourceResolvable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
public class SecretTemplate {
    private final SecretSourceRegistry secretSourceRegistry;
    private final List<Segment> segments;

    SecretTemplate(SecretSourceRegistry secretSourceRegistry, List<Segment> segments) {
        this.secretSourceRegistry = secretSourceRegistry;
        this.segments = segments;
    }

    public String expose() {
        return segments.stream().map(segment -> {
            switch (segment) {
                case Segment.SecretReference sr -> {
                    Optional<SecretSource> optionalSecretSource = secretSourceRegistry.retrieve(sr.type());

                    if (optionalSecretSource.isEmpty()) {
                        log.error("Could not find secret source for type {}", sr.type());
                        return sr.raw();
                    }

                    SecretSource secretSource = optionalSecretSource.get();
                    Optional<SecretValue> resolved = secretSource.resolve(sr.key());
                    if (resolved.isEmpty()) {
                        return sr.raw();
                    }

                    return resolved.get().value().strip();
                }
                case Segment.Literal literal -> {
                    return literal.raw();
                }
            }
        }).collect(Collectors.joining());
    }

    /**
     * Checks every reference of this template without exposing a value.
     *
     * @return one translatable problem per reference that cannot be resolved, empty if all resolve
     */
    public List<MessageSourceResolvable> validate() {
        return segments.stream().map(segment -> {
            if (segment instanceof Segment.SecretReference(String type, String key, String raw)) {

                Optional<SecretSource> optionalSecretSource = secretSourceRegistry.retrieve(type);

                if (optionalSecretSource.isEmpty()) {
                    return problem("secret.error.sourceNotFound",
                            "%s: Could not find secret source for type %s".formatted(raw, type), raw, type);
                }

                SecretSource secretSource = optionalSecretSource.get();

                try {
                    if (!secretSource.resolvable(key)) {
                        return problem("secret.error.notResolvable",
                                "%s: Key %s is not resolvable".formatted(raw, key), raw, key);
                    }
                } catch (KeyResolveException e) {
                    // The source builds this message itself, so only the surrounding text is translated
                    return problem("secret.error.invalidKey",
                            "%s: %s".formatted(raw, e.getMessage()), raw, e.getMessage());
                }
            }
            return null;
        }).filter(Objects::nonNull).toList();
    }

    private static MessageSourceResolvable problem(String code, String defaultMessage, Object... arguments) {
        return new DefaultMessageSourceResolvable(new String[]{code}, arguments, defaultMessage);
    }

    public String raw() {
        return segments.stream().map(Segment::raw).collect(Collectors.joining());
    }
}
