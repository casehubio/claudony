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
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void reconcileDetectsCapacityChange() {
        var original = descriptorWithClaudonyConfig("agent-a", java.util.Map.of("minActive", "1", "maxActive", "5"));
        when(agentRegistry.find(any(AgentQuery.class)))
                .thenReturn(List.of(new AgentMatch(original, null)));
        bridge.onStartup();
        verify(poolRegistry).register(any(), eq("eidos"));

        var updated = descriptorWithClaudonyConfig("agent-a", java.util.Map.of("minActive", "2", "maxActive", "10"));
        when(agentRegistry.find(any(AgentQuery.class)))
                .thenReturn(List.of(new AgentMatch(updated, null)));
        when(poolRegistry.names()).thenReturn(Set.of("agent-a"));

        bridge.reconcile();

        verify(poolRegistry).updateCapacity("agent-a", 2, 10);
    }

    @Test
    void reconcileDetectsConfigChange() {
        var original = descriptorWithClaudonyConfig("agent-b", java.util.Map.of("command", "claude"));
        when(agentRegistry.find(any(AgentQuery.class)))
                .thenReturn(List.of(new AgentMatch(original, null)));
        bridge.onStartup();

        var updated = descriptorWithClaudonyConfig("agent-b", java.util.Map.of("command", "claude --model opus"));
        when(agentRegistry.find(any(AgentQuery.class)))
                .thenReturn(List.of(new AgentMatch(updated, null)));
        when(poolRegistry.names()).thenReturn(Set.of("agent-b"));

        bridge.reconcile();

        verify(poolRegistry).updateAgentConfig(eq("agent-b"), argThat(c -> "claude --model opus".equals(c.command())));
    }

    @Test
    void reconcileNoOpWhenUnchanged() {
        var descriptor = descriptorWithClaudonyConfig("agent-c");
        when(agentRegistry.find(any(AgentQuery.class)))
                .thenReturn(List.of(new AgentMatch(descriptor, null)));
        bridge.onStartup();

        when(poolRegistry.names()).thenReturn(Set.of("agent-c"));
        when(agentRegistry.find(any(AgentQuery.class)))
                .thenReturn(List.of(new AgentMatch(descriptor, null)));

        bridge.reconcile();

        verify(poolRegistry, never()).updateCapacity(anyString(), anyInt(), anyInt());
        verify(poolRegistry, never()).updateAgentConfig(anyString(), any());
    }

    @Test
    void reconcileLogsMissingDescriptorButDoesNotDestroy() {
        var descriptor = descriptorWithClaudonyConfig("agent-d");
        when(agentRegistry.find(any(AgentQuery.class)))
                .thenReturn(List.of(new AgentMatch(descriptor, null)));
        bridge.onStartup();

        when(poolRegistry.names()).thenReturn(Set.of("agent-d"));
        when(agentRegistry.find(any(AgentQuery.class)))
                .thenReturn(List.of());

        bridge.reconcile(); // should not throw
    }

    private AgentDescriptor descriptorWithClaudonyConfig(String agentId, java.util.Map<String, String> config) {
        return AgentDescriptor.builder()
                              .agentId(agentId)
                              .name(agentId)
                              .slot("worker")
                              .tenancyId("default")
                              .providerConfigs(List.of(new ProviderConfig("claudony", config)))
                              .build();
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
