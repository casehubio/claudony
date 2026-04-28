package dev.claudony.casehub;

import io.casehub.api.model.CaseDefinition;
import io.quarkiverse.qhorus.runtime.channel.ChannelSemantic;
import io.quarkiverse.qhorus.runtime.message.MessageType;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface CaseChannelLayout {

    /** @param definition the case definition; may be {@code null} if not available at channel-open time */
    List<ChannelSpec> channelsFor(UUID caseId, CaseDefinition definition);

    static CaseChannelLayout named(String configValue) {
        return switch (configValue) {
            case "normative" -> new NormativeChannelLayout();
            case "simple" -> new SimpleLayout();
            default -> throw new IllegalArgumentException("Unknown channel layout: " + configValue);
        };
    }

    record ChannelSpec(
            String purpose,
            ChannelSemantic semantic,
            Set<MessageType> allowedTypes,
            String description
    ) {}
}
