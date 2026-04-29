package dev.claudony.casehub;

import io.casehub.api.model.CaseDefinition;
import io.quarkiverse.qhorus.api.channel.ChannelSemantic;
import io.quarkiverse.qhorus.api.message.MessageType;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class SimpleLayout implements CaseChannelLayout {

    @Override
    public List<ChannelSpec> channelsFor(UUID caseId, CaseDefinition definition) {
        return List.of(
                new ChannelSpec("work", ChannelSemantic.APPEND, null,
                        "Primary coordination — all obligation-carrying message types"),
                new ChannelSpec("observe", ChannelSemantic.APPEND, Set.of(MessageType.EVENT),
                        "Telemetry — EVENT only, no obligations created")
        );
    }
}
