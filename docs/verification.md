# 隔离验证与当前证据

日期：2026-09-12。本文件记录可复现的验证入口与实际结果，区分编译、合成宿主验证及真实客户端验收。全部验证只用临时目录、合成账号/令牌和内存事实，没有业务数据库、生产服务器或真实账号操作。

最终保留的回归报告共 **47 个场景：46 通过、0 失败、0 错误、1 跳过**。由一次 Java 17 总构建（修正失败断言后按模块恢复）及实际修复所需的定向复验组成，没有把重复运行累计成新增场景。10:09 最终制品构建和真实网络样例均成功。

| 模块 | 场景 | 结果 |
|---|---:|---|
| Core 操作/审计 | 11 | 全部通过 |
| 文件生命周期 | 7 | 6 通过；当前 Windows 不允许创建符号链接，1 跳过 |
| Starter、原生和方法权限 | 22 | 全部通过 |
| 可选 OAuth | 6 | 全部通过 |
| 独立真实网络样例 | 1 | 通过 |

## Core 持久化与审计

实际执行：`mvn.cmd -q -f mcp-bridge-core/pom.xml -Dtest=OperationDurabilityTest,AuditPersistenceTest test`。

结果：OperationDurabilityTest 9 个、AuditPersistenceTest 2 个，共 11 个场景通过，0 失败、0 错误、0 跳过。验证了进程重启后的去重/未知恢复、参数精度、取消与旧确认、并发重复、回执保存失败、身份/客户端隔离及审计分页和容量。两个协调器共享同一存储时，状态查询、重试和取消不会把仍在执行的操作误判为未知；恢复由有权判断执行所有权的存储或宿主决定。详见 [操作与审计](operations-audit.md)。

## Servlet 与 MCP 端到端入口

目录：`mcp-bridge-spring-boot-starter/src/test/java/com/cogistra/mcpbridge/integration/`。

入口：`mvn.cmd -q -f mcp-bridge-spring-boot-starter/pom.xml -Dtest=ServletMcpIntegrationTest,StarterDefaultSecurityIntegrationTest,ProtocolNamingIntegrationTest,UnsafeInjectionRegistrationTest test`。

这是 Boot ApplicationContext、MockMvc Servlet 请求、真实 Spring Security 过滤链/方法授权、实际 MCP SDK JSON-RPC 传输、注解发现、业务回调、文件存储、操作存储与审计组成的完整隔离链路。未 mock 业务服务。JwtDecoder 和身份解析 SPI 是刻意提供的合成宿主实现，仅用于确定性测试，不能当作密码学签名或真实 OAuth 客户端验证。

`ServletMcpIntegrationTest` 覆盖八组业务场景：

1. 无认证/无效令牌 HTTP 401、initialize 协议结果、tools/list 按当前 scope 过滤、只读账号不能调用写工具。
2. 普通 HTTP、自定义 MCP 与原生 MCP 查询同一内存事实，Alice/Bob 只能访问本人 ID；resources/read 与 prompts/get 实际执行并受方法权限保护。
3. WRITE prepare 不改变业务，其他用户不能 submit，提交时不能附带另一份正文替换快照；确认执行一次，重复提交不重复写，旧业务版本拒绝。
4. 原生写方法不以原始名称直接暴露；预览修改提高 revision，旧确认失效，新确认只执行新快照。
5. 实际 multipart POST 文件入口、fileId 归属拒绝、绑定原 MultipartFile 方法完成确认导入、HttpServletResponse 二进制导出及认证下载、普通 URL 原样返回。
6. 预备后撤销账号使提交 HTTP 401；连续不同用户的实际 RPC 保持正确身份。另用确定性单线程执行器核验真实 BridgeInvocationContext 在成功/异常后恢复旧 SecurityContext；这个生命周期核验补充实际 HTTP 场景，不替代它。
7. 通过真实 MCP 工具查询审计，小页分页仅返回当前主体、当前客户端的元数据；Bob 不能复用 Alice 游标，审计不保存原始正文和结果。
8. 专用 MCP 输入中的业务别名在预览时解析为明确目标，`preview.arguments` 与 `preview.execution` 同时可见且纳入摘要。别名随后指向其他目标时，原确认以 `BINDING_CHANGED` 拒绝且零写入；重新预览、确认才可修改新目标。映射产生 long 数值并经历持久化后，稳定的数值内容不会因 Jackson 节点类型不同被误拒绝。

