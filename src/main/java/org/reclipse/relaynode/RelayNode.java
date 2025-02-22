package org.reclipse.relaynode;

import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.server.WebSocketServer;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.reclipse.relaynode.network.Circuit;
import org.reclipse.relaynode.network.discovery.NodeType;
import org.reclipse.relaynode.handlers.JSON;
import org.reclipse.relaynode.handlers.MessageEncryption;
import org.reclipse.relaynode.util.Logger;

public class RelayNode extends WebSocketServer {

    /* ==== CONSTANTS ==== */
    private static final int DEFAULT_CIRCUIT_LENGTH = 3;
    private static final long CIRCUIT_TIMEOUT = 1000 * 60 * 15; // 15 minutes
    private static final int RSA_KEY_SIZE = 2048;
    private static final int AES_KEY_SIZE = 256;

    /* ==== NODE IDENTITY ==== */
    private final UUID nodeId;
    private String nodeName;
    private final InetSocketAddress nodeAddress;
    private NodeType nodeType;

    /* ==== CRYPTOGRAPHIC KEYS ==== */
    private PrivateKey privateKey;
    private PublicKey publicKey;
    private final Map<UUID, PublicKey> peerPublicKeys;

    /* ==== NETWORK COMPONENTS ==== */
    private final Map<UUID, String> peers;  // Maps nodeUUID to the socket address
    private final Map<UUID, WebSocket> connections; // Active connections
    private final Map<String, Circuit> circuits; // Active circuits
    private final Map<String, byte[]> sessionKeys; // Circuit session keys

    /* ==== CONSTRUCTOR ==== */
    public RelayNode(InetSocketAddress address, NodeType type) throws IOException, NoSuchAlgorithmException {
        super(address);
        this.nodeAddress = address;
        this.nodeId = UUID.randomUUID();
        this.nodeName = String.format("%s-%s", type.name(), nodeId.toString().substring(0, 8));
        this.nodeType = type;

        // Initialize data structures
        this.peers = new ConcurrentHashMap<>();
        this.connections = new ConcurrentHashMap<>();
        this.circuits = new ConcurrentHashMap<>();
        this.sessionKeys = new ConcurrentHashMap<>();
        this.peerPublicKeys = new ConcurrentHashMap<>();

        // Generate encryption keys
        generateKeyPair();

        Logger.Log("Initialized " + nodeName + " at " + address.getHostString() + ":" + address.getPort());
    }

