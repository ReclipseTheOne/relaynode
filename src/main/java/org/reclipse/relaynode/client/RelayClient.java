package org.reclipse.relaynode.client;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.reclipse.relaynode.RelayNode;
import org.reclipse.relaynode.bootstrap.BootstrapServer;
import org.reclipse.relaynode.network.discovery.NodeInfo;
import org.reclipse.relaynode.handlers.JSON;
import org.reclipse.relaynode.handlers.MessageEncryption;
import org.reclipse.relaynode.util.Logger;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.security.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class RelayClient {

	/* ==== CONSTANTS ==== */
	private static final int DEFAULT_CIRCUIT_LENGTH = 3;
	private static final int RSA_KEY_SIZE = 2048;
	private static final int AES_KEY_SIZE = 256;
	private static final int TIMEOUT_SECONDS = 30;

	/* ==== CLIENT IDENTITY ==== */
	private final UUID clientId;
	private PrivateKey privateKey;
	private PublicKey publicKey;

	/* ==== NETWORK COMPONENTS ==== */
	private final Map<UUID, NodeInfo> knownNodes;
	private final Map<String, Circuit> activeCircuits;
	private final Map<String, byte[]> sessionKeys;
	private final Map<String, CompletableFuture<String>> pendingResponses;
	private WebSocketClient bootstrapConnection;

	/* ==== NETWORK MANAGER ==== */
	private NetworkManager networkManager;
	private boolean isIntegratedMode = false;

	/* ==== CIRCUIT CLASS ==== */
	private static class Circuit {
		String circuitId;
		List<UUID> path;
		Map<UUID, byte[]> sessionKeys;
		boolean isEstablished;
		long creationTime;

		public Circuit(String circuitId, List<UUID> path) {
			this.circuitId = circuitId;
			this.path = path;
			this.sessionKeys = new HashMap<>();
			this.isEstablished = false;
			this.creationTime = System.currentTimeMillis();
		}
	}

	/* ==== CONSTRUCTOR ==== */
	public RelayClient() throws NoSuchAlgorithmException {
		this.clientId = UUID.randomUUID();
		this.knownNodes = new ConcurrentHashMap<>();
		this.activeCircuits = new ConcurrentHashMap<>();
		this.sessionKeys = new ConcurrentHashMap<>();
		this.pendingResponses = new ConcurrentHashMap<>();

		// Generate encryption keys
		generateKeyPair();
	}

	/* ==== ENCRYPTION METHODS ==== */
	private void generateKeyPair() throws NoSuchAlgorithmException {
		KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
		keyGen.initialize(RSA_KEY_SIZE);
		KeyPair pair = keyGen.generateKeyPair();
		this.publicKey = pair.getPublic();
		this.privateKey = pair.getPrivate();
		Logger.Log("Client generated RSA key pair");
	}

	private byte[] generateSessionKey() throws NoSuchAlgorithmException {
		KeyGenerator keyGen = KeyGenerator.getInstance("AES");
		keyGen.init(AES_KEY_SIZE);
		SecretKey key = keyGen.generateKey();
		return key.getEncoded();
	}

	private String encryptWithSessionKey(String message, byte[] key) throws Exception {
		SecretKeySpec secretKey = new SecretKeySpec(key, "AES");
		Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
		cipher.init(Cipher.ENCRYPT_MODE, secretKey);
		byte[] iv = cipher.getIV();
		byte[] encryptedBytes = cipher.doFinal(message.getBytes());

		// Combine IV and encrypted data
		byte[] combined = new byte[iv.length + encryptedBytes.length];
		System.arraycopy(iv, 0, combined, 0, iv.length);
		System.arraycopy(encryptedBytes, 0, combined, iv.length, encryptedBytes.length);

		return Base64.getEncoder().encodeToString(combined);
	}

	private String decryptWithSessionKey(String encryptedMessage, byte[] key) throws Exception {
		byte[] combined = Base64.getDecoder().decode(encryptedMessage);

		// Extract IV and encrypted data
		byte[] iv = new byte[16]; // AES IV size is 16 bytes
		byte[] encryptedBytes = new byte[combined.length - 16];
		System.arraycopy(combined, 0, iv, 0, 16);
		System.arraycopy(combined, 16, encryptedBytes, 0, encryptedBytes.length);

		SecretKeySpec secretKey = new SecretKeySpec(key, "AES");
		Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
		cipher.init(Cipher.DECRYPT_MODE, secretKey, new javax.crypto.spec.IvParameterSpec(iv));
		byte[] decryptedBytes = cipher.doFinal(encryptedBytes);

		return new String(decryptedBytes);
	}

	/* ==== INTEGRATED NETWORK MANAGEMENT ==== */

	/**
	 * Initializes and starts the integrated network components
	 */
	public boolean initializeIntegratedNetwork(String host, int bootstrapPort, int relayNodePort) {
		try {
			// Initialize logger if not already initialized
			try {
				Logger.loggerInit();
			} catch (Exception e) {
				// Logger might already be initialized, continue
			}

			// Create network manager
			networkManager = new NetworkManager(host, bootstrapPort, relayNodePort);

			// Start bootstrap server
			CompletableFuture<BootstrapServer> bootstrapFuture = networkManager.startBootstrapServer();

			// Start relay node (as RELAY type)
			CompletableFuture<RelayNode> relayFuture = networkManager.startRelayNode(RelayNode.NodeType.RELAY);

			// Wait for both to start
			bootstrapFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			relayFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

			// Connect to our local bootstrap node
			boolean connected = connectToBootstrap(networkManager.getBootstrapAddress());

			if (connected) {
				isIntegratedMode = true;
				Logger.Log("Integrated network initialized successfully");
			} else {
				shutdownIntegratedNetwork();
				Logger.Log("Failed to connect to local bootstrap server");
			}

			return connected;
		} catch (Exception e) {
			Logger.Log("Failed to initialize integrated network: " + e.getMessage());
			return false;
		}
	}

	/**
	 * Shuts down the integrated network components
	 */
	public void shutdownIntegratedNetwork() {
		if (networkManager != null) {
			disconnect(); // Disconnect client first
			networkManager.shutdown();
			networkManager = null;
			isIntegratedMode = false;
			Logger.Log("Integrated network shutdown complete");
		}
	}

	/**
	 * Checks if running in integrated mode
	 */
	public boolean isIntegratedMode() {
		return isIntegratedMode;
	}

	/* ==== CONNECTION MANAGEMENT ==== */

	/**
	 * Connects to a bootstrap node to discover other nodes in the network
	 */
	public boolean connectToBootstrap(String bootstrapAddress) {
		try {
			URI uri = new URI("ws://" + bootstrapAddress);
			CountDownLatch connectionLatch = new CountDownLatch(1);

			bootstrapConnection = new WebSocketClient(uri) {
				@Override
				public void onOpen(ServerHandshake handshakedata) {
					Logger.Log("Connected to bootstrap node: " + bootstrapAddress);

					// Send HELLO message to bootstrap
					Map<String, Object> hello = new HashMap<>();
					hello.put("type", "HELLO");
					hello.put("nodeId", clientId.toString());
					hello.put("nodeType", "CLIENT");
					hello.put("publicKey", MessageEncryption.publicKeyToString(publicKey));

					this.send(JSON.toJson(hello));
					connectionLatch.countDown();
				}

				@Override
				public void onMessage(String message) {
					try {
						Map<String, Object> data = JSON.parseJson(message);
						String type = (String) data.get("type");

						switch (type) {
							case "HELLO":
								handleHelloResponse(data);
								break;
							case "NODE_LIST":
								handleNodeList(data);
								break;
							case "CIRCUIT_ESTABLISHED":
								handleCircuitEstablished(data);
								break;
							case "DATA":
								handleDataResponse(data);
								break;
							default:
								Logger.Log("Unknown message type from bootstrap: " + type);
						}
					} catch (Exception e) {
						Logger.Log("Error processing bootstrap message: " + e.getMessage());
					}
				}

				@Override
				public void onClose(int code, String reason, boolean remote) {
					Logger.Log("Connection to bootstrap closed: " + reason);
				}

				@Override
				public void onError(Exception ex) {
					Logger.Log("Bootstrap connection error: " + ex.getMessage());
					connectionLatch.countDown(); // Ensure latch is released on error
				}
			};

			bootstrapConnection.connect();
			return connectionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

		} catch (Exception e) {
			Logger.Log("Failed to connect to bootstrap: " + e.getMessage());
			return false;
		}
	}

	/**
	 * Disconnects from the bootstrap node
	 */
	public void disconnect() {
		if (bootstrapConnection != null && bootstrapConnection.isOpen()) {
			bootstrapConnection.close();
		}
	}

	/* ==== MESSAGE HANDLERS ==== */

	private void handleHelloResponse(Map<String, Object> data) {
		UUID nodeId = UUID.fromString((String) data.get("nodeId"));
		String nodeName = (String) data.get("nodeName");
		String nodeAddress = (String) data.get("nodeAddress");
		String publicKeyStr = (String) data.get("publicKey");


		try {
			PublicKey nodePublicKey = MessageEncryption.stringToPublicKey(publicKeyStr);
			NodeInfo nodeInfo = new NodeInfo(nodeId, nodeName, nodeAddress, nodePublicKey);
			knownNodes.put(nodeId, nodeInfo);

			Logger.Log("Added node to known nodes: " + nodeName + " (" + nodeId + ")");
		} catch (Exception e) {
			Logger.Log("Error handling hello response: " + e.getMessage());
		}
	}

	private void handleNodeList(Map<String, Object> data) {
		List<Map<String, Object>> nodes = (List<Map<String, Object>>) data.get("nodes");

		for (Map<String, Object> node : nodes) {
			try {
				UUID nodeId = UUID.fromString((String) node.get("nodeId"));
				String nodeName = (String) node.get("nodeName");
				String nodeAddress = (String) node.get("nodeAddress");
				String publicKeyStr = (String) node.get("publicKey");

				PublicKey nodePublicKey = MessageEncryption.stringToPublicKey(publicKeyStr);
				NodeInfo nodeInfo = new NodeInfo(nodeId, nodeName, nodeAddress, nodePublicKey);
				knownNodes.put(nodeId, nodeInfo);
			} catch (Exception e) {
				Logger.Log("Error processing node info: " + e.getMessage());
			}
		}

		Logger.Log("Received list of " + nodes.size() + " nodes from bootstrap");
	}

	private void handleCircuitEstablished(Map<String, Object> data) {
		String circuitId = (String) data.get("circuitId");

		if (activeCircuits.containsKey(circuitId)) {
			activeCircuits.get(circuitId).isEstablished = true;
			Logger.Log("Circuit established: " + circuitId);

			// Notify any waiting threads
			CompletableFuture<String> future = pendingResponses.get(circuitId);
			if (future != null) {
				future.complete(circuitId);
				pendingResponses.remove(circuitId);
			}
		}
	}

	private void handleDataResponse(Map<String, Object> data) {
		String circuitId = (String) data.get("circuitId");
		String encryptedPayload = (String) data.get("payload");

		try {
			// Decrypt the response using our session key for the first node
			Circuit circuit = activeCircuits.get(circuitId);
			if (circuit != null && circuit.isEstablished) {
				// Get first node ID
				UUID firstNodeId = circuit.path.get(0);
				byte[] sessionKey = circuit.sessionKeys.get(firstNodeId);

				String decryptedMessage = decryptWithSessionKey(encryptedPayload, sessionKey);

				// Further decrypt each layer
				for (int i = 1; i < circuit.path.size(); i++) {
					UUID nodeId = circuit.path.get(i);
					sessionKey = circuit.sessionKeys.get(nodeId);
					decryptedMessage = decryptWithSessionKey(decryptedMessage, sessionKey);
				}

				Logger.Log("Received response through circuit: " + decryptedMessage);

				// Notify any waiting threads
				CompletableFuture<String> future = pendingResponses.get(circuitId);
				if (future != null) {
					future.complete(decryptedMessage);
					pendingResponses.remove(circuitId);
				}
			}
		} catch (Exception e) {
			Logger.Log("Error handling data response: " + e.getMessage());
		}
	}

	/* ==== CIRCUIT BUILDING ==== */

	/**
	 * Builds a circuit through the network for anonymous communication
	 */
	public String buildCircuit() throws Exception {
		return buildCircuit(DEFAULT_CIRCUIT_LENGTH);
	}

	/**
	 * Builds a circuit with specified length through the network
	 */
	public String buildCircuit(int length) throws Exception {
		if (knownNodes.size() < length) {
			throw new IllegalStateException("Not enough known nodes to build a circuit of length " + length);
		}

		// Select random nodes for the path
		List<UUID> allNodeIds = new ArrayList<>(knownNodes.keySet());
		Collections.shuffle(allNodeIds);

		List<UUID> path = allNodeIds.subList(0, length);
		String circuitId = UUID.randomUUID().toString();

		Circuit circuit = new Circuit(circuitId, path);
		activeCircuits.put(circuitId, circuit);

		// Create a CompletableFuture to wait for circuit establishment
		CompletableFuture<String> circuitFuture = new CompletableFuture<>();
		pendingResponses.put(circuitId, circuitFuture);

		// Generate session keys for each node in the path
		for (UUID nodeId : path) {
			byte[] sessionKey = generateSessionKey();
			circuit.sessionKeys.put(nodeId, sessionKey);
		}

		// Build the circuit layers (like an onion, from the inside out)
		String encryptedLayers = buildCircuitLayers(circuitId, path, circuit.sessionKeys);

		// Send the circuit build request to the first node
		UUID entryNode = path.get(0);
		NodeInfo entryNodeInfo = knownNodes.get(entryNode);

		Map<String, Object> circuitBuildMessage = new HashMap<>();
		circuitBuildMessage.put("type", "CIRCUIT_BUILD");
		circuitBuildMessage.put("senderId", clientId.toString());
		circuitBuildMessage.put("circuitId", circuitId);
		circuitBuildMessage.put("encryptedLayer", encryptedLayers);

		bootstrapConnection.send(JSON.toJson(circuitBuildMessage));

		// Wait for circuit establishment confirmation
		String result = circuitFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		return result;
	}

	/**
	 * Builds the nested encrypted layers for circuit establishment
	 */
	private String buildCircuitLayers(String circuitId, List<UUID> path, Map<UUID, byte[]> sessionKeys) throws Exception {
		// Start with the exit node layer
		UUID exitNodeId = path.get(path.size() - 1);
		NodeInfo exitNodeInfo = knownNodes.get(exitNodeId);
		byte[] exitSessionKey = sessionKeys.get(exitNodeId);

		Map<String, Object> exitLayer = new HashMap<>();
		exitLayer.put("circuitId", circuitId);
		exitLayer.put("sessionKey", Base64.getEncoder().encodeToString(exitSessionKey));
		exitLayer.put("isExit", true);

		String currentLayer = JSON.toJson(exitLayer);
		String encryptedLayer = MessageEncryption.encrypt(currentLayer, exitNodeInfo.publicKey);

		// Build each layer from back to front (exit to entry)
		for (int i = path.size() - 2; i >= 0; i--) {
			UUID currentNodeId = path.get(i);
			UUID nextNodeId = path.get(i + 1);
			NodeInfo currentNodeInfo = knownNodes.get(currentNodeId);
			byte[] currentSessionKey = sessionKeys.get(currentNodeId);

			Map<String, Object> layerData = new HashMap<>();
			layerData.put("circuitId", circuitId);
			layerData.put("sessionKey", Base64.getEncoder().encodeToString(currentSessionKey));
			layerData.put("nextHop", nextNodeId.toString());
			layerData.put("remainingLayers", encryptedLayer);

			currentLayer = JSON.toJson(layerData);
			encryptedLayer = MessageEncryption.encrypt(currentLayer, currentNodeInfo.publicKey);
		}

		return encryptedLayer;
	}

	/**
	 * Destroys an active circuit
	 */
	public void destroyCircuit(String circuitId) {
		if (!activeCircuits.containsKey(circuitId)) {
			Logger.Log("Circuit " + circuitId + " not found");
			return;
		}

		try {
			Circuit circuit = activeCircuits.get(circuitId);

			// Create circuit destroy message
			Map<String, Object> destroyMessage = new HashMap<>();
			destroyMessage.put("type", "CIRCUIT_DESTROY");
			destroyMessage.put("circuitId", circuitId);

			// Send to the first node
			bootstrapConnection.send(JSON.toJson(destroyMessage));

			// Remove from our active circuits
			activeCircuits.remove(circuitId);
			Logger.Log("Sent destroy request for circuit: " + circuitId);
		} catch (Exception e) {
			Logger.Log("Error destroying circuit: " + e.getMessage());
		}
	}

	/* ==== SENDING MESSAGES ==== */

	/**
	 * Sends an anonymous message through an established circuit
	 */
	public String sendMessage(String circuitId, String message) throws Exception {
		if (!activeCircuits.containsKey(circuitId)) {
			throw new IllegalArgumentException("Circuit " + circuitId + " not found or not established");
		}

		Circuit circuit = activeCircuits.get(circuitId);
		if (!circuit.isEstablished) {
			throw new IllegalStateException("Circuit " + circuitId + " is not yet established");
		}

		// Create a future to wait for the response
		CompletableFuture<String> responseFuture = new CompletableFuture<>();
		pendingResponses.put(circuitId, responseFuture);

		// Encrypt the message in layers (from exit to entry)
		String encryptedMessage = message;

		// Encrypt in reverse order (exit node first, entry node last)
		for (int i = circuit.path.size() - 1; i >= 0; i--) {
			UUID nodeId = circuit.path.get(i);
			byte[] sessionKey = circuit.sessionKeys.get(nodeId);
			encryptedMessage = encryptWithSessionKey(encryptedMessage, sessionKey);
		}

		// Prepare the message wrapper
		Map<String, Object> dataMessage = new HashMap<>();
		dataMessage.put("type", "DATA");
		dataMessage.put("circuitId", circuitId);
		dataMessage.put("payload", encryptedMessage);

		// Send the message
		bootstrapConnection.send(JSON.toJson(dataMessage));
		Logger.Log("Sent message through circuit " + circuitId);

		// Wait for a response
		return responseFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	/* ==== UTILITY METHODS ==== */

	/**
	 * Returns a list of all known nodes
	 */
	public List<NodeInfo> getKnownNodes() {
		return new ArrayList<>(knownNodes.values());
	}

	/**
	 * Returns a list of all active circuits
	 */
	public List<Circuit> getActiveCircuits() {
		return new ArrayList<>(activeCircuits.values());
	}

	/* ==== MAIN METHOD FOR TESTING ==== */
	public static void main(String[] args) {
		try {
			Logger.loggerInit();

			// Parse command line arguments
			boolean integratedMode = args.length > 0 && args[0].equalsIgnoreCase("--integrated");

			RelayClient client = new RelayClient();

			if (integratedMode) {
				// Start in integrated mode (with built-in bootstrap server and relay node)
				Logger.Log("Starting in integrated mode...");

				// Use port 12345 for bootstrap, 12346 for relay node
				if (client.initializeIntegratedNetwork("localhost", 12345, 12346)) {
					Logger.Log("Successfully started integrated network");

					// Wait a bit for node registration
					Thread.sleep(2000);

					// Build a circuit
					String circuitId = client.buildCircuit();
					Logger.Log("Built circuit: " + circuitId);

					// Send a test message
					String response = client.sendMessage(circuitId, "Hello, anonymous world from integrated mode!");
					Logger.Log("Received response: " + response);

					// Destroy the circuit
					client.destroyCircuit(circuitId);

					Logger.Log("Press Enter to shutdown the integrated network...");
					System.in.read();

					// Shutdown everything
					client.shutdownIntegratedNetwork();
				} else {
					Logger.Log("Failed to start integrated network");
				}
			} else {
				// Connect to an external bootstrap node
				String bootstrapAddress = args.length > 0 ? args[0] : "localhost:12345";
				if (client.connectToBootstrap(bootstrapAddress)) {
					Logger.Log("Successfully connected to bootstrap node");

					// Wait a bit to receive the node list
					Thread.sleep(2000);

					// Build a circuit
					String circuitId = client.buildCircuit();
					Logger.Log("Built circuit: " + circuitId);

					// Send a test message
					String response = client.sendMessage(circuitId, "Hello, anonymous world!");
					Logger.Log("Received response: " + response);

					// Destroy the circuit
					client.destroyCircuit(circuitId);

					// Disconnect
					client.disconnect();
				} else {
					Logger.Log("Failed to connect to bootstrap node");
				}
			}
		} catch (Exception e) {
			System.err.println("Error in client: " + e.getMessage());
			e.printStackTrace();
		}
	}
}