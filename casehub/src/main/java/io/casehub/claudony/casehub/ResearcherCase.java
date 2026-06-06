package io.casehub.claudony.casehub;

import io.casehub.api.engine.YamlCaseHub;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Production researcher case definition loaded from classpath YAML.
 *
 * When CaseHub is disabled (CaseHubRuntime absent from CDI), this bean exists
 * but startCase() is never called — no effect on non-CaseHub deployments.
 *
 * Completion: case auto-completes when the researcher tmux session exits.
 * The exit watcher stores a pending signal; ClaudonyLedgerEventCapture drains
 * it on WorkerExecutionCompleted and calls CaseHubRuntime.signal() which sets
 * context.workers.researcher.exited = true, satisfying the goal condition.
 */
@ApplicationScoped
public class ResearcherCase extends YamlCaseHub {

    public ResearcherCase() {
        super("casehub/researcher.yaml");
    }
}
