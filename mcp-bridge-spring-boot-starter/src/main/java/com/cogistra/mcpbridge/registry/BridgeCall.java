package com.cogistra.mcpbridge.registry;

import com.cogistra.mcpbridge.api.BridgePrincipal;
import java.util.Map;

public record BridgeCall(
    BridgePrincipal principal, String capability, Map<String, Object> arguments) {}
