package io.casehub.claudony.server;

import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

@ApplicationScoped
public class NoOpLedgerTraceIdProvider implements LedgerTraceIdProvider {

    @Override
    public Optional<String> currentTraceId() {
        return Optional.empty();
    }
}
