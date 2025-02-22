package org.reclipse.relaynode.client;

import org.reclipse.relaynode.RelayNode;
import org.reclipse.relaynode.bootstrap.BootstrapServer;
import org.reclipse.relaynode.util.Logger;

import java.net.InetSocketAddress;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages the network components (Bootstrap server, Relay nodes, and Client)
 * for an all-in-one network utility
 */
public class NetworkManager {
	private BootstrapServer bootstrapServer;
	private RelayNode relayNode;
	private final ExecutorService executor;
	private final String host;
	private final int bootstrapPort;
	private final int relayNodePort;

	/**
	 * Creates a new NetworkManager with default ports
	 */
	public NetworkManager(String host) {
		this(host, 12345, 12346);
	}

	/**
	 * Creates a new NetworkManager with specified ports
	 */
	public NetworkManager(String host, int bootstrapPort, int relayNodePort) {
		this.host = host;
		this.bootstrapPort = bootstrapPort;
		this.relayNodePort = relayNodePort;
		this.executor = Executors.newCachedThreadPool();
	}

	/**
	 * Starts the bootstrap server
	 */
	public CompletableFuture<BootstrapServer> startBootstrapServer() {
		CompletableFuture<BootstrapServer> future = new CompletableFuture<>();

		executor.submit(() -> {
			try {
				InetSocketAddress address = new InetSocketAddress(host, bootstrapPort);
				bootstrapServer = new BootstrapServer(address, "BootstrapServer-Local");
				bootstrapServer.start();
				Logger.Log("Bootstrap server started on " + host + ":" + bootstrapPort);
				future.complete(bootstrapServer);
			} catch (Exception e) {
				Logger.Log("Failed to start bootstrap server: " + e.getMessage());
				future.completeExceptionally(e);
			}
		});

		return future;
	}

	/**
	 * Starts a relay node
	 */
	public CompletableFuture<RelayNode> startRelayNode(RelayNode.NodeType nodeType) {
		CompletableFuture<RelayNode> future = new CompletableFuture<>();

		executor.submit(() -> {
			try {
				// Give the bootstrap server time to start if it hasn't already
				if (bootstrapServer != null) {
					Thread.sleep(1000);
				}

				InetSocketAddress address = new InetSocketAddress(host, relayNodePort);
				relayNode = new RelayNode(address, nodeType);
				relayNode.start();
				Logger.Log("Relay node started on " + host + ":" + relayNodePort);
				future.complete(relayNode);
			} catch (Exception e) {
				Logger.Log("Failed to start relay node: " + e.getMessage());
				future.completeExceptionally(e);
			}
		});

		return future;
	}

	/**
	 * Stops the bootstrap server
	 */
	public void stopBootstrapServer() {
		if (bootstrapServer != null) {
			try {
				bootstrapServer.stop();
				Logger.Log("Bootstrap server stopped");
			} catch (Exception e) {
				Logger.Log("Error stopping bootstrap server: " + e.getMessage());
			}
		}
	}

	/**
	 * Stops the relay node
	 */
	public void stopRelayNode() {
		if (relayNode != null) {
			try {
				relayNode.stop();
				Logger.Log("Relay node stopped");
			} catch (Exception e) {
				Logger.Log("Error stopping relay node: " + e.getMessage());
			}
		}
	}

	/**
	 * Stops all components and shuts down the executor
	 */
	public void shutdown() {
		stopRelayNode();
		stopBootstrapServer();
		executor.shutdown();
		Logger.Log("Network Manager shutdown complete");
	}

	/**
	 * Gets the bootstrap server
	 */
	public BootstrapServer getBootstrapServer() {
		return bootstrapServer;
	}

	/**
	 * Gets the relay node
	 */
	public RelayNode getRelayNode() {
		return relayNode;
	}

	/**
	 * Creates an address string for the bootstrap server
	 */
	public String getBootstrapAddress() {
		return host + ":" + bootstrapPort;
	}
}