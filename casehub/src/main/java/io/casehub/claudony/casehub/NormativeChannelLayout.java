package io.casehub.claudony.casehub;

import io.casehub.api.model.CaseDefinition;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class NormativeChannelLayout implements CaseChannelLayout {

    @Override
    public List<ChannelSpec> channelsFor(UUID caseId, CaseDefinition definition) {
        return List.of(
                new ChannelSpec("work", ChannelSemantic.APPEND, null, null,
                        "Primary coordination — all obligation-carrying message types"),
                new ChannelSpec("observe", ChannelSemantic.APPEND, Set.of(MessageType.EVENT), null,
                        "Telemetry — EVENT only, no obligations created"),
                new ChannelSpec("oversight", ChannelSemantic.APPEND,
                        null,                       // allowedTypes: open — all obligation-carrying types permitted
                        // If a new MessageType is added to Qhorus with no commitment effect (like EVENT),
                        // add it here. This comment is the mechanical anchor for that obligation.
                        Set.of(MessageType.EVENT),  // deniedTypes: no telemetry on the governance channel
                        "Human governance — all obligation-carrying types; no telemetry")
        );
    }
}
