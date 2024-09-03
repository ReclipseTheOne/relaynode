
## The message object is of 2 types:
  - Node
  - Message

### The node message is used in creating peer handshakes and sending info about the node and has these metadata fields:
 - MessageQueue -> `List<String>`
 - Message -> `String` (Encrypted)
 - NodeUUID -> `UUID`
 - SocketAddress -> `String`
 - PublicKey -> `PublicKey`

### The normal message is just a simple text and is simpler in structure:
 - MessageQueue -> `List<String>`
 - Message -> `String` (Encrypted)