另有三个独立宿主场景：

- `StarterDefaultSecurityIntegrationTest`：宿主只依赖 Boot 默认安全设置时，接入组件后原 `/private` 仍返回 401，公共保护资源发现可用，避免组件的局部过滤链使普通宿主接口失去保护。
- `ProtocolNamingIntegrationTest`：宿主设置 SNAKE_CASE 后，业务结果保留 `record_id` 等原有字段名，MCP 协议的 `inputSchema` 与操作的 `operationId`、`payloadHash` 保持标准命名；实际准备和提交链路成功。
- `UnsafeInjectionRegistrationTest`：完整应用注册带 `@AuthenticationPrincipal` 注入的 Controller 工具时拒绝启动，避免可信身份被错误描述成可由模型填入的普通参数。

2026-09-12 实际执行结果：上述四类共 11 个场景通过，0 失败、0 错误、0 跳过。其中 Servlet 主链 8 个，其余三类各 1 个。报告位于该模块 `target/surefire-reports/`；该目录是本地构建产物，不作为版本化发布材料。

本轮真实链路曾发现并修复了方法安全延迟 Advisor 识别、JSON Schema 运行时版本冲突、宿主默认过滤链保护、协议与业务 Jackson 命名隔离、Map 引用值显式 null 清空、持久化映射数值等值比较等问题。前一版“ApplicationContext 尚不能启动”的结果已经被本轮重跑取代。测试验证的是修复后的实际 Servlet 请求与回调行为，不将只编译或反射检查计为通过。

另有 `NativeAuthorizationTest` 3 个场景，覆盖原生工具、资源、提示词及补全经过实际 Spring 安全代理。上述隔离 Servlet 测试使用 MockMvc，不监听网络端口。

### 当前角色与历史回执的最终补强

Java 17.0.19 定向执行 `MethodGuardReceiptIntegrationTest` 1 个场景、`MethodGuardRegistrationTest` 7 个场景，全部通过。实际 RPC 使用宿主 `HOST_` 角色前缀和 ADMIN > EDITOR 层级，覆盖 PreAuthorize、Secured 与 JSR-250。原操作成功后移除角色但保留同一主体/客户端/scope，状态查询、submit 重放、修改预览和重新准备全部拒绝，业务写入数不增加。

启动场景覆盖：Secured/JSR-250 声明未启用、PreFilter/PostFilter 与继承组合的 PostAuthorize 声明、错把 WRITE 的 `_submit` 名称当策略键、两个资源重复使用同一策略键。均拒绝启动，避免安全配置静默失效。证据为 starter `target/method-guard-test-run.log` 与对应 Surefire 报告。

## 真实网络样例与 OAuth

`mcp-bridge-authorization-server` 的 6 个真实 SAS/Servlet 授权场景通过，覆盖登录、PKCE、resource、刷新轮换、撤销、当前账号版本和持久化重启边界。这里“真实”指实际 Spring 授权协议组件，不代表连接真实公司的账号或数据库。

`mcp-bridge-sample` 的 `SampleOAuthMcpIntegrationTest` 启动真实 Tomcat，仅监听随机本地回环端口，并用 JDK HttpClient 完成：HTTP 登录与 CSRF、Alice/Bob 各自 OAuth+PKCE+签名令牌、MCP initialize、两种注解读写、完整预览确认、重复提交不重复修改、旧版本拒绝、multipart 与分块上传、重复分块/完成重放、文件归属、导出下载与删除、资源/提示词、撤销后 401。测试通过并关闭服务器；不将自动 HTTP 表单交互算成人工浏览器视觉验收。

文件模块的 7 个场景中 6 个通过，1 个因当前 Windows 用户无法创建符号链接而跳过。文件流、真实临时目录、额度、重启、分块摘要及归属的验证通过；Linux 符号链接和挂载目录行为仍需 CI/目标环境验证。

## Java 17 构建与制品

2026-09-12 固定 `JAVA_HOME` 为 JDK **17.0.19**，执行 `mvn --batch-mode --no-transfer-progress clean install -Prelease-artifacts`。首轮在 starter 的一个旧错误文案断言处失败，实际不安全注入已被拒绝；修正断言并完成最新身份上下文修复后，从 starter 恢复构建成功，授权模块和真实网络样例也在 Java 17 下运行通过。前两模块的 Java 17 成功结果保留，未为重复计数重跑。源码/Javadoc 附件及本地安装已完成，样例按配置跳过 install。

