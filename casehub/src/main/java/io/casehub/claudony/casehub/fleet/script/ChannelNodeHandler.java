package io.casehub.claudony.casehub.fleet.script;

import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ChannelNodeHandler implements FleetNodeHandler {

    private static final Logger LOG = Logger.getLogger(ChannelNodeHandler.class);

    @FunctionalInterface
    public interface ChannelCreator {
        void create(ChannelCreateRequest request);
    }

    private final ChannelCreator creator;

    public ChannelNodeHandler(ChannelCreator creator) {
        this.creator = creator;
    }

    @Override
    public String type() { return "channel"; }

    @Override
    @SuppressWarnings("unchecked")
    public NodeResult handle(String nodeName, Map<String, Object> spec) {
        var name = (String) spec.get("name");
        if (name == null || name.isBlank()) {
            return NodeResult.failed(nodeName, "channel", "name is required in channel spec");
        }

        try {
            var description = (String) spec.getOrDefault("description", "");
            var semanticStr = (String) spec.get("semantic");
            var semantic = semanticStr != null
                    ? ChannelSemantic.valueOf(semanticStr)
                    : ChannelSemantic.APPEND;

            Set<MessageType> allowedTypes = Set.of();
            var typesList = (List<String>) spec.get("allowedTypes");
            if (typesList != null) {
                allowedTypes = typesList.stream()
                        .map(MessageType::valueOf)
                        .collect(Collectors.toSet());
            }

            var request = new ChannelCreateRequest(name, description, semantic,
                    null, null, null, null, null,
                    allowedTypes, Set.of(),
                    null, null, null, null, null);
            creator.create(request);

            LOG.infof("Channel '%s' created (name=%s, semantic=%s)", nodeName, name, semantic);
            return NodeResult.ok(nodeName, "channel", "created channel=" + name);
        } catch (Exception e) {
            return NodeResult.failed(nodeName, "channel", e.getMessage());
        }
    }
}
