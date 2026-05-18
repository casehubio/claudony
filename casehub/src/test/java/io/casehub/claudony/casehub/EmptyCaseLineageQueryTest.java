package io.casehub.claudony.casehub;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class EmptyCaseLineageQueryTest {

    @Test
    void findCompletedWorkers_returnsEmptyUni() {
        var query = new EmptyCaseLineageQuery();
        var result = query.findCompletedWorkers(UUID.randomUUID())
                          .await().indefinitely();
        assertThat(result).isEmpty();
    }
}
