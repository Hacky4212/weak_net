# Graph Report - C:\Users\yangxu\Desktop\weak_net\app\src\main\java  (2026-10-08)

## Corpus Check
- Corpus is ~2,345 words - fits in a single context window. You may not need a graph.

## Summary
- 113 nodes · 235 edges · 9 communities (5 shown, 4 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 17 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
-  / MainActivity
-  / PacketBridge
- WeakVpnService / 
- LocalSocks / 
- AndroidPacketPort / 
- LocalSocks / ResourceGroup
- LocalSocks / 
- LocalSocks / ResourceGroup
- LocalSocks / 

## God Nodes (most connected - your core abstractions)
1. `LocalSocks` - 22 edges
2. `MainActivity` - 20 edges
3. `PacketBridge` - 16 edges
4. `WeakVpnService` - 14 edges
5. `ResourceGroup` - 10 edges
6. `AndroidPacketPort` - 7 edges
7. `Profile` - 7 edges
8. `Session` - 7 edges
9. `Port` - 6 edges
10. `Direction` - 5 edges

## Surprising Connections (you probably didn't know these)
- `AndroidPacketPort` --implements--> `Port`  [EXTRACTED]
  cn/weaknet/lab/AndroidPacketPort.java → cn/weaknet/lab/PacketBridge.java
- `LocalSocks` --references--> `ResourceGroup`  [EXTRACTED]
  cn/weaknet/lab/LocalSocks.java → cn/weaknet/lab/ResourceGroup.java
- `Session` --references--> `LocalSocks`  [EXTRACTED]
  cn/weaknet/lab/WeakVpnService.java → cn/weaknet/lab/LocalSocks.java
- `MainActivity` --references--> `PacketBridge`  [EXTRACTED]
  cn/weaknet/lab/MainActivity.java → cn/weaknet/lab/PacketBridge.java
- `Session` --references--> `PacketBridge`  [EXTRACTED]
  cn/weaknet/lab/WeakVpnService.java → cn/weaknet/lab/PacketBridge.java

## Import Cycles
- None detected.

## Communities (9 total, 4 thin omitted)

### Community 0 - " / MainActivity"
Cohesion: 0.17
Nodes (12): Activity, Bundle, Button, Intent, Override, MainActivity, EditText, GradientDrawable (+4 more)

### Community 1 - " / PacketBridge"
Cohesion: 0.13
Nodes (8): ArrayBlockingQueue, Direction, Override, Packet, PacketBridge, Port, PacketSchedule, Profile

### Community 2 - "WeakVpnService / "
Cohesion: 0.20
Nodes (10): FileDescriptor, Intent, Override, Session, WeakVpnService, Context, ParcelFileDescriptor, PendingIntent (+2 more)

### Community 3 - "LocalSocks / "
Cohesion: 0.27
Nodes (4): Override, LocalSocks, Protector, ServerSocket

### Community 4 - "AndroidPacketPort / "
Cohesion: 0.39
Nodes (4): AndroidPacketPort, FileDescriptor, Override, StructPollfd

## Knowledge Gaps
- **4 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `PacketBridge` connect ` / PacketBridge` to ` / MainActivity`, `WeakVpnService / `?**
  _High betweenness centrality (0.577) - this node is a cross-community bridge._
- **Why does `LocalSocks` connect `LocalSocks / ` to `WeakVpnService / `, `LocalSocks / ResourceGroup`, `LocalSocks / `, `LocalSocks / ResourceGroup`, `LocalSocks / `?**
  _High betweenness centrality (0.448) - this node is a cross-community bridge._
- **Why does `MainActivity` connect ` / MainActivity` to ` / PacketBridge`?**
  _High betweenness centrality (0.314) - this node is a cross-community bridge._
- **Should ` / PacketBridge` be split into smaller, more focused modules?**
  _Cohesion score 0.13 - nodes in this community are weakly interconnected._
## Extraction limitations

{"node_count": 113, "raw_edge_count": 291, "dangling_endpoint_edges": 36, "missing_endpoint_edges": 0, "undirected_same_endpoint_collapsed_edges": 20}

AST-only extraction; external types and overloads may be unresolved. Consult source and tests for review conclusions.
