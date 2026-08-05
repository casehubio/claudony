package io.casehub.claudony.casehub.inbox;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@DefaultBean
@ApplicationScoped
public class EmptyWorkItemActionSource implements WorkItemActionSource {

    @Override
    public List<WorkItemView> findActionableItems(String tenancyId) {
        return List.of();
    }
}
