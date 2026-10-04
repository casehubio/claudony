package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelFallbackEventObserverTest {

    @Test
    void onFallback_incrementsCount() {
        var observer = new ModelFallbackEventObserver();

        observer.onFallback(new ModelFallbackEvent("reviewer", "opus", "sonnet", 1));

        assertThat(observer.fallbackCount()).isEqualTo(1);
    }

    @Test
    void multipleFallbacks_countAccumulates() {
        var observer = new ModelFallbackEventObserver();

        observer.onFallback(new ModelFallbackEvent("reviewer", "opus", "sonnet", 1));
        observer.onFallback(new ModelFallbackEvent("coder", "opus", "haiku", 2));

        assertThat(observer.fallbackCount()).isEqualTo(2);
    }

    @Test
    void resetForTest_clearsCount() {
        var observer = new ModelFallbackEventObserver();
        observer.onFallback(new ModelFallbackEvent("reviewer", "opus", "sonnet", 1));

        observer.resetForTest();

        assertThat(observer.fallbackCount()).isZero();
    }
}
