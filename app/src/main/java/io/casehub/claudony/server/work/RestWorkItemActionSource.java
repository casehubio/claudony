package io.casehub.claudony.server.work;

import io.casehub.claudony.casehub.inbox.WorkItemActionSource;
import io.casehub.claudony.casehub.inbox.WorkItemView;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class RestWorkItemActionSource implements WorkItemActionSource {

    private static final Logger LOG = Logger.getLogger(RestWorkItemActionSource.class);

    private final WorkServiceClient client;
    private final String baseUrl;

    @Inject
    public RestWorkItemActionSource(@RestClient WorkServiceClient client,
                                     @ConfigProperty(name = "claudony.work-service.url") Optional<String> baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl.orElse("");
    }

    @Override
    public List<WorkItemView> findActionableItems(String tenancyId) {
        if (baseUrl.isEmpty()) return List.of();
        try {
            return client.inbox(null, null, null).stream()
                    .map(this::toView)
                    .toList();
        } catch (Exception e) {
            LOG.warnf("Work service unavailable: %s", e.getMessage());
            return List.of();
        }
    }

    private WorkItemView toView(WorkServiceResponse r) {
        return new WorkItemView(
                r.id(), r.title(), r.status(), r.priority(),
                r.expiresAt(), r.claimDeadline(), r.createdAt(),
                r.assigneeId(),
                baseUrl + "/workitems/" + r.id());
    }
}
