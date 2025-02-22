package org.reclipse.relaynode.network.discovery;

import java.io.Serializable;
import java.security.PublicKey;
import java.util.UUID;

/**
 * Contains information about a node in the relay network
 */
public class NodeInfo implements Serializable {

	// Need to handle serialization of PublicKey separately
	private static final long serialVersionUID = 1L;

	private final UUID nodeId;
	private final String nodeName;
	private final String address;
	private transient PublicKey publicKey; // Transient because PublicKey is not Serializable
	private String publicKeyEncoded; // Store encoded version for serialization
	private final NodeType nodeType;

	private long lastSeen;
	private int latency;
	private boolean isOnline;

	/**
	 * Creates a new NodeInfo
	 */
	public NodeInfo(UUID nodeId, String nodeName, String address, PublicKey publicKey, NodeType nodeType) {
		this.nodeId = nodeId;
		this.nodeName = nodeName;
		this.address = address;
		this.publicKey = publicKey;
		this.nodeType = nodeType;
		this.lastSeen = System.currentTimeMillis();
		this.latency = -1; // Unknown initial latency
		this.isOnline = true;

		// Store encoded public key for serialization
		if (publicKey != null) {
			this.publicKeyEncoded = java.util.Base64.getEncoder().encodeToString(publicKey.getEncoded());
		}
	}

	/**
	 * Gets the node ID
	 */
	public UUID getNodeId() {
		return nodeId;
	}

	/**
	 * Gets the node name
	 */
	public String getNodeName() {
		return nodeName;
	}

	/**
	 * Gets the node address
	 */
	public String getAddress() {
		return address;
	}

	/**
	 * Gets the node's public key
	 */
	public PublicKey getPublicKey() {
		return publicKey;
	}

	/**
	 * Gets the node type
	 */
	public NodeType getNodeType() {
		return nodeType;
	}

	/**
	 * Gets the time when the node was last seen
	 */
	public long getLastSeen() {
		return lastSeen;
	}

	/**
	 * Updates the last seen timestamp to now
	 */
	public void updateLastSeen() {
		this.lastSeen = System.currentTimeMillis();
	}

	/**
	 * Gets the node latency
	 */
	public int getLatency() {
		return latency;
	}

	/**
	 * Sets the node latency
	 */
	public void setLatency(int latency) {
		this.latency = latency;
	}

	/**
	 * Checks if the node is online
	 */
	public boolean isOnline() {
		return isOnline;
	}

	/**
	 * Sets the node's online status
	 */
	public void setOnline(boolean online) {
		isOnline = online;
	}

	/**
	 * Custom serialization logic can be added to handle the PublicKey
	 */
	private void writeObject(java.io.ObjectOutputStream out) throws java.io.IOException {
		out.defaultWriteObject();
	}

	/**
	 * Custom deserialization logic to restore the PublicKey
	 */
	private void readObject(java.io.ObjectInputStream in)
			throws java.io.IOException, ClassNotFoundException {
		in.defaultReadObject();

		// Restore public key from encoded string
		if (publicKeyEncoded != null) {
			try {
				byte[] keyBytes = java.util.Base64.getDecoder().decode(publicKeyEncoded);
				java.security.spec.X509EncodedKeySpec keySpec = new java.security.spec.X509EncodedKeySpec(keyBytes);
				java.security.KeyFactory keyFactory = java.security.KeyFactory.getInstance("RSA");
				this.publicKey = keyFactory.generatePublic(keySpec);
			} catch (Exception e) {
				System.err.println("Error restoring public key: " + e.getMessage());
			}
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		NodeInfo nodeInfo = (NodeInfo) o;
		return nodeId.equals(nodeInfo.nodeId);
	}

	@Override
	public int hashCode() {
		return nodeId.hashCode();
	}

	@Override
	public String toString() {
		return "NodeInfo{" +
				"nodeId=" + nodeId +
				", nodeName='" + nodeName + '\'' +
				", address='" + address + '\'' +
				", nodeType=" + nodeType +
				", isOnline=" + isOnline +
				'}';
	}
}