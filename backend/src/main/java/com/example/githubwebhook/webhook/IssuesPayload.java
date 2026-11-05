package com.example.githubwebhook.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record IssuesPayload(String action, Issue issue, Repository repository) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Issue(long number, String title, @JsonProperty("html_url") String htmlUrl) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(@JsonProperty("full_name") String fullName) {
    }
}
