package io.casehub.claudony.casehub.browser;

import io.casehub.claudony.server.SessionRegistry;
import io.casehub.claudony.server.TenantContext;
import io.casehub.claudony.server.model.Session;
import io.casehub.claudony.server.model.SessionStatus;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.qhorus.api.channel.ChannelDetail;
import io.casehub.qhorus.runtime.dashboard.QhorusDashboardService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class CaseBrowserService {

    private final CaseInstanceRepository caseRepo;
    private final SessionRegistry sessionRegistry;
    private final QhorusDashboardService dashboardService;
    private final TenantContext tenantContext;

    @Inject
    public CaseBrowserService(CaseInstanceRepository caseRepo,
                               SessionRegistry sessionRegistry,
                               QhorusDashboardService dashboardService,
                               TenantContext tenantContext) {
        this.caseRepo = caseRepo;
        this.sessionRegistry = sessionRegistry;
        this.dashboardService = dashboardService;
        this.tenantContext = tenantContext;
    }

    public List<CaseSummary> listCases() {
        String tenancyId = tenantContext.currentTenantId();
        List<CaseInstance> cases = caseRepo.findAll(tenancyId);
        Map<String, Integer> channelCounts = channelCountsByCase();

        return cases.stream().map(ci -> {
            String caseId = ci.getUuid().toString();
            List<Session> sessions = sessionRegistry.findByCaseId(caseId);
            long activeCount = sessions.stream()
                    .filter(s -> s.status() == SessionStatus.ACTIVE)
                    .count();
            Instant lastActivity = sessions.stream()
                    .map(Session::lastActive)
                    .max(Instant::compareTo)
                    .orElse(Instant.EPOCH);
            String name = ci.getCaseMetaModel() != null ? ci.getCaseMetaModel().getName() : "unknown";

            return new CaseSummary(
                    ci.getUuid(),
                    ci.getState().name(),
                    name,
                    (int) activeCount,
                    channelCounts.getOrDefault(caseId, 0),
                    lastActivity);
        }).toList();
    }

    public Optional<CaseDetail> getCaseDetail(UUID caseId) {
        String tenancyId = tenantContext.currentTenantId();
        CaseInstance ci = caseRepo.findByUuid(caseId, tenancyId);
        if (ci == null) return Optional.empty();

        List<Session> sessions = sessionRegistry.findByCaseId(caseId.toString());
        List<WorkerInfo> workers = sessions.stream()
                .map(s -> new WorkerInfo(
                        s.id(), s.roleName().orElse("unknown"),
                        s.status().name(), s.lastActive(),
                        s.status() == SessionStatus.ACTIVE))
                .toList();

        String prefix = "case-" + caseId + "/";
        List<String> channels = dashboardService.listChannels().stream()
                .map(ChannelDetail::name)
                .filter(n -> n.startsWith(prefix))
                .toList();

        List<Map<String, Object>> timeline = channels.isEmpty()
                ? List.of()
                : dashboardService.getTimeline(channels.get(0), null, 50);

        String name = ci.getCaseMetaModel() != null ? ci.getCaseMetaModel().getName() : "unknown";
        Instant lastActivity = sessions.stream()
                .map(Session::lastActive)
                .max(Instant::compareTo)
                .orElse(Instant.EPOCH);

        return Optional.of(new CaseDetail(
                ci.getUuid(), ci.getState().name(), name,
                workers, channels, timeline, lastActivity));
    }

    private Map<String, Integer> channelCountsByCase() {
        return dashboardService.listChannels().stream()
                .map(ChannelDetail::name)
                .filter(n -> n.startsWith("case-") && n.contains("/"))
                .collect(Collectors.groupingBy(
                        n -> n.substring(5, n.indexOf('/')),
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)));
    }
}
