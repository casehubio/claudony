/*
 * Copyright 2026-Present The Case Hub Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.casehub.claudony.casehub;

import io.casehub.api.spi.DispatchBudget;
import io.casehub.api.spi.DispatchBudgetQuery;
import io.casehub.claudony.server.SessionRegistry;
import io.casehub.claudony.server.model.Session;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Session-level dispatch budget backed by claudony's worker session pool. Displaces engine's
 * {@code NoOpDispatchBudget} (@DefaultBean) automatically.
 *
 * <p>Counts active worker sessions (prefix {@link ClaudonyWorkerProvisioner#SESSION_PREFIX}) against
 * {@code claudony.casehub.workers.max-sessions}. Returns remaining capacity. Zero max = unlimited.
 *
 * <p>When {@code min-sessions} is configured, at least that many slots are always reported as
 * available — ensuring the system never starves completely even under load.
 */
@ApplicationScoped
public class ClaudonyDispatchBudget implements DispatchBudget {

  private final SessionRegistry sessionRegistry;
  private final int maxSessions;
  private final int minSessions;

  @Inject
  public ClaudonyDispatchBudget(SessionRegistry sessionRegistry, CaseHubConfig config) {
    this.sessionRegistry = sessionRegistry;
    this.maxSessions = config.workers().maxSessions();
    this.minSessions = config.workers().minSessions();
  }

  ClaudonyDispatchBudget(SessionRegistry sessionRegistry, int maxSessions, int minSessions) {
    this.sessionRegistry = sessionRegistry;
    this.maxSessions = maxSessions;
    this.minSessions = minSessions;
  }

  @Override
  public int availableCapacity(DispatchBudgetQuery query) {
    if (maxSessions <= 0) {
      return Integer.MAX_VALUE;
    }
    long active = sessionRegistry.allUnscoped().stream()
        .filter(s -> s.id().startsWith(ClaudonyWorkerProvisioner.SESSION_PREFIX))
        .count();
    int remaining = maxSessions - (int) active;
    return Math.max(remaining, minSessions);
  }
}
