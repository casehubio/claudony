package io.casehub.claudony.server;

/**
 * Fired by ClaudonyWorkerStatusListener when a CaseHub worker lifecycle event occurs.
 * Observed by CaseEventBroadcaster in claudony-app to push SSE updates to connected clients.
 */
public record WorkerCaseLifecycleEvent(String caseId, String tenancyId) {}