本地日志：`build-verification.log`、`build-verification-resume.log`，以及各模块 `target/surefire-reports/`。这些构建输出被 Git 忽略；本文保存可复现入口和结论。早期 Java 21.0.11 定向结果不代替本轮 Java 17 基线结果。

最终方法守卫补强后，受影响的原 Servlet 11 场景再次通过（`target/method-guard-existing-chain-recheck.log`，相对 starter 模块）；随后统一格式化，执行 `mvn install -Prelease-artifacts -DskipTests -Dmaven.jar.forceCreation=true`，六个 reactor 项目全部成功。此步只生成制品，未宣称又运行一遍测试。接着单独重新运行 `SampleOAuthMcpIntegrationTest`，真实本地 OAuth/MCP 网络链路在最终依赖上再次通过。日志为根目录 `final-artifacts.log`、`final-network-sample.log`。

检查四个公共模块的运行 JAR 与源码 JAR 均含 `META-INF/LICENSE`，四份 Javadoc JAR 均生成。运行 JAR 没有下游私有业务类。最新组件已安装在本机 Maven 仓库，仅用于本地引用验证；没有远程发布。

## 2026-09-13 宿主确认运行时扩展验证

Java 17.0.19 执行 `mvn.cmd -pl mcp-bridge-spring-boot-starter -am "-Dmaven.jar.forceCreation=true" install` 成功。只覆盖本次受影响 starter 及其 core/files 依赖，未重跑无改动的可选 OAuth 模块与独立样例。合计 45 项通过、1 项 Windows 符号链接场景跳过；其中 starter 28 项全部通过，包含新增宿主运行时集成 4 项及错误注册拒绝 2 项。六项新增场景的详细边界见 [宿主确认运行时](host-confirmation-runtime.md)。

本轮使用合成员工、临时目录与内存业务事实，经实际 MockMvc `/mcp` 请求及原 HTTP 请求验证。准备不创建，提交固定 Controller 与受管方法权限，修改失败先失效，跨身份不可读写，成功重放不重复创建，原 HTTP DTO 仍可用。Spotless 检查通过，最新制品安装到本机 Maven 仓库；没有远程发布。日志为根目录 `target-host-runtime-verify.log` 及各模块 Surefire 报告。本轮没有验证宿主 MySQL 同事务回执，也没有验证真实 GPT 会等待真人确认。

同日复审修正参数转换失败误报 `FORBIDDEN` 的问题，错误参数现在返回固定安全的 `INVALID_ARGUMENT`，真正方法权限拒绝仍返回 `FORBIDDEN`。仅重跑受影响的 `HostConfirmedWriteRuntimeIntegrationTest`、`MethodGuardReceiptIntegrationTest`、`MethodGuardRegistrationTest`、`ServletMcpIntegrationTest`、`NativeAuthorizationTest`，共 24 项全部通过，包含新增一项映射错误及修正恢复场景。格式检查与最终制品本机安装成功，日志 `target-host-runtime-error-verify.log`。两轮覆盖的独立场景共 46 项通过、1 项平台跳过，不把重复执行相加计为新增测试。

## 2026-09-13 服务端 Skill 发现、读取与下载验证

本轮按照用户确认的“项目维护同源 Skill，由 MCP 提供初始发现与读取、客户端可选下载”实现 [服务端业务 Skill](guidance.md)。新增 `BridgeGuidanceProvider`、不可变受限资产、`bridge_get_guidance` 及初始化简短指引；原确认流程保持有效。没有 provider 的项目维持原工具集合和初始化说明；已有 provider 但当前用户没有适用 Skill 时，系统入口仍可发现，目录返回空数组，正文及 ZIP 拒绝访问。

Java 17.0.19 执行：

```shell
mvn.cmd -pl mcp-bridge-spring-boot-starter -am -Dtest=GuidanceMcpIntegrationTest,GuidanceAssetsTest,ServletMcpIntegrationTest,ProtocolNamingIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.jar.forceCreation=true install
```

最终 **13 项通过，0 失败、0 错误、0 跳过**：新 Skill 场景 4 项（实际 Servlet/MCP 场景 2 项、资产边界场景 2 项），原 Servlet 场景 8 项，协议与业务命名隔离 1 项。Spotless 检查通过，最新 JAR 已安装本机 Maven 仓库；日志 `target-guidance-final-verify.log`。实际覆盖如下：

