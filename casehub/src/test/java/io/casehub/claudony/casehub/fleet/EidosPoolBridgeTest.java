package io.casehub.claudony.casehub.fleet;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentMatch;
import io.casehub.eidos.api.AgentQuery;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.ProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EidosPoolBridgeTest {

    private AgentRegistry agentRegistry;
    private AgentPoolDefinitionRegistry poolRegistry;
    private EidosPoolBridge bridge;
    private static final EidosPoolBridgeConfig CONFIG = EidosPoolBridgeConfig.DEFAULTS;
    private static final String TENANCY_ID = "default";

    @BeforeEach
    void setUp() {
        agentRegistry = mock(AgentRegistry.class);
        poolRegistry = mock(AgentPoolDefinitionRegistry.class);
        when(poolRegistry.names()).thenReturn(Set.of());
        bridge = new EidosPoolBridge(agentRegistry, poolRegistry, CONFIG, TENANCY_ID);
    }

    @Test
    void startupRegistersMatchedAgents() {
        var descriptor = descriptorWithClaudonyConfig("code-reviewer");
        when(agentRegistry.find(any(AgentQuery.class)))
                .thenReturn(List.of(new AgentMatch(descriptor, null)));

        bridge.onStartup();

        verify(poolRegistry).register(argThat(def ->
                def.agent().name().equals("code-reviewer")
                && "code-reviewer".equals(def.agent().eidosAgentId())), eq("eidos"));
    }

    @Test
    void agentsWithoutClaudonyConfigSkipped() {
        var descriptor = AgentDescriptor.builder()
                .agentId("openai-agent")
                .name("OpenAI Agent")
                .slot("worker")
                .tenancyId("default")
                .providerConfigs(List.of(new ProviderConfig("openai", Map.of())))
                .build();
        when(agentRegistry.find(any(AgentQuery.class)))
                .thenReturn(List.of(new AgentMatch(descriptor, null)));

        bridge.onStartup();

        verify(poolRegistry, never()).register(any(), anyString());
    }

    @Test
    void reconcileAddsNewAgents() {
        when(poolRegistry.names()).thenReturn(Set.of());
        var descriptor = descriptorWithClaudonyConfig("new-agent");
        when(agentRegistry.find(any(AgentQuery.class)))
                .thenReturn(List.of(new AgentMatch(descriptor, null)));

        bridge.reconcile();

        verify(poolRegistry).register(argThat(def ->
                def.agent().name().equals("new-agent")), eq("eidos"));
    }

    @Test
    void reconcileSkipsAlreadyRegistered() {
        when(poolRegistry.names()).thenReturn(Set.of("existing-agent"));
        var descriptor = descriptorWithClaudonyConfig("existing-agent");
        when(agentRegistry.find(any(AgentQuery.class)))
                .thenReturn(List.of(new AgentMatch(descriptor, null)));

        bridge.reconcile();

        verify(poolRegistry, never()).register(any(), anyString());
    }

    @Test
    void emptyRegistryNoError() {
        when(agentRegistry.find(any(AgentQuery.class)))
                .thenReturn(List.of());

        bridge.onStartup();

        verify(poolRegistry, never()).register(any(), anyString());
    }

    @Test
    void registryExceptionDoesNotPropagate() {
        when(agentRegistry.find(any(AgentQuery.class)))
                .thenThrow(new RuntimeException("registry unavailable"));

        bridge.onStartup();

        verify(poolRegistry, never()).register(any(), anyString());
    }

    private AgentDescriptor descriptorWithClaudonyConfig(String agentId) {
        return AgentDescriptor.builder()
                .agentId(agentId)
                .name(agentId)
                .slot("worker")
                .tenancyId("default")
                .providerConfigs(List.of(
                        new ProviderConfig("claudony", Map.of())))
                .build();
    }
}
