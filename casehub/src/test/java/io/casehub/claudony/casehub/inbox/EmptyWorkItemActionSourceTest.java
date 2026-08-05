package io.casehub.claudony.casehub.inbox;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmptyWorkItemActionSourceTest {

    EmptyWorkItemActionSource source = new EmptyWorkItemActionSource();

    @Test
    void returnsEmptyList() {
        var result = source.findActionableItems("default");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}
