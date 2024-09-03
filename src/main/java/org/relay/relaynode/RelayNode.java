package org.relay.relaynode;

import jdk.jshell.execution.Util;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.WebSocket;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.spec.InvalidKeySpecException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import java.security.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.relay.relaynode.handlers.JSON;
import org.relay.relaynode.handlers.EncryptionKeys;

import org.relay.relaynode.handlers.MessageEncryption;
import org.relay.relaynode.util.Logger;

public class RelayNode extends WebSocketServer {

    // ENCRYPTION //

    private final HashMap<UUID, PublicKey> cachedPubKeys = new HashMap<>();

    private static PrivateKey privateKey;
    private static PublicKey publicKey;

    public static PublicKey getPublicKey() {
        return publicKey;
    }

    private void generateKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048); // 2048-bit key size
        KeyPair pair = keyGen.generateKeyPair();
        this.publicKey = pair.getPublic();
        this.privateKey = pair.getPrivate();
    }

    // NETWORK HANDLING //

    private final InetSocketAddress nodeAddress;
    private final UUID nodeUUID;
    private String nodeName;
    private final Map<UUID, String> peers;  // Maps nodeUUID to the socket address on the node

    public RelayNode(InetSocketAddress address) throws IOException {
        super(address);
        this.nodeAddress = address;
        this.nodeUUID = UUID.randomUUID();
        this.peers = new HashMap<>();
        this.nodeName = String.format("NODE-%s", nodeUUID);
    }

    public void addPeer(UUID peerUUID, String uri) {
        peers.put(peerUUID, uri);
    }
    public void setNodeName(String name) {
        nodeName = name;
    }

    public String getNodeName() {
        return nodeName;
    }
    public String getNodeUUID() {
        return nodeUUID.toString();
    }
    public InetSocketAddress getNodeAddress() {
        return nodeAddress;
    }
    public String getURI(InetSocketAddress address) { return address.getHostName(); }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake clientHandshake) {
        System.out.println("Connection opened: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("Connection closed: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        Map<String, Object> data = JSON.parseJson(message);
        List<String> connectionQueue = (List<String>) data.get("connectionQueue");

        // Message Handling
        if ("message".equals(data.get("type")) && connectionQueue.isEmpty()) {
            try {
                Logger.Log(String.format("Message received: %s", MessageEncryption.decrypt((String) data.get("message"), privateKey)));
            } catch (Exception e) { Logger.Log(e.getMessage()); }
        }

        // Node Handling
        if ("node".equals(data.get("type")) && connectionQueue.isEmpty()) {
            if (peers.containsKey(UUID.fromString((String) data.get("nodeUUID")))) {
                Logger.Log(String.format("Peer@%s already present", data.get("socketAddress")));
            } else {
                Logger.Log(String.format("Peer@%s added with UUID: %s", data.get("socketAddress"), data.get("nodeUUID")));
                peers.put(UUID.fromString((String) data.get("nodeUUID")), (String) data.get("socketAddress"));

                try {
                    cachedPubKeys.put(UUID.fromString((String) data.get("nodeUUID")), EncryptionKeys.stringToPublicKey((String) data.get("pubkey")));
                } catch (Exception e) {
                    Logger.Log(e.getMessage());
                }
            }
        }

        forwardMessage(data);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("Error: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        System.out.println("RelayNode " + nodeUUID + " started on " + getAddress().getHostName() + ":" + getPort());
    }

    // Checks if there are more Relays to go through before reaching destination and goes 1 by 1.
    private void forwardMessage(Map<String, Object> data) {
        List<String> connectionQueue = (List<String>) data.get("connectionQueue");

        // The 1 by 1 step. Removes the "next" relay from the chain before continuing
        String nextRelayId = connectionQueue.remove(0);
        String nextRelayUri = peers.get(nextRelayId);

        if (nextRelayUri != null) {
            try {
                WebSocketClient client = new WebSocketClient(new URI(nextRelayUri)) {
                    @Override
                    public void onOpen(ServerHandshake handshakedata) {
                        Map<String, Object> data = new HashMap<>();
                        send(JSON.toJson(data));
                    }

                    @Override
                    public void onMessage(String message) {
                        // Handle responses from the next relay if needed
                    }

                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        System.out.println("Connection closed to: " + nextRelayUri);
                    }

                    @Override
                    public void onError(Exception ex) {
                        System.err.println("Error forwarding to " + nextRelayUri + ": " + ex.getMessage());
                    }
                };
                client.connect();
            } catch (URISyntaxException e) {
                e.printStackTrace();
            }
        } else {
            System.err.println("Relay " + nextRelayId + " not found in peers.");
        }
    }

    public static void main(String[] args) throws IOException {
        Logger.loggerInit();

        RelayNode node = new RelayNode(new InetSocketAddress("localhost", 12345));
        node.start();
    }
}