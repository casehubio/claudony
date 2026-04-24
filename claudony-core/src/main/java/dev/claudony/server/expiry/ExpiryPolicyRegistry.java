package dev.claudony.server.expiry;

import dev.claudony.config.ClaudonyConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@ApplicationScoped
public class ExpiryPolicyRegistry {

    private static final Logger LOG = Logger.getLogger(ExpiryPolicyRegistry.class);

    private final Map<String, ExpiryPolicy> policies;
    private final String defaultPolicyName;

    @Inject
    ExpiryPolicyRegistry(@Any Instance<ExpiryPolicy> all, ClaudonyConfig config) {
        this.defaultPolicyName = config.sessionExpiryPolicy();
        this.policies = StreamSupport.stream(all.spliterator(), false)
                .collect(Collectors.toMap(ExpiryPolicy::name, p -> p));
        if (!this.policies.containsKey(defaultPolicyName)) {
            LOG.warnf("Configured session-expiry-policy '%s' not found. Available: %s",
                    defaultPolicyName, policies.keySet());
        }
        LOG.infof("ExpiryPolicyRegistry initialised with %d policies: %s",
                policies.size(), policies.keySet());
    }

    public ExpiryPolicy resolve(String name) {
        if (name != null && policies.containsKey(name)) return policies.get(name);
        var fallback = policies.get(defaultPolicyName);
        if (fallback != null) return fallback;
        if (policies.isEmpty()) throw new IllegalStateException("No ExpiryPolicy beans registered");
        LOG.warnf("Default policy '%s' not found; applying first available. Check configuration.", defaultPolicyName);
        return policies.values().iterator().next();
    }

    public Set<String> availableNames() {
        return Collections.unmodifiableSet(policies.keySet());
    }
}
