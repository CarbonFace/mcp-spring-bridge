# 写操作确认、持久化恢复与审计

记录日期：2026-09-12。需求依据：第一版独立 Spring Boot 组件；自定义 HTTP 工具和 Spring AI 原生工具使用同一写操作确认、版本、权限与审计边界。本文说明 `mcp-bridge-core` 的公共实现，不代表下游业务接入、真实数据库验收或上线完成。

## 业务场景与责任

一个已登录员工通过客户端准备一个写操作，看到完整待执行参数，再确认该版本。服务器重新核对当前身份和业务权限，执行已保存的同一份内容。网络断开后，员工按 operationId 查询结果；客户端不能把超时理解成失败并自动重新提交。另一员工或同一员工的其他客户端不能查看、确认或取消这份操作。

公共引擎不包含订单、财务、岗位、账号表、DAO 或 MyBatis。宿主负责核验真实业务对象、字段、锁、引用与版本。`BridgeIdentity` 必须来自受信任认证链，不能由工具参数绑定。每次调用，包括读取历史成功回执，都需要宿主重新核对当前授权；库内的归属校验不能代替这些检查。

## 公开 API

入口包：`com.cogistra.mcpbridge.operation`。

```java
OperationStore store = new LocalFileOperationStore(privateDirectory, 10_000, 64L * 1024 * 1024);
OperationCoordinator operations = new OperationCoordinator(
        store, Clock.systemUTC(), Duration.ofMinutes(15), 256 * 1024, 1024 * 1024);

OperationView preview = operations.prepare(identity, "sample.change", validatedArguments, requestId);
OperationView changed = operations.revise(identity, preview.operationId(), preview.revision(), changedArguments);
// 客户端展示 changed 的完整 preview 并确认；宿主重新认证和授权后调用：
OperationView receipt = operations.execute(identity, changed.operationId(), changed.revision(),
        changed.payloadHash(), execution -> host.executeInTransaction(
                execution.operationId(), execution.arguments()));
OperationView current = operations.status(identity, changed.operationId());
```

示例 `host.executeInTransaction` 表示宿主提供的事务实现，不是组件内的业务服务。实际方法：

| 方法 | 行为 |
|---|---|
| `prepare(identity, capabilityName, canonicalArguments, requestId)` | 保存不可随调用替换的参数快照，返回 operationId、revision、payloadHash、完整 preview、expiresAtEpochMillis、状态 |
| `revise(identity, id, expectedRevision, newCanonicalArguments)` | 仅 PREPARED 可修改；内容改变时原子提升版本、重算 hash、更新到期时间，旧确认立即失效；内容不变保留当前版本 |
| `execute(identity, id, revision, hash, callback)` | 先持久化 EXECUTING，再调用业务；callback 只收到保存的 OperationExecution，不接收客户端提交的第二份正文 |
| `status(identity, id)` | 返回本人、同 issuer、同 client 的完整预览及已有结果；不改变业务事实 |
| `cancel(identity, id, revision)` | 仅取消尚未开始的 PREPARED；已执行/执行中/未知不能取消，也不假称撤销业务效果 |
| `recover(identity, id, resolver)` | 仅 UNKNOWN，调用宿主凭事实查询结果的扩展；不执行原业务命令 |

调用方可以调用 `preview()` / `arguments()` 取得副本，但修改副本不会改变保存内容。JSON 对象键排序、数组顺序保留、不合并数值或业务含义，SHA-256 包含 capabilityName 与标准化后参数。缺字段和显式 null 保持不同；映射身份证、客户名、币种、空数组等业务语义由宿主或专用适配器决定。持久化保留大整数和十进制值，不将业务金额转成 double。

归属为完整 `(issuer, subject, clientId)`。requestId 在这个归属下唯一，跨 capabilityName 也不复用。同 requestId、同内容返回原记录；不同内容拒绝 REQUEST_CONFLICT。`revise` 是显式例外入口，会修改现有操作并使其旧确认失效。开始执行后禁止修改；修正已完成业务必须走新的、已授权业务动作，不是覆盖旧回执。

## 状态及失败语义

| 状态 | 含义与下一步 |
|---|---|
| PREPARED | 已保存完整预览，尚未执行；可重新查看、修改或取消 |
| EXECUTING | 已持久化执行占用；重复 execute 不再调用业务，取消不被允许 |
| SUCCEEDED | 成功结果已记录；重复 execute 返回同一回执，仍需当前授权 |
| REJECTED | 宿主能够证明没有产生副作用；如需更改重试，创建新操作并确认 |
| CANCELLED | 执行开始前取消，不会执行 |
| EXPIRED | 预览过期，不会执行；创建新操作并确认 |
| UNKNOWN | 业务可能已提交，禁止自动重跑；先查宿主持久事实恢复结果 |

