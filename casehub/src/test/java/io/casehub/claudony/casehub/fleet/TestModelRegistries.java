package io.casehub.claudony.casehub.fleet;

import io.casehub.platform.api.model.CostTier;
import io.casehub.platform.api.model.ModelDescriptor;
import io.casehub.platform.api.model.ModelLocality;
import io.casehub.platform.api.model.ModelQuery;
import io.casehub.platform.api.model.ModelRegistry;
import io.casehub.platform.api.model.ModelTier;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class TestModelRegistries {

    private TestModelRegistries() {}

    static ModelRegistry withIds(String... modelIds) {
        return new ModelRegistry() {
            @Override public Optional<ModelDescriptor> resolveById(String id) {
                return Arrays.stream(modelIds).filter(m -> m.equals(id)).findFirst()
                        .map(m -> new ModelDescriptor(m, m, "claude", "default",
                                "anthropic", "claude", m, ModelTier.FLAGSHIP,
                                Set.of(), 200000, 16000, ModelLocality.CLOUD, CostTier.HIGH, null, Map.of()));
            }
            @Override public List<ModelDescriptor> query(ModelQuery query) {
                return all().stream().filter(d -> query.tier() == null || d.tier() == query.tier()).toList();
            }
            @Override public List<ModelDescriptor> all() {
                return Arrays.stream(modelIds).map(m -> resolveById(m).orElseThrow()).toList();
            }
        };
    }
}
