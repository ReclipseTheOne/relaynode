package org.relay.relaynode;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.WebSocket;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public class RelayNode extends WebSocketServer {

    private final InetSocketAddress nodeAddress;
    private final UUID nodeUUID;
    private String nodeName;
    private Map<UUID, String> peers;  // Maps nodeUUID to the socket address on the node

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public RelayNode(InetSocketAddress address) {
        super(address);
        this.nodeAddress = address;
        this.nodeUUID = UUID.randomUUID();
        this.peers = new HashMap<>();
        this.nodeName = String.format("NODE-%s", nodeUUID.toString());
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
        Map<String, Object> data = parseJson(message);

        // Message handling
        if (data != null && "message".equals(data.get("type"))) {
            @SuppressWarnings("unchecked")
            List<String> connectionQueue = (List<String>) data.get("connectionQueue");
            String relayMessage = (String) data.get("message");
            forwardMessage(connectionQueue, relayMessage);
        }

        // Node Discovery Handling
        if (data != null && "node".equals(data.get("type")) && !peers.containsKey(UUID.fromString((String) data.get("nodeUUID")))) {
            @SuppressWarnings("unchecked")
            List<String> connectionQueue = (List<String>) data.get("connectionQueue");
            String relayMessage = (String) data.get("message");
            peers.put(UUID.fromString((String) data.get("nodeUUID")), (String) data.get("socketAddress"));
        }
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
    private void forwardMessage(List<String> connectionQueue, String message) {
        if (connectionQueue != null && !connectionQueue.isEmpty()) {

            // The 1 by 1 step. Removes the "next" relay from the chain before continuing
            String nextRelayId = connectionQueue.remove(0);
            String nextRelayUri = peers.get(nextRelayId);

            if (nextRelayUri != null) {
                try {
                    WebSocketClient client = new WebSocketClient(new URI(nextRelayUri)) {
                        @Override
                        public void onOpen(ServerHandshake handshakedata) {
                            Map<String, Object> data = new HashMap<>();
                            data.put("type", "message");
                            data.put("connectionQueue", connectionQueue);
                            data.put("nodeUUID", getNodeUUID());
                            data.put("message", message);
                            send(toJson(data));
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
        } else {
            System.out.println("Message delivered: " + message);
        }
    }

    // Map -> JSON
    private String toJson(Map<String, Object> data) {
        try {
            // Map -> JSON
            return objectMapper.writeValueAsString(data);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    // JSON -> Map
    private Map<String, Object> parseJson(String message) {
        try {
            // JSON -> Map
            return objectMapper.readValue(message, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            e.printStackTrace();
            return new HashMap<>();
        }
    }

    public static void main(String[] args) {
        RelayNode node = new RelayNode(new InetSocketAddress("localhost", 12345));
        node.addPeer("relay2", "ws://localhost:12346");
        node.addPeer("relay3", "ws://localhost:12347");
        node.start();
    }
}