# 配置与宿主集成

记录日期：2026-09-12。配置入口：`BridgeProperties`、`BridgeFilesProperties`、`BridgeAuthorizationProperties`。以下为当前源码默认值，配置在应用启动时生效；本版没有运行时工具管理后台。

## 已有授权服务

只引入 `com.cogistra:mcp-bridge-spring-boot-starter`。提供名为 `bridgeJwtDecoder` 的 `JwtDecoder`，负责可信签名、issuer、时间等验证；不要因增加第二个 bean 而使宿主原注入歧义。推荐为同一解码器提供 bean 别名。

默认 `JwtBridgeIdentityResolver` 继续要求 `iss`、`sub`、`client_id`、`exp`、目标 audience，读取 scope 与已有认证 authorities。它不会猜某个企业的停用账号、授权撤销或角色变更规则。已有这些规则的宿主必须实现 `BridgeIdentityResolver`，每次重新核验授权与账号状态，返回当前业务 `Authentication`。不能把 `SCOPE_x` 当作业务岗位，也不能由工具参数传 userId 来构造认证。

```yaml
mcp:
  bridge:
    enabled: true
    path: /mcp
    resource: https://service.example.com/mcp
    authorization-servers:
      - https://login.example.com
    storage: /var/lib/service/mcp/operations-and-audit
    files:
      directory: /var/lib/service/mcp/files
```

URL 为示例占位值。公开 URL 要求 HTTPS；开发仅允许 loopback HTTP。`allowed-origins` 默认空：没有 Origin 的非浏览器客户端可访问，带 Origin 的请求需精确命中配置。不能用 `*` 放开；令牌只从 Authorization header 接收。

## 只有账号密码

另引入 `mcp-bridge-authorization-server`，实现 `HostAccountDirectory` 与 `HostPasswordVerifier`。参照 [授权模块文档](authorization.md) 配置外置签名密钥、存储加密密钥、私有绝对目录和允许的客户端。

启用本地授权模块时，若根 `mcp.bridge.resource` / `authorization-servers` 未填写，会分别使用 `mcp.bridge.authorization.resource` / `issuer`。两处同时填写却不一致时拒绝启动。默认访问令牌 24 小时、刷新令牌 7 天、授权绝对上限 30 天；刷新轮换，绝对期限不会被无限延长。

## HTTP 安全链与发现地址

组件默认安全链只匹配 MCP、文件和审计路径及其受保护资源发现路径，OAuth 模块只匹配自己的浏览器/协议路径。若宿主完全依赖 Boot 默认安全而没有自己的链，会提供保护剩余路径的后备链，避免引入组件后普通私有接口意外公开。宿主已定义自己的链时不会用该后备链覆盖它；需要自行保证它的路径完整。

已有 MCP 安全链的宿主可提供名为 `bridgeSecurityFilterChain` 的 bean 接管这部分，必须覆盖 `/mcp`、`/mcp-bridge/files/**`、`/mcp-bridge/audit/**` 并处理发现路径。不能只保护 /mcp 而把辅助文件路径留空。已有 OAuth/资源发现页面时可设置 `publish-resource-metadata: false`，保留原公开契约；同时保留正确 WWW-Authenticate 中的 metadata URL。

默认发现路径为 `/.well-known/oauth-protected-resource` 及带资源路径后缀的变体。存在 servlet context-path 或反向代理路径前缀时，必须将公网发现 URL 路由到正确应用路径，或由宿主链和发现端点接管。配置的 resource 是客户端看到的外部 URI，不能用内部容器地址替代。

## 主要配置

| 配置（`mcp.bridge.` 前缀） | 默认值 / 语义 |
|---|---|
| `enabled` | true；启用时缺少必要身份配置会明确启动失败；false 停用主组件及默认文件模块 |
| `path` / `name` | `/mcp` / `spring-mcp-bridge` |
| `publish-resource-metadata` | true |
| `resource` / `authorization-servers` | 外部授权服务模式必须配置；本地授权模式可按上述规则继承 |
| `storage` | `.local/mcp-bridge`；操作和审计分别在子目录；生产需私有持久目录 |
| `confirmation-ttl` | 20 分钟；允许 1 毫秒–7 天 |
| `max-argument-bytes` | 1 MiB；JSON 工具参数上限 |
| `max-result-bytes` | 2 MiB；MCP 结果上限 |
| `max-export-bytes` | 16 MiB；同步 Servlet 响应捕获上限，最终仍受文件限额约束 |
| `max-records` | 操作和审计各 10000 条；不静默删除历史去重/审计记录 |
| `max-storage-bytes` | 操作文件、审计文件各 256 MiB；达到限制拒绝新增 |
| `allowed-origins` | 空集合 |
| `tools.<name>.enabled` | true；配置 false 停用对应能力 |
| `tools.<name>.scopes` / `authorities` | 空；与注解 scopes 共同限制，而非授予业务权限 |
| `tools.<name>.effect` | 遵循显式注解；没有声明的原生工具默认 WRITE，允许配置其分类；禁止把显式 WRITE 降为 READ |
| `tools.<name>.allow-authenticated` | false；只在明确允许所有已认证调用者访问该能力时配置 |

