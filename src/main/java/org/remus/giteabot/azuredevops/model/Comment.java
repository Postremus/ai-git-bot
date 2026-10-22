package org.remus.giteabot.azuredevops.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Objects;

// https://learn.microsoft.com/en-us/rest/api/azure/devops/git/pull-request-threads/create?view=azure-devops-rest-6.0&tabs=HTTP#comment
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Comment {
    private Long id;
    private CommentType commentType;
    private String content;
    private Long parentCommentId;

    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    public enum CommentType {
        UNKNOWN,
        TEXT,
        CODE_CHANGE,
        SYSTEM
    }
}
