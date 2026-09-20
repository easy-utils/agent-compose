# agent-compose

Compose Multiplatform agent chat app. Shared Compose UI + the single
`AgentApiImpl` in `commonMain` against the typed `agent-sdk-kotlin` client
over **easy-rpc** (proto3 + JSON). The transport differs per platform
(desktop/android: OkHttp with the bundled ingress CA + bearer interceptor;
web/wasmJs: the KMP Ktor fetch transport) while the RPC surface and the
pb→model mapping are shared. Breakpoints agree with the other clients:
<600 = single column (drawer/bottom bar), >=600 = split, >=1024 = wide.

```
AGENT_BASE_URL=https://agent.example.com AGENT_TOKEN=devtoken gradle :desktopRun
```
