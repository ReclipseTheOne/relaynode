package org.reclipse.relaynode.builder;

import org.reclipse.relaynode.RelayNode;
import org.reclipse.relaynode.util.Logger;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Utility class to build and manage a test network of relay nodes
 */
public class NetworkBuilder {

	private final List<RelayNode> nodes = new ArrayList<>();
	private final ExecutorService executor = Executors.newCachedThreadPool();
	private final CountDownLatch shutdownLatch = new CountDownLatch(1);

	/**
	 * Creates a bootstrap node
	 */
	public RelayNode createBootstrapNode(String host, int port) throws Exception {
		RelayNode bootstrapNode = new RelayNode(
				new InetSocketAddress(host, port),
				RelayNode.NodeType.BOOTSTRAP
		);
		nodes.add(bootstrapNode);
		return bootstrapNode;
	}

	/**
	 * Creates an entry node
	 */
	public RelayNode createEntryNode(String host, int port) throws Exception {
		RelayNode entryNode = new RelayNode(
				new InetSocketAddress(host, port),
				RelayNode.NodeType.ENTRY
		);
		nodes.add(entryNode);
		return entryNode;
	}

	/**
	 * Creates a relay node
	 */
	public RelayNode createRelayNode(String host, int port) throws Exception {
		RelayNode relayNode = new RelayNode(
				new InetSocketAddress(host, port),
				RelayNode.NodeType.RELAY
		);
		nodes.add(relayNode);
		return relayNode;
	}

	/**
	 * Creates an exit node
	 */
	public RelayNode createExitNode(String host, int port) throws Exception {
		RelayNode exitNode = new RelayNode(
				new InetSocketAddress(host, port),
				RelayNode.NodeType.EXIT
		);
		nodes.add(exitNode);
		return exitNode;
	}

	/**
	 * Starts all nodes
	 */
	public void startNetwork() {
		for (RelayNode node : nodes) {
			executor.submit(() -> {
				try {
					node.start();
				} catch (Exception e) {
					try {
						Logger.Log("Error starting node: " + e.getMessage());
					} catch (Exception ex) {
						System.err.println("Error logging: " + ex.getMessage());
					}
				}
			});
		}

		// Give nodes time to start
		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		// Connect the nodes
		connectNodes();

		try {
			Logger.Log("Network started with " + nodes.size() + " nodes");
		} catch (Exception e) {
			System.err.println("Error logging: " + e.getMessage());
		}
	}

	/**
	 * Connects all nodes to each other
	 */
	private void connectNodes() {
		try {
			// Wait for all nodes to be running
			Thread.sleep(2000);

			// Connect each node to every other node
			for (int i = 0; i < nodes.size(); i++) {
				RelayNode node1 = nodes.get(i);

				for (int j = 0; j < nodes.size(); j++) {
					if (i == j) continue;

					RelayNode node2 = nodes.get(j);
					String uri = "ws://" + node2.getNodeAddress().getHostString() + ":" + node2.getNodeAddress().getPort();

					node1.addPeer(
							node2.getNodeId(),
							uri,
							node2.getPublicKey()
					);
				}
			}

			Logger.Log("All nodes connected to each other");
		} catch (Exception e) {
			try {
				Logger.Log("Error connecting nodes: " + e.getMessage());
			} catch (Exception ex) {
				System.err.println("Error logging: " + ex.getMessage());
			}
		}
	}

	/**
	 * Stops all nodes and shuts down the executor
	 */
	public void stopNetwork() {
		for (RelayNode node : nodes) {
			try {
				node.stop();
			} catch (Exception e) {
				try {
					Logger.Log("Error stopping node: " + e.getMessage());
				} catch (Exception ex) {
					System.err.println("Error logging: " + ex.getMessage());
				}
			}
		}

		executor.shutdown();
		shutdownLatch.countDown();
		try {
			Logger.Log("Network stopped");
		} catch (Exception e) {
			System.err.println("Error logging: " + e.getMessage());
		}
	}

	/**
	 * Waits for the network to be shut down
	 */
	public void waitForShutdown() throws InterruptedException {
		shutdownLatch.await();
	}

	/**
	 * Main method to demonstrate network setup
	 */
	public static void main(String[] args) {
		try {
			Logger.loggerInit();

			NetworkBuilder builder = new NetworkBuilder();

			// Create a test network
			RelayNode bootstrap = builder.createBootstrapNode("localhost", 12345);
			RelayNode entry1 = builder.createEntryNode("localhost", 12346);
			RelayNode relay1 = builder.createRelayNode("localhost", 12347);
			RelayNode relay2 = builder.createRelayNode("localhost", 12348);
			RelayNode exit1 = builder.createExitNode("localhost", 12349);

			// Start the network
			builder.startNetwork();

			Logger.Log("Network is running. Press Enter to shut down...");
			System.in.read();

			// Stop the network
			builder.stopNetwork();

		} catch (Exception e) {
			System.err.println("Error: " + e.getMessage());
			e.printStackTrace();
		}
	}
}