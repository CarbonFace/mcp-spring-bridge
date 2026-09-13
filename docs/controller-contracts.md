# Controller 与原生能力接入契约

记录日期：2026-09-12。组织 `com.cogistra`，包 `com.cogistra.mcpbridge`，MIT。本文是公共组件当前接入边界，不代表任何下游业务接口已经自动开放或完成权限改造。

## 注册与调用

`@McpEndpoint` 只扫描 Spring MVC 已登记、显式标注的 Controller 方法。一项声明需要一个明确 HTTP 动作和一个路径；多路径别名、额外 params/headers/custom 映射条件、正则或通配路径、未知可信注入和异步返回会启动失败并指出方法。不会自动把全部 Controller、普通 `@Tool` 或工具服务中的任意方法公开。

每个能力的名称唯一，包括原生工具、HTTP 工具与生成的 `_prepare` / `_submit` 名称。原生资源、资源模板、提示词和补全也检查各自命名空间冲突。注册使用实际 Spring 代理：方法安全、事务、日志等 AOP 仍适用，不绕到 target 对象调用。检测到独立 MCP Server bean 时拒绝启动，避免存在另一条未经过公共控制的入口。

本实现不是完整 MVC 请求重放。HTTP Filter、HandlerInterceptor、ControllerAdvice、自定义 HandlerMethodArgumentResolver 不会因直接调用 Controller 自动执行。依赖这些机制的权限和业务错误判断，必须迁入共享业务层或接入公共扩展后才能标注工具；不能以“HTTP 地址原本受保护”代替 MCP 策略。

## 输入、缺省与清空

| 声明 | MCP 输入与行为 |
|---|---|
| 单一 `@RequestBody` | 工具参数直接是该对象，保留字段缺失与显式 null |
| `@PathVariable`、`@RequestParam` | 按显式名称或编译参数名绑定，保留 required/defaultValue |
| 多个业务参数 | 以参数名分别提供对象或值；不猜不同参数之间的业务映射 |
| `@RequestPart` | 按 part 名绑定；文件提供 `{ "fileId": "..." }`，JSON 部分提供对应对象 |
| `MultipartFile` / `MultipartFile[]` / `List<MultipartFile>` | 从当前用户、当前客户端拥有的有效文件引用建立对象，不接受本地路径或任意远程 URL |
| `Authentication` / `Principal` | 由可信上下文注入，不出现在模型输入中 |
| `HttpServletRequest` / `HttpServletResponse` | 提供受限同步请求上下文与有上限的响应捕获，供已兼容的 AOP/导出使用 |

`@AuthenticationPrincipal`（包括组合注解）、Session/Request attribute、Cookie/Header/Matrix 等未实现的可信绑定，以及未知参数注解和 HTTP JsonView，会被明确拒绝，不会降级成普通 DTO 接受模型伪造。需要时由宿主做显式同步适配入口，仍从已验证的业务认证读取用户。仅有文档、可空、校验或支持的参数绑定注解按对应规则处理。

Schema 与绑定采用宿主 Jackson 属性模型，支持属性命名策略、泛型、可空值及 Bean Validation；Map/集合引用元素可为 null，原始类型数组不可为 null。实际校验保留方法、类和参数声明的 `@Validated` 分组。复杂动态/递归模型使用明确的 MCP DTO 与映射，不靠反射猜业务含义。

框架协议使用单独的标准 Jackson 配置与 RouterFunctionMapping：业务 JSON 的 snake_case 或 Fastjson 转换器不能把 JSON-RPC 的 `inputSchema`、`operationId` 等协议字段改名或二次编码。

## 专用模型与扩展

`@McpEndpoint(input=..., output=...)` 可以指定工具模型。`BridgeContractAdapter` 的 `supports(Method)` 选择它负责的接口，`mapInput` 映射业务字段，`mapOutput` 投影结果。适配器按 Spring 的排序应用，不应产生写入副作用。

