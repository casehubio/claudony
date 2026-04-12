package dev.claudony.server.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GitStatusResponse(
        boolean gitRepo,
        String githubRepo,
        String branch,
        PrInfo pr,
        String error
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PrInfo(
            int number,
            String title,
            String url,
            String state,
            int checksTotal,
            int checksPassed,
            int checksFailed,
            int checksPending
    ) {}

    public static GitStatusResponse notGit() {
        return new GitStatusResponse(false, null, null, null, null);
    }

    public static GitStatusResponse noGitHub(String branch) {
        return new GitStatusResponse(true, null, branch, null, null);
    }

    public static GitStatusResponse noPr(String githubRepo, String branch) {
        return new GitStatusResponse(true, githubRepo, branch, null, null);
    }

    public static GitStatusResponse withPr(String githubRepo, String branch, PrInfo pr) {
        return new GitStatusResponse(true, githubRepo, branch, pr, null);
    }

    public static GitStatusResponse error(String githubRepo, String branch, String error) {
        return new GitStatusResponse(true, githubRepo, branch, null, error);
    }
}
