package org.reclipse.relaynode.bootstrap;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.reclipse.relaynode.RelayNode.NodeType;
import org.reclipse.relaynode.network.discovery.NodeDirectory;
import org.reclipse.relaynode.network.discovery.NodeInfo;
import org.reclipse.relaynode.handlers.JSON;
import org.reclipse.relaynode.handlers.MessageEncryption;
import org.reclipse.relaynode.util.Logger;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A bootstrap server that helps nodes discover each other
 */
public class BootstrapServer extends WebSocketServer {

	private static final int RSA_KEY_SIZE = 2048;
	private static final int DEFAULT_NODE_LIST_SIZE = 10;

	private final UUID serverId;
	private final String serverName;
	private final NodeDirectory nodeDirectory;
	private final Map<WebSocket, UUID> connectionToNodeId;
	private final KeyPair keyPair;

	/**
	 * Creates a new bootstrap server
	 */
	public BootstrapServer(InetSocketAddress address, String serverName) throws NoSuchAlgorithmException {
		super(address);
		this.serverId = UUID.randomUUID();
		this.serverName = serverName;
		this.nodeDirectory = new NodeDirectory();
		this.connectionToNodeId = new ConcurrentHashMap<>();

		// Generate server key pair
		KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
		keyGen.initialize(RSA_KEY_SIZE);
		this.keyPair = keyGen.generateKeyPair();

		// Add ourselves to the node directory
		NodeInfo serverInfo = new NodeInfo(
				serverId,
				serverName,
				address.getHostString() + ":" + address.getPort(),
				keyPair.getPublic(),
				NodeType.BOOTSTRAP
		);
		nodeDirectory.addNode(serverInfo);
	}

	@Override
	public void onOpen(WebSocket conn, ClientHandshake handshake) {
		String clientAddress = conn.getRemoteSocketAddress().toString();
		try {
			Logger.Log("New connection from " + clientAddress);
		} catch (Exception e) {
			System.err.println("Error logging: " + e.getMessage());
		}
	}

	@Override
	public void onClose(WebSocket conn, int code, String reason, boolean remote) {
		// Update node status when connection closed
		UUID nodeId = connectionToNodeId.get(conn);
		if (nodeId != null) {
			nodeDirectory.updateNodeStatus(nodeId, false);
			connectionToNodeId.remove(conn);

			try {
				Logger.Log("Connection closed for node " + nodeId);
			} catch (Exception e) {
				System.err.println("Error logging: " + e.getMessage());
			}
		}
	}

	@Override
	public void onMessage(WebSocket conn, String message) {
		try {
			Map<String, Object> data = JSON.parseJson(message);
			String type = (String) data.get("type");

			switch (type) {
				case "HELLO":
					handleHello(conn, data);
					break;
				case "NODE_DISCOVERY":
					handleNodeDiscovery(conn, data);
					break;
				case "KEEP_ALIVE":
					handleKeepAlive(conn, data);
					break;
				default:
					Logger.Log("Unknown message type: " + type);
			}
		} catch (Exception e) {
			System.err.println("Error processing message: " + e.getMessage());
		}
	}

	@Override
	public void onError(WebSocket conn, Exception ex) {
		try {
			Logger.Log("Error on connection " + (conn != null ? conn.getRemoteSocketAddress() : "null") + ": " + ex.getMessage());
		} catch (Exception e) {
			System.err.println("Error logging: " + e.getMessage());
		}
	}

	@Override
	public void onStart() {
		try {
			Logger.Log("Bootstrap server " + serverName + " started on " +
					getAddress().getHostString() + ":" + getAddress().getPort());
		} catch (Exception e) {
			System.err.println("Error logging: " + e.getMessage());
		}
	}