- 身份认证下的 initialize 指引、当前权限目录、完整主文及引用，普通文本响应与 structuredContent 内容一致；不依赖客户端读取结构化字段。
- 同源 ZIP 的完整文件集合、下载 SHA-256 与目录/正文一致、重复导出摘要稳定、其他主体不能下载；未产生业务写入。
- 当前 provider 权限撤销、scope 不足、未发布依赖工具均隐藏对应 Skill；空目录仍可读取，直接猜 ID 获取正文/ZIP 拒绝。
- 未登记引用路径、路径穿越、额外 URL 参数、目录/正文/ZIP 混合参数被拒绝；不读取请求指定的服务器文件或远程资源。
- 非法 ZIP 路径、大小超限、大小写冲突、无效 UTF-8 在注册前拒绝；提供者或调用者修改原字节不改变已登记内容；文件模块关闭时正文仍可读取。
- 不同时区得到相同 ZIP 摘要。首轮该场景发现 JDK 将 `1980-01-01` 当作特殊时间、写入受时区影响的附加时间戳；已改用固定 `2000-01-01T00:00` 并重跑通过。中间一轮只因修正行换行格式未规范化而未通过 verify，最终格式化后的上述完整定向执行成功。

构建产物和本机仓库内 starter JAR 的 SHA-256 均为 `16065D5D56AFAC642927CF69B87377B34A14F05388D3728C5744312009ADF11D`。本轮没有重跑无修改的 OAuth 授权模块、独立网络样例或业务数据库。没有提交、远程发布、部署、真实客户端安装或员工业务验收；不能将这些隔离验证写成 GPT 已自动安装或已证明真人逐笔确认。

## 2026-09-14 标准初始化通知告警修复

用户要求修复交接材料中的公共组件初始化告警。修复基于源码 `29864a2d01e531e9675edc2541038f63ab573763`，在 `codex/fix-mcp-initialized-notification` 分支实施；发送方提交号和既有测试结果仅作定位参考，本段只记录本机新执行的证据。

