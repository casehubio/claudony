package io.casehub.claudony.casehub.inbox;

import java.util.List;

public interface WorkItemActionSource {
    List<WorkItemView> findActionableItems(String tenancyId);
}
