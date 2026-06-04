package io.casehub.claudony.casehub;

import io.casehub.api.model.CaseDefinition;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
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
            /** Comma-separated permitted MessageType names, or {@code null} if open to all types. */
            Set<MessageType> allowedTypes,
            /**
             * MessageTypes explicitly denied on this channel. {@code null} = no denial.
             * Denial wins when a type appears in both allowedTypes and deniedTypes.
             * If a new MessageType is added to Qhorus with no commitment effect (like EVENT),
             * add it here for governance channels — this comment is the mechanical anchor for that obligation.
             */
            Set<MessageType> deniedTypes,
            String description
    ) {}
}