    /* ==== KEY MANAGEMENT ==== */
    private void generateKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(RSA_KEY_SIZE);
        KeyPair pair = keyGen.generateKeyPair();
        this.publicKey = pair.getPublic();
        this.privateKey = pair.getPrivate();
        Logger.Log("Generated RSA key pair");
    }

    private byte[] generateSessionKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(AES_KEY_SIZE);
        SecretKey key = keyGen.generateKey();
        return key.getEncoded();
    }

    /* ==== GETTERS & SETTERS ==== */
    public PublicKey getPublicKey() {
        return publicKey;
    }

    public void setNodeName(String name) {
        this.nodeName = name;
    }

    public String getNodeName() {
        return nodeName;
    }

    public UUID getNodeId() {
        return nodeId;
    }

    public InetSocketAddress getNodeAddress() {
        return nodeAddress;
    }

    public NodeType getNodeType() {
        return nodeType;
    }

    public void setNodeType(NodeType type) {
        this.nodeType = type;
    }

    /* ==== PEER MANAGEMENT ==== */
    public void addPeer(UUID peerUUID, String uri, PublicKey peerKey) {
        peers.put(peerUUID, uri);
        peerPublicKeys.put(peerUUID, peerKey);
        Logger.Log("Added peer: " + peerUUID + " at " + uri);
    }

    public void removePeer(UUID peerUUID) {
        peers.remove(peerUUID);
        peerPublicKeys.remove(peerUUID);
        Logger.Log("Removed peer: " + peerUUID);
    }

    /* ==== CIRCUIT MANAGEMENT ==== */
    public String createCircuit(UUID previousNode, UUID nextNode) throws NoSuchAlgorithmException {
        String circuitId = UUID.randomUUID().toString();
        byte[] sessionKey = generateSessionKey();
        Circuit circuit = new Circuit(circuitId, previousNode, nextNode, sessionKey);
        circuits.put(circuitId, circuit);
        return circuitId;
    }

    public void destroyCircuit(String circuitId) {
        circuits.remove(circuitId);
        sessionKeys.remove(circuitId);
    }

    /* ==== MESSAGE ENCRYPTION ==== */
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

    /* ==== MESSAGE PROCESSING ==== */
    private void processMessage(WebSocket conn, Map<String, Object> message) {
        try {
            String type = (String) message.get("type");

            switch (type) {
                case "HELLO":
                    handleHello(conn, message);
                    break;
                case "CIRCUIT_BUILD":
                    handleCircuitBuild(conn, message);
                    break;
                case "DATA":
                    handleData(conn, message);
                    break;
                case "CIRCUIT_DESTROY":
                    handleCircuitDestroy(conn, message);
                    break;
                default:
                    Logger.Log("Unknown message type: " + type);
            }
        } catch (Exception e) {
            Logger.Log("Error processing message: " + e.getMessage());
        }
    }

    private void handleHello(WebSocket conn, Map<String, Object> message) {
        // Process node introduction
        UUID peerId = UUID.fromString((String) message.get("nodeId"));
        String peerAddress = (String) message.get("nodeAddress");
        String peerKeyString = (String) message.get("publicKey");

        try {
            PublicKey peerKey = MessageEncryption.stringToPublicKey(peerKeyString);
            addPeer(peerId, peerAddress, peerKey);

            // Send our identity back
            Map<String, Object> response = new HashMap<>();
            response.put("type", "HELLO");
            response.put("nodeId", nodeId.toString());
            response.put("nodeName", nodeName);
            response.put("nodeAddress", nodeAddress.getHostString() + ":" + nodeAddress.getPort());
            response.put("publicKey", MessageEncryption.publicKeyToString(publicKey));

            conn.send(JSON.toJson(response));
        } catch (Exception e) {
            Logger.Log("Error handling HELLO: " + e.getMessage());
        }
    }

    private void handleCircuitBuild(WebSocket conn, Map<String, Object> message) {
        // Process circuit creation requests
        try {
            String encryptedLayerData = (String) message.get("encryptedLayer");
            String decryptedLayer = MessageEncryption.decrypt(encryptedLayerData, privateKey);
            Map<String, Object> layerData = JSON.parseJson(decryptedLayer);

            String circuitId = (String) layerData.get("circuitId");
            String sessionKeyBase64 = (String) layerData.get("sessionKey");
            byte[] sessionKey = Base64.getDecoder().decode(sessionKeyBase64);

            // Store the session key for this circuit
            sessionKeys.put(circuitId, sessionKey);

            if (layerData.containsKey("isExit") && (boolean) layerData.get("isExit")) {
                // This is the exit node, no need to forward
                Logger.Log("Established as EXIT for circuit: " + circuitId);

                // Create circuit with previous node only
                UUID previousNodeId = UUID.fromString((String) message.get("senderId"));
                Circuit circuit = new Circuit(circuitId, previousNodeId, null, sessionKey);
                circuits.put(circuitId, circuit);

                // Send success response back
                Map<String, Object> response = new HashMap<>();
                response.put("type", "CIRCUIT_ESTABLISHED");
                response.put("circuitId", circuitId);

                // Encrypt with session key and send back to previous
                String encryptedResponse = encryptWithSessionKey(JSON.toJson(response), sessionKey);

                Map<String, Object> wrapper = new HashMap<>();
                wrapper.put("type", "DATA");
                wrapper.put("circuitId", circuitId);
                wrapper.put("payload", encryptedResponse);

                conn.send(JSON.toJson(wrapper));
            } else {
                // This is a relay node, forward to next hop
                String nextHopId = (String) layerData.get("nextHop");
                String remainingLayers = (String) layerData.get("remainingLayers");

                // Create circuit with both previous and next
                UUID previousNodeId = UUID.fromString((String) message.get("senderId"));
                UUID nextNodeId = UUID.fromString(nextHopId);
                Circuit circuit = new Circuit(circuitId, previousNodeId, nextNodeId, sessionKey);
                circuits.put(circuitId, circuit);

                // Forward circuit build request
                Map<String, Object> forwardMessage = new HashMap<>();
                forwardMessage.put("type", "CIRCUIT_BUILD");
                forwardMessage.put("senderId", nodeId.toString());
                forwardMessage.put("circuitId", circuitId);
                forwardMessage.put("encryptedLayer", remainingLayers);

                forwardToNode(nextNodeId, JSON.toJson(forwardMessage));
            }
        } catch (Exception e) {
            Logger.Log("Error handling CIRCUIT_BUILD: " + e.getMessage());
        }
    }

    private void handleData(WebSocket conn, Map<String, Object> message) {
        try {
            String circuitId = (String) message.get("circuitId");
            String encryptedPayload = (String) message.get("payload");

            if (!circuits.containsKey(circuitId) || !sessionKeys.containsKey(circuitId)) {
                Logger.Log("Unknown circuit: " + circuitId);
                return;
            }

            Circuit circuit = circuits.get(circuitId);
            byte[] sessionKey = sessionKeys.get(circuitId);

            // Decrypt this layer
            String decryptedPayload = decryptWithSessionKey(encryptedPayload, sessionKey);

            if (nodeType == NodeType.EXIT || circuit.getNextNode() == null) {
                // We are the exit node, process the actual message
                Logger.Log("Received message at EXIT: " + decryptedPayload);
                // In a real implementation, you would deliver to the recipient here
            } else {
                // We are a relay, forward to next node
                Map<String, Object> forwardMessage = new HashMap<>();
                forwardMessage.put("type", "DATA");
                forwardMessage.put("circuitId", circuitId);
                forwardMessage.put("payload", decryptedPayload);

                forwardToNode(circuit.getNextNode(), JSON.toJson(forwardMessage));
            }
        } catch (Exception e) {
            Logger.Log("Error handling DATA: " + e.getMessage());
        }
    }

    private void handleCircuitDestroy(WebSocket conn, Map<String, Object> message) {
        String circuitId = (String) message.get("circuitId");

        if (circuits.containsKey(circuitId)) {
            Circuit circuit = circuits.get(circuitId);

            // Forward destruction request if we're not the exit
            if (circuit.getNextNode() != null) {
                Map<String, Object> forwardMessage = new HashMap<>();
                forwardMessage.put("type", "CIRCUIT_DESTROY");
                forwardMessage.put("circuitId", circuitId);

                forwardToNode(circuit.getNextNode(), JSON.toJson(forwardMessage));
            }

            // Clean up our circuit
            destroyCircuit(circuitId);
            Logger.Log("Destroyed circuit: " + circuitId);
        }
    }

    /* ==== CONNECTION MANAGEMENT ==== */
    private void forwardToNode(UUID nodeId, String message) {
        String nodeUri = peers.get(nodeId);

        if (nodeUri == null) {
            Logger.Log("Unknown node: " + nodeId);
            return;
        }

        try {
            WebSocketClient client = new WebSocketClient(new URI(nodeUri)) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    this.send(message);
                }

                @Override
                public void onMessage(String message) {
                    // Handle response if needed
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Logger.Log("Connection closed to: " + nodeUri);
                }

                @Override
                public void onError(Exception ex) {
                    Logger.Log("Error forwarding to " + nodeUri + ": " + ex.getMessage());
                }
            };
            client.connect();
        } catch (URISyntaxException e) {
            Logger.Log("Invalid URI: " + nodeUri);
        }
    }

    /* ==== WEBSOCKET SERVER METHODS ==== */
    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String address = conn.getRemoteSocketAddress().toString();
        Logger.Log("New connection from: " + address);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String address = conn.getRemoteSocketAddress().toString();
        Logger.Log("Connection closed from: " + address + " with code " + code);

        // Clean up any circuits associated with this connection
        for (Map.Entry<String, Circuit> entry : new ArrayList<>(circuits.entrySet())) {
            // TODO: Implement proper circuit-to-connection mapping
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            Map<String, Object> data = JSON.parseJson(message);
            processMessage(conn, data);
        } catch (Exception e) {
            Logger.Log("Error parsing message: " + e.getMessage());
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        Logger.Log("Error: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        Logger.Log("RelayNode " + nodeName + " started on " +
                getAddress().getHostString() + ":" + getAddress().getPort());

        // Start maintenance tasks like circuit cleanup
        startMaintenanceTasks();
    }

    /* ==== MAINTENANCE ==== */
    private void startMaintenanceTasks() {
        Timer timer = new Timer(true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                cleanupExpiredCircuits();
            }
        }, 60000, 60000); // Run every minute
    }

    private void cleanupExpiredCircuits() {
        int count = 0;
        for (Map.Entry<String, Circuit> entry : new HashMap<>(circuits).entrySet()) {
            if (entry.getValue().isExpired()) {
                destroyCircuit(entry.getKey());
                count++;
            }
        }
        if (count > 0) {
            Logger.Log("Cleaned up " + count + " expired circuits");
        }
    }

    /* ==== MAIN METHOD ==== */
    public static void main(String[] args) {
        try {
            Logger.loggerInit();

            // Default port and node type
            int port = args.length > 0 ? Integer.parseInt(args[0]) : 12345;
            NodeType type = args.length > 1 ? NodeType.valueOf(args[1]) : NodeType.RELAY;

            RelayNode node = new RelayNode(new InetSocketAddress("localhost", port), type);
            node.start();
        } catch (Exception e) {
            System.err.println("Failed to start node: " + e.getMessage());
            e.printStackTrace();
        }
    }
}