package org.remus.giteabot.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * API response model for an e-mail address of the authenticated GitHub account.
 * Returned by GET /user/emails
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubEmail {

    private String email;

    private boolean primary;

    private boolean verified;
}