场景与行为见[无状态初始化通知](configuration.md#无状态初始化通知2026-09-14)。运行入口为 `GuardedStatelessTransport.handleNotification`，回归入口为 `ServletMcpIntegrationTest.initializedNotificationIsAuthenticatedAndDoesNotWarnOrChangeDiscovery`。使用现有合成账号、内存事实和临时目录，经实际 Servlet、安全链与 SDK 完成初始化和通知，不访问任何宿主业务数据库。

本轮新增两个真实 Servlet 场景，防止以下故障：

- 标准通知误报初始化异常：匿名和无效令牌仍返回 401，有效身份在 `initialize → notifications/initialized → tools/list` 中收到 202 空正文，通知前后工具目录相同；标准通知不再产生缺少 handler 告警，未知通知仍产生 SDK 诊断并保持 202 空正文，业务写入为零。
- 请求捕获后账号被撤销，却仍被初始化 no-op 接受：合成宿主在首次成功身份解析后立即撤销账号，通知处理时必须重新核验并拒绝。当前 SDK 0.18.3 将该处理异常映射为 HTTP 500；这与安全链前置拒绝的 401 不同，本修复保留其既有错误处理，不把失败正文误当作成功 202 的空正文契约。该场景同样零业务写入。

2026-09-14，Java 17.0.19、Maven 3.8.8，使用本机原有 Maven 仓库完成：

1. **修复前复现**：只增加标准通知场景，旧运行时代码返回 202 并打印 `Missing handler for notification type: notifications/initialized`，日志断言失败。证据 `target-initialized-before-20260914.log`。
2. **校验顺序反向验证**：临时把 no-op 放到 `identities.resolve(context)` 前，新增撤销场景收到 202 而非预期 500，准确失败；随后恢复正确源码。证据 `target-initialized-auth-order-20260914.log`。
3. **修复后定向回归**：20:00，`ServletMcpIntegrationTest` 10 项、`StarterDefaultSecurityIntegrationTest` 1 项、`ProtocolNamingIntegrationTest` 1 项，共 **12 项通过，0 失败、0 错误、0 跳过**。报告在 starter 的 `target/surefire-reports/`，日志 `target-initialized-final-20260914.log`。中间发现撤销异常原本有 SDK 错误正文，已纠正该新增测试中的空正文误判，未修改运行时错误行为；该轮中间日志为 `target-initialized-sdk-error-body-20260914.log`。
4. **格式与本地安装**：上述 12 项通过后的 verify 阶段仅因新增两行的 LF/CRLF 格式差异失败。执行 Spotless 统一行尾后，没有语义改动；20:01 使用 `-DskipTests -Dmaven.jar.forceCreation=true install` 完成父 POM、core、files、starter 构建、格式检查及本地安装，不将跳过测试的安装命令算作另一轮测试。证据 `target-initialized-format-20260914.log`、`target-initialized-install-20260914.log`。
5. **下游隔离兼容**：20:01，现有下游宿主使用本机新安装依赖，单独执行既有接入测试 **6 项全部通过**，覆盖原授权、当前业务身份、工具发现、文件、确认写入及 Skill；使用合成数据和隔离配置，不加载业务数据库或 Redis。下游仓库的本地日志为 `target/mcp-initialized-downstream-20260914.log`。本轮未改其业务源码，也未重新打包或部署宿主应用。

最终 starter 构建 JAR 与本机 Maven 仓库 JAR 的 SHA-256 均为 `1A67F243B87B909076CBA5669D4C2E415386622A2E419475555217BB32909E07`。版本继续使用 `com.cogistra:mcp-bridge-spring-boot-starter:0.1.0-SNAPSHOT`。这两个 JAR 的一致性证明本机安装内容；下游应用包/镜像内容和线上状态尚未验证。

复现组件定向验证与安装（Java 17，使用自身正常 Maven settings，不切换临时本地仓库）：

```shell
mvn -pl mcp-bridge-spring-boot-starter spotless:apply
mvn -pl mcp-bridge-spring-boot-starter -am -Dtest=ServletMcpIntegrationTest,StarterDefaultSecurityIntegrationTest,ProtocolNamingIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.jar.forceCreation=true install
```

**本次发布状态及 SNAPSHOT 更新方式（2026-09-14 用户确认）**：本机验证完成后，用户明确要求推送远程 GitHub，本次源码交付分支为 [`codex/fix-mcp-initialized-notification`](https://github.com/CarbonFace/mcp-spring-bridge/tree/codex/fix-mcp-initialized-notification)。本次没有合并原发布分支 `codex/mcp-controller-adapter`，也未发布远程 Maven 制品或部署。既有公开基线 `29864a2` 不含本修复，不能把发送方提交号或旧基线当作修复版本。沿用[源码构建分发](releasing.md)：下游机器取得本次修复分支源码后，须记录实际提交号并执行上述 `install`，再重新构建宿主；仅加 `-U` 不能从未发布的远程 Maven 仓库取到本修复，无需删除整个 Maven 缓存或另建发布设施。

新工作区获取入口（已有工作区须先保护未提交内容，不直接覆盖）：

```shell
git clone --branch codex/fix-mcp-initialized-notification --single-branch https://github.com/CarbonFace/mcp-spring-bridge.git
cd mcp-spring-bridge
git rev-parse HEAD
```

将实际提交号与维护者本次交付的修复提交核对，再执行本节的组件安装命令。源码发布与 Maven 远程发布、宿主重新打包和部署分别核验；GitHub 自动验证的实际结果以该提交对应运行记录为准，不使用此前基线的 CI 结果替代。

下游构建时强制重新创建应用 JAR，例如 `mvn -DskipTests -Dmaven.jar.forceCreation=true package`（测试应另按宿主规则完成），再比对 `BOOT-INF/lib` 中组件与本地仓库 JAR 的 SHA-256。部署新包/镜像后，才由真实授权客户端核验 `initialize → notifications/initialized → tools/list` 和对应日志；仅执行握手及只读调用，不构造正式业务数据。异常时按宿主原发布流程回退上一应用版本。本次无需数据库迁移、配置调整或业务数据回滚。

## 尚未完成的验收

- 人工浏览器视觉与辅助功能验收、真实桌面 MCP 客户端完整连接。
- 宿主业务数据库事务回执、外部服务幂等与结果恢复。
- GitHub 上 Linux/Windows、Java 17/21 矩阵的实际执行；生产反代/证书、断电与介质损坏、容量/备份/保留策略。
- GitHub 发布、远程 Maven 发布、部署、真实人员业务验收。

Servlet fixtures 使用 MockMvc，独立样例另外使用真实本地回环 HTTP。没有监听公网端口；所有 `.invalid` 地址仅为合成标识，不发起外部业务请求。
