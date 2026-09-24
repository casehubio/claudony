package io.casehub.claudony.server.api;

import io.casehub.claudony.casehub.AgentCase;
import io.casehub.claudony.casehub.browser.CaseBrowserService;
import io.casehub.claudony.casehub.browser.CaseDetail;
import io.casehub.claudony.casehub.browser.CaseSummary;
import io.casehub.claudony.casehub.inbox.ActionAggregationService;
import io.casehub.claudony.casehub.inbox.ActionInboxResponse;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

@McpDomain(value = "claudony/cases", app = "claudony", basePath = "/api/claudony/cases", summary = "Agent case coordination — assign and track across fleet")
@ApplicationScoped
public class ClaudonyCaseApi {

    @Inject CaseBrowserService caseBrowserService;
    @Inject Instance<AgentCase> agentCase;
    @Inject ActionAggregationService aggregationService;

    @PlatformQuery("List CaseHub cases")
    @RestPath("/")
    public CaseListResult listCases() {
        var cases = caseBrowserService.listCases();
        return new CaseListResult(cases, cases.size());
    }

    @PlatformQuery("Get case details")
    @RestPath("/{id}")
    public CaseDetail getCaseDetail(@PathParam UUID id) {
        return caseBrowserService.getCaseDetail(id).orElse(null);
    }

    @PlatformMutation("Start a CaseHub agent")
    @RestPath("/agent")
    public CaseStartResult startAgent() {
        if (agentCase.isUnsatisfied()) {
            throw new IllegalStateException("CaseHub engine not available");
        }
        return new CaseStartResult(agentCase.get().startCase());
    }

    @PlatformQuery("List pending actions in the inbox")
    @RestPath("/actions")
    public ActionInboxResponse listActions() {
        return aggregationService.listActions();
    }


    public record CaseListResult(List<CaseSummary> entities, int totalCount) {}

    public record CaseStartResult(UUID caseId) {}
}