	/**
	 * Handles HELLO messages from nodes
	 */
	private void handleHello(WebSocket conn, Map<String, Object> data) throws Exception {
		UUID nodeId = UUID.fromString((String) data.get("nodeId"));
		String nodeType = (String) data.get("nodeType");
		String publicKeyStr = (String) data.get("publicKey");

		// Extract address from connection
		String remoteAddress = conn.getRemoteSocketAddress().toString();
		if (remoteAddress.startsWith("/")) {
			remoteAddress = remoteAddress.substring(1);
		}

		// Generate a node name if not provided
		String nodeName = (String) data.get("nodeName");
		if (nodeName == null || nodeName.isEmpty()) {
			nodeName = nodeType + "-" + nodeId.toString().substring(0, 8);
		}

		// Parse the public key
		java.security.PublicKey publicKey = MessageEncryption.stringToPublicKey(publicKeyStr);

		// Add node to directory
		NodeInfo nodeInfo = new NodeInfo(
				nodeId,
				nodeName,
				remoteAddress,
				publicKey,
				NodeType.valueOf(nodeType)
		);
		nodeDirectory.addNode(nodeInfo);

		// Associate connection with node ID
		connectionToNodeId.put(conn, nodeId);

		// Send our info back as HELLO response
		Map<String, Object> response = new HashMap<>();
		response.put("type", "HELLO");
		response.put("nodeId", serverId.toString());
		response.put("nodeName", serverName);
		response.put("nodeAddress", getAddress().getHostString() + ":" + getAddress().getPort());
		response.put("nodeType", "BOOTSTRAP");
		response.put("publicKey", MessageEncryption.publicKeyToString(keyPair.getPublic()));

		conn.send(JSON.toJson(response));

		Logger.Log("Registered node: " + nodeName + " (" + nodeId + ")");

		// Automatically send a node list after HELLO
		sendNodeList(conn, DEFAULT_NODE_LIST_SIZE);
	}

	/**
	 * Handles NODE_DISCOVERY requests
	 */
	private void handleNodeDiscovery(WebSocket conn, Map<String, Object> data) throws Exception {
		int count = DEFAULT_NODE_LIST_SIZE;
		if (data.containsKey("count")) {
			count = ((Number) data.get("count")).intValue();
		}

		sendNodeList(conn, count);
	}

	/**
	 * Handles KEEP_ALIVE messages
	 */
	private void handleKeepAlive(WebSocket conn, Map<String, Object> data) throws Exception {
		UUID nodeId = UUID.fromString((String) data.get("nodeId"));
		nodeDirectory.updateNodeLastSeen(nodeId);

		// Optional: Send acknowledgement
		Map<String, Object> response = new HashMap<>();
		response.put("type", "KEEP_ALIVE_ACK");
		conn.send(JSON.toJson(response));
	}

	/**
	 * Sends a list of nodes to a connection
	 */
	private void sendNodeList(WebSocket conn, int count) throws Exception {
		// Get a random subset of nodes
		List<NodeInfo> nodesToSend = nodeDirectory.getRandomNodes(count);

		// Convert to format for message
		List<Map<String, Object>> nodeList = new ArrayList<>();
		for (NodeInfo node : nodesToSend) {
			Map<String, Object> nodeData = new HashMap<>();
			nodeData.put("nodeId", node.getNodeId().toString());
			nodeData.put("nodeName", node.getNodeName());
			nodeData.put("nodeAddress", node.getAddress());
			nodeData.put("nodeType", node.getNodeType().t());
			nodeData.put("publicKey", MessageEncryption.publicKeyToString(node.getPublicKey()));
			nodeData.put("lastSeen", node.getLastSeen());
			nodeList.add(nodeData);
		}

		// Build and send the NODE_LIST message
		Map<String, Object> response = new HashMap<>();
		response.put("type", "NODE_LIST");
		response.put("nodes", nodeList);

		conn.send(JSON.toJson(response));

		Logger.Log("Sent list of " + nodeList.size() + " nodes");
	}

	/**
	 * Stops the bootstrap server
	 */
	@Override
	public void stop() throws InterruptedException {
		nodeDirectory.shutdown();
		super.stop();
	}

	/**
	 * Main method to run a bootstrap server
	 */
	public static void main(String[] args) {
		try {
			Logger.loggerInit();

			int port = args.length > 0 ? Integer.parseInt(args[0]) : 12345;
			String serverName = args.length > 1 ? args[1] : "BootstrapServer-Main";

			BootstrapServer server = new BootstrapServer(
					new InetSocketAddress("localhost", port),
					serverName
			);

			server.start();

			Logger.Log("Press Enter to stop the server...");
			System.in.read();

			server.stop();

		} catch (Exception e) {
			System.err.println("Error starting bootstrap server: " + e.getMessage());
			e.printStackTrace();
		}
	}
}