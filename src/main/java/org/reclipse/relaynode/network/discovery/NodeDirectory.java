package org.reclipse.relaynode.network.discovery;

import org.reclipse.relaynode.RelayNode.NodeType;
import org.reclipse.relaynode.util.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Manages a directory of known nodes in the relay network
 */
public class NodeDirectory {

	private static final int DEFAULT_CLEANUP_INTERVAL_MINS = 10;
	private static final long NODE_TIMEOUT_MS = 1000 * 60 * 30; // 30 minutes
	private static final String CACHE_FILENAME = "node_cache.bin";

	private final Map<UUID, NodeInfo> nodes;
	private final ScheduledExecutorService scheduler;
	private final Random random;
	private final File cacheFile;

	/**
	 * Creates a new NodeDirectory
	 */
	public NodeDirectory() {
		this(new File(CACHE_FILENAME));
	}

	/**
	 * Creates a new NodeDirectory with a specific cache file
	 */
	public NodeDirectory(File cacheFile) {
		this.nodes = new ConcurrentHashMap<>();
		this.scheduler = Executors.newSingleThreadScheduledExecutor();
		this.random = new Random();
		this.cacheFile = cacheFile;

		// Start the cleanup task
		scheduler.scheduleAtFixedRate(
				this::cleanupStaleNodes,
				DEFAULT_CLEANUP_INTERVAL_MINS,
				DEFAULT_CLEANUP_INTERVAL_MINS,
				TimeUnit.MINUTES
		);

		// Try to load cached nodes
		loadNodesFromCache();
	}

	/**
	 * Adds a node to the directory
	 */
	public void addNode(NodeInfo node) {
		nodes.put(node.getNodeId(), node);
		try {
			Logger.Log("Added node to directory: " + node.getNodeName() + " (" + node.getNodeId() + ")");
		} catch (Exception e) {
			System.err.println("Error logging: " + e.getMessage());
		}
	}

	/**
	 * Adds a node to the directory
	 */
	public void addNode(UUID nodeId, String nodeName, String address, PublicKey publicKey, NodeType nodeType) {
		NodeInfo node = new NodeInfo(nodeId, nodeName, address, publicKey, nodeType);
		addNode(node);
	}

	/**
	 * Removes a node from the directory
	 */
	public void removeNode(UUID nodeId) {
		nodes.remove(nodeId);
		try {
			Logger.Log("Removed node from directory: " + nodeId);
		} catch (Exception e) {
			System.err.println("Error logging: " + e.getMessage());
		}
	}

	/**
	 * Gets information about a specific node
	 */
	public NodeInfo getNode(UUID nodeId) {
		return nodes.get(nodeId);
	}

	/**
	 * Gets a list of all known nodes
	 */
	public List<NodeInfo> getAllNodes() {
		return new ArrayList<>(nodes.values());
	}

	/**
	 * Gets a list of all active nodes
	 */
	public List<NodeInfo> getActiveNodes() {
		return nodes.values().stream()
				.filter(NodeInfo::isOnline)
				.collect(Collectors.toList());
	}

	/**
	 * Gets a list of active nodes of a specific type
	 */
	public List<NodeInfo> getActiveNodesOfType(NodeType type) {
		return nodes.values().stream()
				.filter(NodeInfo::isOnline)
				.filter(node -> node.getNodeType() == type)
				.collect(Collectors.toList());
	}

	/**
	 * Gets a random subset of active nodes
	 */
	public List<NodeInfo> getRandomNodes(int count) {
		List<NodeInfo> activeNodes = getActiveNodes();

		if (activeNodes.size() <= count) {
			return new ArrayList<>(activeNodes);
		}

		// Shuffle and take first 'count' elements
		Collections.shuffle(activeNodes, random);
		return activeNodes.subList(0, count);
	}

	/**
	 * Updates a node's online status
	 */
	public void updateNodeStatus(UUID nodeId, boolean isOnline) {
		NodeInfo node = nodes.get(nodeId);
		if (node != null) {
			node.setOnline(isOnline);
			node.updateLastSeen();
		}
	}

	/**
	 * Updates a node's last seen timestamp
	 */
	public void updateNodeLastSeen(UUID nodeId) {
		NodeInfo node = nodes.get(nodeId);
		if (node != null) {
			node.updateLastSeen();
		}
	}

	/**
	 * Cleans up stale nodes
	 */
	private void cleanupStaleNodes() {
		long now = System.currentTimeMillis();
		int removedCount = 0;

		for (Iterator<Map.Entry<UUID, NodeInfo>> it = nodes.entrySet().iterator(); it.hasNext();) {
			Map.Entry<UUID, NodeInfo> entry = it.next();
			NodeInfo node = entry.getValue();

			if (now - node.getLastSeen() > NODE_TIMEOUT_MS) {
				it.remove();
				removedCount++;
			}
		}

		if (removedCount > 0) {
			try {
				Logger.Log("Cleaned up " + removedCount + " stale nodes");
			} catch (Exception e) {
				System.err.println("Error logging: " + e.getMessage());
			}
		}

		// Save current nodes to cache
		saveNodesToCache();
	}

	/**
	 * Saves the current nodes to cache
	 */
	public void saveNodesToCache() {
		try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
			// In a real implementation, we would serialize the nodes here
			// This is a simplified placeholder

			Logger.Log("Saved " + nodes.size() + " nodes to cache");
		} catch (Exception e) {
			System.err.println("Error saving node cache: " + e.getMessage());
		}
	}

	/**
	 * Loads nodes from cache
	 */
	private void loadNodesFromCache() {
		if (!cacheFile.exists()) {
			return;
		}

		try (FileInputStream fis = new FileInputStream(cacheFile)) {
			// In a real implementation, we would deserialize the nodes here
			// This is a simplified placeholder

			Logger.Log("Loaded nodes from cache");
		} catch (Exception e) {
			System.err.println("Error loading node cache: " + e.getMessage());
		}
	}

	/**
	 * Shuts down the node directory
	 */
	public void shutdown() {
		scheduler.shutdown();
		saveNodesToCache();
	}
}