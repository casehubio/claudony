package dev.claudony.casehub;

import io.casehub.api.model.CaseDefinition;
import io.quarkiverse.qhorus.runtime.channel.ChannelSemantic;
import io.quarkiverse.qhorus.runtime.message.MessageType;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface CaseChannelLayout {

    List<ChannelSpec> channelsFor(UUID caseId, CaseDefinition definition);

    record ChannelSpec(
            String purpose,
            ChannelSemantic semantic,
            Set<MessageType> allowedTypes,
            String description
    ) {}
}
