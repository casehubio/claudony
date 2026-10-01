package io.casehub.claudony.casehub.fleet;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

@DefaultBean
@ApplicationScoped
public class NoOpSessionLifecycleListener implements SessionLifecycleListener {
}
