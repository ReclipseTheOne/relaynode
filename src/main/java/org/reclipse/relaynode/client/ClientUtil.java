package org.reclipse.relaynode.client;

import org.reclipse.relaynode.RelayNode.NodeType;
import org.reclipse.relaynode.network.discovery.NodeInfo;
import org.reclipse.relaynode.util.Logger;

import java.util.Scanner;

/**
 * Command-line utility for relay network operations
 */
public class ClientUtil {

	private RelayClient client;
	private Scanner scanner;
	private boolean running = true;

	/**
	 * Initializes the CLI utility
	 */
	public ClientUtil() {
		scanner = new Scanner(System.in);
	}

	/**
	 * Starts the utility
	 */
	public void start() {
		try {
			Logger.loggerInit();
			System.out.println("===== Relay Network Utility =====");
			showStartMenu();

			while (running) {
				System.out.print("> ");
				String command = scanner.nextLine().trim();
				processCommand(command);
			}
		} catch (Exception e) {
			System.err.println("Error: " + e.getMessage());
			e.printStackTrace();
		}
	}

	/**
	 * Shows the startup mode selection menu
	 */
	private void showStartMenu() {
		System.out.println("\nChoose startup mode:");
		System.out.println("1. Integrated mode (start local bootstrap server and relay node)");
		System.out.println("2. Client-only mode (connect to external bootstrap server)");
		System.out.println("q. Quit");

		boolean validChoice = false;
		while (!validChoice) {
			System.out.print("Select mode (1/2/q): ");
			String choice = scanner.nextLine().trim();

			try {
				switch (choice) {
					case "1":
						startIntegratedMode();
						validChoice = true;
						break;
					case "2":
						startClientMode();
						validChoice = true;
						break;
					case "q":
					case "Q":
						running = false;
						validChoice = true;
						break;
					default:
						System.out.println("Invalid choice. Please try again.");
				}
			} catch (Exception e) {
				System.err.println("Error: " + e.getMessage());
				System.out.println("Please try again.");
			}
		}
	}

	/**
	 * Starts in integrated mode with local bootstrap server and relay node
	 */
	private void startIntegratedMode() throws Exception {
		System.out.println("\nStarting in integrated mode...");

		// Get port configuration
		System.out.print("Enter bootstrap server port (default: 12345): ");
		String bootstrapPortStr = scanner.nextLine().trim();
		int bootstrapPort = bootstrapPortStr.isEmpty() ? 12345 : Integer.parseInt(bootstrapPortStr);

		System.out.print("Enter relay node port (default: 12346): ");
		String relayPortStr = scanner.nextLine().trim();
		int relayPort = relayPortStr.isEmpty() ? 12346 : Integer.parseInt(relayPortStr);

		System.out.println("Initializing integrated network...");
		client = new RelayClient();

		if (client.initializeIntegratedNetwork("localhost", bootstrapPort, relayPort)) {
			System.out.println("Successfully started integrated network!");
			System.out.println("Bootstrap server running on localhost:" + bootstrapPort);
			System.out.println("Relay node running on localhost:" + relayPort);

			// Wait for nodes to register
			System.out.println("Waiting for node registration...");
			Thread.sleep(2000);

			showMainMenu();
		} else {
			System.out.println("Failed to start integrated network. Please check logs for details.");
			showStartMenu();
		}
	}

	/**
	 * Starts in client-only mode connecting to external bootstrap
	 */
	private void startClientMode() throws Exception {
		System.out.println("\nStarting in client-only mode...");

		System.out.print("Enter bootstrap server address (default: localhost:12345): ");
		String bootstrapAddress = scanner.nextLine().trim();
		if (bootstrapAddress.isEmpty()) {
			bootstrapAddress = "localhost:12345";
		}

		System.out.println("Connecting to bootstrap server at " + bootstrapAddress + "...");
		client = new RelayClient();

		if (client.connectToBootstrap(bootstrapAddress)) {
			System.out.println("Successfully connected to bootstrap server!");

			// Wait for node list
			System.out.println("Receiving node list...");
			Thread.sleep(2000);

			showMainMenu();
		} else {
			System.out.println("Failed to connect to bootstrap server. Please check address and try again.");
			showStartMenu();
		}
	}

	/**
	 * Shows the main menu
	 */
	private void showMainMenu() {
		System.out.println("\n===== Main Menu =====");
		System.out.println("1. Build a circuit");
		System.out.println("2. Send a message through circuit");
		System.out.println("3. Destroy a circuit");
		System.out.println("4. List known nodes");
		System.out.println("5. List active circuits");
		System.out.println("0. Disconnect and return to start menu");
		System.out.println("q. Quit");
	}