业务只有明确保证未产生副作用时才抛 `OperationRejectedException(code, safeMessage)`。前置参数/权限拒绝可以采用该类型；如果此前发生了外部请求、数据库提交或无法判断回滚结果，就不应使用它。普通异常、回调后的结果无法序列化/超限、结果回执写失败均为 UNKNOWN。异常正文不进入结果，只返回限制格式的错误码。

进程在 EXECUTING 期间退出，重新打开本地 store 时会将该记录标记为 UNKNOWN。store 在整个生命周期持有操作文件的进程独占锁，第二个活进程无法打开相同文件。协调器不根据本进程是否存在调用记录猜测执行中断；其他协调器查询或重复确认只读取已有 EXECUTING，不会使仍在执行的操作失效。同一进程内多个协调器可共享同一个受锁保护的 store 对象；需要横向多实例时必须实现带分布式事务、执行归属/租约与恢复保证的 store，不能在各进程分别打开本地文件 store。

执行本次 callback 的协调器知道自己的回调已经结束。成功回执写入失败时，它返回 `UNKNOWN / RECEIPT_NOT_CONFIRMED`，并仅在同一归属、版本仍为 EXECUTING 时尝试记录 UNKNOWN。如果这次失败回执也无法持久化，存储中的状态暂时仍是 EXECUTING，查询不会擅自改写；它同样禁止重跑。恢复存储后，本地实现需停止原 store 并重新打开，由独占锁下的启动恢复转为 UNKNOWN；分布式实现必须先由权威执行归属/租约证明原执行者已经停止。随后才能凭宿主事实使用 `recover`，不能因超时或一次查不到业务回执便认定没有副作用。

过期检查仅使 PREPARED 失效，不会删除已经成功的回执，也不会把 UNKNOWN 当作过期未执行。取消和成功记录均保留 requestId，防止清理后误触发同一请求。

## 与宿主事务及结果恢复的连接

**本地操作文件和任意业务数据库不能天然原子提交。本实现不提供跨它们的 exactly-once 承诺。** 即使 callback 在数据库提交后返回成功，写本地成功回执仍可能失败；因此必须返回 UNKNOWN 并查询事实。

`OperationCallback` 是宿主事务执行扩展点：宿主可在一笔自身事务内，用 operationId 作为稳定去重键，同时提交业务事实与该 operationId 的结果回执。Spring 代理事务可在 callback 内调用，事务管理器由宿主负责。外部 HTTP 服务还需它自身的幂等约定，不能认为数据库回滚能撤销外部效果。

`OperationRecoveryResolver` 是结果恢复扩展点：根据 operationId / revision / payloadHash 查询宿主持久化凭证，返回：

- `RecoveryDecision.committed(result)`：已经提交，恢复为 SUCCEEDED。
- `RecoveryDecision.noEffect(code)`：已可靠确认没有副作用，恢复为 REJECTED；不会自动重试。
- `RecoveryDecision.unresolved()`：证据不足，继续 UNKNOWN。

resolver 不接收模型声称的“已经失败/尚未写入”，不通过再次执行业务来探测。恢复操作的权限与审计由宿主重新检查；调用者不能只传 operationId 就取得跨用户业务回执。建议客户端提示：“结果暂时无法确认，请按此操作编号查询；确认前不要再次提交。”

客户端对话确认是客户端义务。operationId、hash、revision 只证明提交内容与预览一致，不能独立证明真人在聊天里说了“同意”。如果某项目需要可证明的人工审批，必须提供受信任审批事实并在宿主授权层核验。

## OperationStore SPI 与本地实现限制

`OperationStore.locked(Function<Session,T>)` 要求所有写入者互斥；`Session.put` 返回前必须完成持久写入。业务 callback 在存储锁外执行，公共接口没有数据库框架依赖。自定义多实例实现还需处理启动恢复、活实例执行租约及未知状态，不得在启动或查询时无条件清理其他实例的 EXECUTING。只有权威存储确认执行归属已经失效后才可转 UNKNOWN，并防止过期执行者继续提交业务；这些分布式保证并未由本版本的本地 store 实现。

`LocalFileOperationStore(Path, maxRecords, maxBytes)`：

