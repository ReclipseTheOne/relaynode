package org.reclipse.relaynode.network;

import java.util.UUID;

public class Circuit {
	public static final int CIRCUIT_TIMEOUT = 1000 * 60 * 15; // 15 minutes

	private final String circuitId;
	private final UUID previousNode;
	private final UUID nextNode;
	private final byte[] sessionKey; // AES key for this circuit
	private final long creationTime;

	public Circuit(String circuitId, UUID previousNode, UUID nextNode, byte[] sessionKey) {
		this.circuitId = circuitId;
		this.previousNode = previousNode;
		this.nextNode = nextNode;
		this.sessionKey = sessionKey;
		this.creationTime = System.currentTimeMillis();
	}

	public boolean isExpired() {
		return System.currentTimeMillis() - creationTime > CIRCUIT_TIMEOUT;
	}

	public String getCircuitId() {
		return circuitId;
	}

	public UUID getPreviousNode() {
		return previousNode;
	}

	public UUID getNextNode() {
		return nextNode;
	}

	public byte[] getSessionKey() {
		return sessionKey;
	}

	public long getCreationTime() {
		return creationTime;
	}
}