	/**
	 * Processes user commands
	 */
	private void processCommand(String command) {
		try {
			switch (command) {
				case "1":
					buildCircuit();
					break;
				case "2":
					sendMessage();
					break;
				case "3":
					destroyCircuit();
					break;
				case "4":
					listNodes();
					break;
				case "5":
					listCircuits();
					break;
				case "0":
					disconnect();
					showStartMenu();
					break;
				case "menu":
					showMainMenu();
					break;
				case "q":
				case "Q":
					quit();
					break;
				default:
					System.out.println("Unknown command. Type 'menu' to see available commands.");
			}
		} catch (Exception e) {
			System.err.println("Error: " + e.getMessage());
		}
	}

	/**
	 * Builds a new circuit
	 */
	private void buildCircuit() throws Exception {
		System.out.print("Enter circuit length (default: 3): ");
		String lengthStr = scanner.nextLine().trim();
		int length = lengthStr.isEmpty() ? 3 : Integer.parseInt(lengthStr);

		System.out.println("Building circuit with length " + length + "...");
		String circuitId = client.buildCircuit(length);
		System.out.println("Circuit built successfully: " + circuitId);

		showMainMenu();
	}

	/**
	 * Sends a message through an active circuit
	 */
	private void sendMessage() throws Exception {
		System.out.print("Enter circuit ID: ");
		String circuitId = scanner.nextLine().trim();

		System.out.print("Enter message to send: ");
		String message = scanner.nextLine();

		System.out.println("Sending message through circuit...");
		String response = client.sendMessage(circuitId, message);
		System.out.println("Response received: " + response);

		showMainMenu();
	}

	/**
	 * Destroys an active circuit
	 */
	private void destroyCircuit() throws Exception {
		System.out.print("Enter circuit ID to destroy: ");
		String circuitId = scanner.nextLine().trim();

		client.destroyCircuit(circuitId);
		System.out.println("Circuit destruction request sent for: " + circuitId);

		showMainMenu();
	}

	/**
	 * Lists all known nodes
	 */
	private void listNodes() {
		System.out.println("\n===== Known Nodes =====");
		java.util.List<NodeInfo> nodes = client.getKnownNodes();

		if (nodes.isEmpty()) {
			System.out.println("No nodes found.");
		} else {
			for (NodeInfo node : nodes) {
				System.out.println("Node ID: " + node.getNodeId());
				System.out.println("  Name: " + node.getNodeName());
				System.out.println("  Address: " + node.getAddress());
				System.out.println("  Type: " + node.getNodeType());
				System.out.println("  Online: " + node.isOnline());
				System.out.println("  Latency: " + (node.getLatency() < 0 ? "Unknown" : node.getLatency() + "ms"));
				System.out.println();
			}
			System.out.println("Total nodes: " + nodes.size());
		}

		showMainMenu();
	}

	/**
	 * Lists all active circuits
	 */
	private void listCircuits() {
		System.out.println("\n===== Active Circuits =====");
		java.util.List<RelayClient.Circuit> circuits = client.getActiveCircuits();

		if (circuits.isEmpty()) {
			System.out.println("No active circuits.");
		} else {
			for (RelayClient.Circuit circuit : circuits) {
				System.out.println("Circuit ID: " + circuit.circuitId);
				System.out.println("  Path: " + circuit.path);
				System.out.println("  Established: " + circuit.isEstablished);
				System.out.println("  Created: " + new java.util.Date(circuit.creationTime));
				System.out.println();
			}
			System.out.println("Total circuits: " + circuits.size());
		}

		showMainMenu();
	}

	/**
	 * Disconnects and cleans up
	 */
	private void disconnect() {
		if (client != null) {
			if (client.isIntegratedMode()) {
				System.out.println("Shutting down integrated network...");
				client.shutdownIntegratedNetwork();
			} else {
				System.out.println("Disconnecting from bootstrap server...");
				client.disconnect();
			}
			client = null;
		}
	}

	/**
	 * Exits the application
	 */
	private void quit() {
		disconnect();
		running = false;
		System.out.println("Goodbye!");
	}

	/**
	 * Main method
	 */
	public static void main(String[] args) {
		RelayNetworkUtil util = new RelayNetworkUtil();
		util.start();
	}
}