package io.casehub.claudony.casehub.fleet;

import java.util.Map;

public interface DemandMetricsSource {
    Map<String, Double> collect(String poolName);
}