参数和结果配置允许 1 字节–16 MiB，但很小的限额无法承载正常协议或完整预览。准备的原始+执行参数快照必须在 `min(max-argument-bytes, max-result-bytes/4)` 内；默认 512 KiB。操作内部结果预算为 `max-result-bytes/2`，预留回执和预览空间。参数过大在保存前拒绝，应使用文件引用或分页。超过内部结果预算且业务已经执行时按 UNKNOWN 处理，不自动重跑。

`tools` 是按**注册策略键**配置的 map，键与客户端公开名称不总相同：

| 类型 | 配置键 |
|---|---|
| 自定义/原生 READ 工具 | 注解或 callback 声明的原始工具名 |
| 自定义/原生 WRITE 工具 | 声明原名，例如 `sample_patch`；统一限制它的 prepare、submit 和旧操作查询，不填 `sample_patch_prepare` 或 `sample_patch_submit` |
| 原生资源及资源模板 | `resource.<注解 name>` |
| 原生提示词 | `prompt.<注解 name>` |
| 原生补全 | `completion.<Java 方法名>`；不是被补全资源 URI |
| 系统工具 | 完整公开名称，例如 `bridge_file_begin` 或 `bridge_audit_query` |

以下为语法示例，前提是应用实际注册了这些能力。带点号的 map 键用方括号保持整体键名：

```yaml
mcp:
  bridge:
    tools:
      sample_patch:
        enabled: false
      "[resource.personal_records]":
        scopes: [records:read]
      bridge_file_begin:
        authorities: [files:upload]
```

未知配置键或重复策略名会在启动时报错；错误拼写不能被静默当成已停用/已限制。配置某个未注册工具、给 WRITE 的派生名配置策略，或者文件模块关闭后继续配置其未注册工具，都需要先纠正配置。自定义 HTTP 文件上传端点仍独立受宿主 HTTP 安全链和身份解析保护；停用 `bridge_file_begin` 只停用这个 MCP 工具，不会关闭 HTTP 上传。

文件默认单个 16 MiB、当前归属总额 128 MiB、全局 1 GiB、分块 256 KiB、文件 24 小时、上传会话 1 小时。配置过大的 chunk 无法装入 JSON/base64 预算时启动拒绝。HTTP multipart 还受 `spring.servlet.multipart.max-file-size` / `max-request-size` 和反向代理限制，需共同配置；文件模块不会擅自改宿主全局上传限制。详见 [文件模块](files.md)。

## 可替换的边界

| SPI | 宿主职责 |
|---|---|
| `BridgeIdentityResolver` | 当前可信身份、撤销/停用/版本、业务认证 |
| `BridgeAuthorization` | 对象、字段、客户端与业务阶段权限，独立于页面显示 |
| `BridgeDiscoveryPolicy` | 按当前账号决定能力元数据是否可见；不扩张实际调用权限 |
| `BridgeContractAdapter` | 特定接口输入解析和输出投影；不得在输入映射中写业务 |
| `OperationStore` / `OperationRecoveryResolver` | 共享持久化、幂等范围、可靠业务结果恢复 |
| `AuditRepository` | 元数据审计存储与查询；默认仅本人同客户端 |
| `BridgeFileStore` | 可替换的产物存储、读取及删除接口；外部实现必须保留所有者/客户端和有效期边界 |
| `BridgeAuthorizationStore` | 可选授权模块的持久化；集群需共享实现 |

本地操作/审计文件不是业务数据库事务的一部分。多实例服务必须替换这些存储与恢复机制；不能让多个实例各保存一份内存/本地去重表后声称全局幂等。已有账号服务应保留原授权数据库，通过 SPI 适配，无需为了接入本组件迁移成样例文件存储。

`FileUploadService` 是默认本地分块实现，依赖 `LocalBridgeFileStore`，不是可替换上传 SPI。仅替换 `BridgeFileStore` 不会让公共分块工具自动写入云存储；当前云存储接入需要宿主提供自己的受控上传工具/流程，再通过产物 SPI 提供文件引用。默认本地实现已完整支持 begin/append/complete/cancel 与重试。
