
### The message object currently has 4 sections of metadata:

1.  Type:
	- "node": Is processed as a handshake to create a new peer connection and has a 5th metadata field called `socketAddress` for the node URI.
	- "message": Is processed normally through the [[Connection Queue]]
2.  nodeUUID
3.  message
4.  connectionQueue