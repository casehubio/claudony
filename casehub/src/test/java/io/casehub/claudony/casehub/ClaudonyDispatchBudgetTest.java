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

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.spi.DispatchBudgetQuery;
import io.casehub.claudony.server.SessionRegistry;
import io.casehub.claudony.server.model.Session;
import io.casehub.claudony.server.model.SessionStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClaudonyDispatchBudgetTest {

  private static SessionRegistry registryWith(String... sessionIds) {
    var registry = new SessionRegistry(() -> null);
    for (String id : sessionIds) {
      registry.register(new Session(
          id, id, "/tmp", "claude", SessionStatus.ACTIVE,
          Instant.now(), Instant.now(), Optional.empty(),
          Optional.empty(), Optional.empty(), null));
    }
    return registry;
  }

  @Test
  void unlimited_when_maxSessions_zero() {
    var registry = registryWith("claudony-worker-abc", "claudony-worker-def");
    var budget = new ClaudonyDispatchBudget(registry, 0, 0);
    assertThat(budget.availableCapacity(new DispatchBudgetQuery(UUID.randomUUID(), "t1")))
        .isEqualTo(Integer.MAX_VALUE);
  }

  @Test
  void returns_remaining_capacity() {
    var registry = registryWith(
        "claudony-worker-abc", "claudony-worker-def", "user-session-1");
    var budget = new ClaudonyDispatchBudget(registry, 5, 0);
    assertThat(budget.availableCapacity(new DispatchBudgetQuery(UUID.randomUUID(), "t1")))
        .isEqualTo(3);
  }

  @Test
  void returns_zero_when_at_max() {
    var registry = registryWith(
        "claudony-worker-abc", "claudony-worker-def", "claudony-worker-ghi");
    var budget = new ClaudonyDispatchBudget(registry, 3, 0);
    assertThat(budget.availableCapacity(new DispatchBudgetQuery(UUID.randomUUID(), "t1")))
        .isEqualTo(0);
  }

  @Test
  void non_worker_sessions_not_counted() {
    var registry = registryWith(
        "user-session-1", "user-session-2", "claudony-worker-abc");
    var budget = new ClaudonyDispatchBudget(registry, 5, 0);
    assertThat(budget.availableCapacity(new DispatchBudgetQuery(UUID.randomUUID(), "t1")))
        .isEqualTo(4);
  }

  @Test
  void minSessions_guarantees_floor() {
    var registry = registryWith(
        "claudony-worker-1", "claudony-worker-2", "claudony-worker-3",
        "claudony-worker-4", "claudony-worker-5");
    var budget = new ClaudonyDispatchBudget(registry, 5, 2);
    assertThat(budget.availableCapacity(new DispatchBudgetQuery(UUID.randomUUID(), "t1")))
        .isEqualTo(2);
  }

  @Test
  void minSessions_does_not_inflate_when_capacity_available() {
    var registry = registryWith("claudony-worker-1");
    var budget = new ClaudonyDispatchBudget(registry, 10, 2);
    assertThat(budget.availableCapacity(new DispatchBudgetQuery(UUID.randomUUID(), "t1")))
        .isEqualTo(9);
  }
}