输入映射可以查询当前主数据，例如把名称解析成 ID，但写操作准备时会把解析后的参数保存到 `preview.execution`，同时保存原输入到 `preview.arguments`，两者共同纳入确认 hash。执行前重新解析发现不同会拒绝，要求重新准备和确认；真正执行使用已经保存的绑定参数，不能在确认后静默改为另一客户或主体。数值比较保留数值语义，不因 Integer/Long/BigInteger 的表示差异误判改动。

显式输出模型在结果映射后投影并验证；适用于普通结果和文件产物。READ 工具同时声明对应 outputSchema，WRITE 的公开结果是操作回执，其业务结果单独在投影时校验。字符串或数组作为 structuredContent 的 `result` 字段返回。原始 URL 是普通结果，不由框架下载、改写或自动授权。

业务错误采用 HTTP 状态和异常只能覆盖这两种契约。若旧接口用 HTTP 200 加 `ret_code`、`success=false` 等自定义信封表示失败，宿主须在结果适配器检查并抛出稳定错误；不能让公共组件猜每个项目的错误码。

## 权限与可见性

调用身份只能来自 `BridgeIdentityResolver`。它在 HTTP 边界及 SDK 调度线程再次执行，返回当前业务认证；`SecurityContext` 与临时 Servlet 上下文在完成后恢复，不把用户 A 留给下一次用户 B 调用。

配置 scopes/authorities 与注解 scopes 取交集限制。`@PreAuthorize`、`@Secured` 及 JSR-250 的 `@RolesAllowed` / `@PermitAll` / `@DenyAll` 使用实际代理上已启用的 Spring 前置授权拦截器复核，包括宿主的表达式处理器、角色前缀和层级；不会另造一套默认角色判断。声明了对应守卫却未启用时拒绝注册。显式 WRITE 不能被配置改成 READ 来免除确认。opaque ToolCallback 没有可解析的业务方法，必须提供明确策略，不能凭一个 description 获得权限。

首版拒绝方法或继承/组合声明的 `@PreFilter`、`@PostFilter` 和 `@PostAuthorize`：参数过滤可能改变用户确认的快照，结果过滤/后置权限不能自动在历史回执读取时按当前权限重做。依赖这些声明的接口需要使用共享业务层/`BridgeAuthorization` 做可重复验证的对象与字段权限，或提供专门适配入口；不得仅删除原保护注解来通过注册。

`BridgeAuthorization` 负责额外的数据/字段/客户端约束。工具执行、预览、修改、成功回执重放和状态查询均按当前身份核对。资源/模板/提示词/补全用官方参数绑定和真实代理执行方法权限，外层不拿错误的 null URI 参数替代原生授权。

`BridgeDiscoveryPolicy` 只管理元数据发现，返回 ALLOW / DENY / ABSTAIN。任一 DENY 生效；显式 ALLOW 可为参数相关方法提供宿主维护的可见性规则；没有明确决策时，框架评估不依赖业务输入的方法权限，无法安全确定时隐藏。OAuth scope 本身不会跳过角色检查。元数据允许永远不会放宽真正调用的数据权限。

## 写入与文件

WRITE 注册为两个工具：`<name>_prepare` 输入 `{requestId, arguments}`，`<name>_submit` 输入 `{operationId, revision, payloadHash}`。submit 不接受替换正文；修改经 `bridge_operation_revise`，随后重新确认。`bridge_operation_status` 和 `bridge_operation_cancel` 仅允许当前归属，成功/未知操作不能借取消撤销业务效果。

上传可以走认证 multipart HTTP，也可用 begin/append/complete 工具分块。完成得到 fileId 后交给原上传/导入接口；上传暂存与业务导入是不同动作，导入保留业务确认。二进制、Resource、ResponseEntity<byte[]>、同步 HttpServletResponse 输出转为受控文件，继续走输出模型；异步流/未来任务不在本版范围。具体限额与部署见文件文档。

## 兼容验证边界

仅声明当前固定 Spring Boot / Spring AI / MCP SDK 基线。JDK 动态代理的原生注解、响应式返回、异步 Servlet 返回及双向会话参数启动拒绝，不能静默丢掉一部分工具。通过临时文件、真实 Spring 代理、Servlet/JSON-RPC 与独立样例验证；下游数据库事务、业务角色、实际客户端确认和生产部署需要下游各自验收。
