package org.reclipse.relaynode.network.discovery;

import org.reclipse.relaynode.api.StringRepresentable;

public enum NodeType implements StringRepresentable<NodeType> {
	RELAY,      // Standard relay
	ENTRY,      // First node in circuit
	EXIT,       // Last node in circuit
	BOOTSTRAP;   // Helps with peer discovery

	public static NodeType fromString(String type) {
		return switch (type) {
			case "RELAY" -> RELAY;
			case "ENTRY" -> ENTRY;
			case "EXIT" -> EXIT;
			case "BOOTSTRAP" -> BOOTSTRAP;
			default -> throw new IllegalArgumentException("Invalid node type: " + type);
		};
	}

	@Override
	public String toString() {
		return switch (this) {
			case RELAY -> "RELAY";
			case ENTRY -> "ENTRY";
			case EXIT -> "EXIT";
			case BOOTSTRAP -> "BOOTSTRAP";
		};
	}
}
