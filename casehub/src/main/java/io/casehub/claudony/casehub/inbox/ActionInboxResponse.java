package io.casehub.claudony.casehub.inbox;

import java.util.List;

public record ActionInboxResponse(List<ActionItem> items, ActionCounts counts) {}