- maxRecords 范围 1–100000；maxBytes 为 1 KiB–256 MiB。目录由宿主明确配置，不能用上传目录、Web 静态目录或代码库作为生产存储。
- coordinator 的预览有效期必须为 1 毫秒–7 天；参数、结果各为 1 字节–16 MiB；JSON 最大嵌套深度 64。示例值是示例，实际 starter 配置以其文档为准。
- 私有目录和文件：POSIX 目录 0700、文件 0600；Windows 只给目录所有者完整 ACL。拒绝路径链接和非普通数据文件。宿主需控制路径配置与运行用户；不支持多个系统用户共用这些私有文件。
- 保存使用同目录临时文件、文件 force、原子替换；不支持原子替换的文件系统失败关闭。进程退出后可恢复；目录 fsync 能力受操作系统影响，不能承诺突然断电、损坏介质、NFS/网络盘下的跨系统持久性。
- 数据不加密，虽然目录私有，完整预览和业务回执仍属于敏感资料；需要磁盘加密、备份权限、保留期限的项目应由宿主配置，或替换 store。
- 达到数量/字节上限时拒绝新增，不会悄悄删除成功/未知记录。必须监控容量并经专门保留、备份和幂等期限方案迁移；直接删除目录会丢失去重依据，可能导致重复业务。
- 配置/容量/坏文件错误保留已有数据并拒绝，绝不以空存储继续运行。关闭组件需关闭 store 释放锁。

## 最小审计与本人查询

入口包：`com.cogistra.mcpbridge.audit`。

`AuditEvent` 仅保存 eventId、时间、issuer/subject/clientId、capabilityName、可选 operationId、阶段、结果和稳定错误码。不能保存 token、密码、原始参数、原始结果或异常正文。阶段/结果为枚举，标识和错误码限制字符及长度，避免将异常正文误塞入日志。

```java
AuditRepository audit = new LocalFileAuditRepository(privateAuditDirectory, 50_000, 32L * 1024 * 1024);
audit.append(AuditEvent.create(identity, "sample.change", operationId,
        AuditEvent.Phase.EXECUTE, AuditEvent.Outcome.SUCCEEDED, null));
AuditPage page = audit.query(identity, cursor, 50);
```

`append` 同 eventId 同内容可重试，不同内容冲突。`query(identity,cursor,limit)` 默认严格按 issuer + subject + clientId 隔离，按序号升序分页，页大小 1–200；nextCursor 仅在还有本人后续记录时返回。cursor 包含归属校验且查询再次按归属过滤，它不是可跨用户转让的授权凭证。管理查询由宿主扩展独立策略，默认实现没有“管理员查看全部”开关。

本地审计同样持有进程文件锁、私有 ACL、原子替换和容量限制；上限 100000 条/256 MiB，不静默丢弃旧日志。无额外管理前端。宿主必须保护查询接口并从当前认证解析 identity，不接受目标用户参数。

操作引擎与审计仓库是两个独立 SPI，starter 需在自定义、原生以及管理工具的共同调用边界记录尝试/结果，不能只在某种注解路径记录。审计存储不是业务事务的一部分：宿主应在业务执行前确保必要审计可写；执行后审计失败不能触发业务重试，必须保留操作编号并独立补偿。要求事务内审计的项目需在自身事务/outbox 写入，再投递 AuditRepository，不能声称两份本地 JSON 与业务数据库天然原子。

## 验证与交付状态

本次新增真实临时文件、合成回调场景：

- `OperationDurabilityTest`：响应丢失/重启后去重，issuer/subject/client 隔离，修改内容旧确认失效，取消/过期不执行，未知结果不重试、显式恢复，活实例文件锁，执行中重启恢复，成功/失败回执均无法保存时仍不可重跑，两个协调器共享真实文件 store 的并发查询/确认不误伤执行与成功回执，拒绝无副作用与容量限制。
- `AuditPersistenceTest`：持久化分页、员工/客户端隔离、游标不能跨身份使用、同事件重试、满额不静默丢日志。

测试使用临时目录、Java 合成回调和本地文件，不连接任何业务数据库。2026-09-12 完成共享 store 修复后，固定 **Java 17.0.19** 运行 `mvn.cmd -q -f mcp-bridge-core/pom.xml spotless:apply -Dtest=OperationDurabilityTest,AuditPersistenceTest test`；OperationDurabilityTest 9 个、AuditPersistenceTest 2 个场景共 11 个通过，0 失败、0 错误、0 跳过。包含两个协调器共享真实文件的执行中查询/重复确认、成功回执落盘及重启回读，以及成功回执和 UNKNOWN 回执均写失败时的保护，不使用 mock 数据库。此前 8+2 个场景的记录已由本次结果替代。尚未完成真实多进程分布式 store、宿主事务/外部服务联调、断电/损坏介质验证、生产容量与保留策略、发布、部署或真实人员验收。新增代码和文档未提交、推送或发布。
