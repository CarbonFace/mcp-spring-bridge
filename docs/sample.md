# 独立接入示例：个人记录

记录日期：2026-09-12。示例模块 `mcp-bridge-sample` 证明组件可以接入一个没有物流订单、财务表或其他业务系统依赖的 Spring Boot 项目。它仅使用有界内存记录，是开发验证材料，不是生产记录应用。

## 场景

Alice 与 Bob 各有一条合成记录，分别是 `alice-1` / `bob-1`。每条记录包含标题、备注和版本。身份来自 Spring Authentication 的 subject/name，业务服务自己核验记录归属，不接受客户端提交 owner。

开发者在普通 HTTP Controller 的方法上显式添加 `@McpEndpoint`；相同业务还通过 Spring AI 原生 `@McpTool` 接入。两者调用同一个 `SampleRecordService`，共享归属和版本校验；方法权限使用 `@PreAuthorize`。原生资源和提示词也有相同的方法授权声明。

| 业务动作 | 普通 HTTP | MCP 声明 |
|---|---|---|
| 本人身份 | GET `/sample/whoami` | 自定义 `sample_whoami`，READ |
| 本人列表 | GET `/sample/records` | 自定义 `sample_records`，READ |
| 本人详情 | GET `/sample/records/{id}` | 自定义 `sample_record`，READ；原生 `sample_native_record`，READ |
| 稀疏修改 | PATCH `/sample/records/{id}` | 自定义 `sample_patch`，WRITE；原生 `sample_native_patch`，WRITE |
| 文件导入 | POST `/sample/records/import`，multipart 字段 `file` | 自定义 `sample_import`，WRITE，使用框架已上传 fileId 适配 |
| 文件流导出 | GET `/sample/records/{id}/download` | 自定义 `sample_export_file`，READ，原 HTTP 方法写 HttpServletResponse |
| 返回既有下载 URL | GET `/sample/records/{id}/export-url` | 自定义 `sample_export_url`，READ；URL 按原输出保留，不自动访问 |
| 指南 | 无新增 HTTP 业务入口 | 原生 resource `sample://guide` |
| 评审提示词 | 无新增 HTTP 业务入口 | 原生 prompt `sample_review` |

所有读声明需要 `sample:read`，所有写声明需要 `sample:write`。框架之外，业务服务仍按本人归属过滤或拒绝他人 ID。同角色同 scope 不代表共享所有记录。默认没有跨用户管理员绕过。

## 稀疏编辑和可操作失败

普通 HTTP 正文示例：

```json
{"version":1,"changes":{"title":"Revised title","note":null}}
```

MCP 自定义工具参数包含 path 参数与 body 参数：

```json
{"id":"alice-1","patch":{"version":1,"changes":{"title":"Revised title","note":null}}}
```

没有传 title/note 时保留原值；显式 note:null 清空备注；title 不能清空。只接收 title/note，拒绝 owner/id/version 被塞进 changes。每次成功保存版本加一；旧版本返回 VERSION_CONFLICT，客户端需要先重读并重新确认，不静默覆盖。

Alice 读取 Bob ID 与不存在 ID 都得到同一 RECORD_NOT_FOUND；不能通过错误细节枚举他人记录。HTTP 中该错误为 404；版本冲突为 409。示例异常使用安全错误码和可读说明，不返回堆栈。

## 文件

导入使用 UTF-8 JSON 数组，样本在 `mcp-bridge-sample/src/main/resources/sample/import-records.json`。最多 128 KiB、1–20 行、每账号最多 100 条、总计最多 200 条记录。服务器先验证整组，再一次性保存；非法行不会留下前面成功的半批记录。每行只接收 title/note，owner 和 id 由服务器产生。

HTTP 调用实际使用 MultipartFile；MCP 调用先通过公共上传能力获得 fileId，再由框架绑定到同一方法，不能把本机路径当服务器文件。导出 JSON 文件与返回 URL 使用同一本人归属检查。URL 指向受认证保护的 HTTP 下载，不因为知道 URL 就能匿名获取；示例不抓取任意外部 URL。

## 安全启动与账号

基础配置只监听 `127.0.0.1:8091`，MCP 与可选 OAuth 服务器默认关闭。没有默认口令，也不回退生成 Spring 随机口令。未启用 demo 时所有示例入口拒绝访问。

本地演示需显式启用 `demo` profile，并在本机环境变量提供 `MCP_DEMO_ALICE_PASSWORD`、`MCP_DEMO_BOB_PASSWORD`，至少 12 字符且两者不同。值不要写入仓库、命令行参数、聊天或日志。该 profile 提供 HTTP Basic 验证，以及认证模块的 `HostPasswordVerifier` / `HostAccountDirectory` 实现；**同时明确打开 MCP、OAuth 和文件模块**。签名密钥与存储密钥仍必须外置提供，缺少任意必需配置直接启动失败。

完整配置在 `mcp-bridge-sample/src/main/resources/application-demo.yml`，默认只监听 `127.0.0.1:8091`。`MCP_DEMO_PORT` 可同时修改监听端口、issuer、resource 和原 HTTP 下载 URL。`MCP_DEMO_SIGNING_JWK` / `MCP_DEMO_STORAGE_KEY` 必须是外置文件的绝对 `file:` URI。已经注册公开客户端 `sample-desktop`、回调 `http://127.0.0.1:64599/callback`、scope `sample:read` / `sample:write`。标准 literal loopback IP 动态回调端口规则由认证模块处理；客户端 ID 不需要也不存在 client secret。

这里的本地 HTTP 特例只用于 demo。该 profile 不是生产默认认证配置，也不应该被部署到公开服务。

HTTP demo 保留 CSRF。客户端在认证后 GET `/sample/session` 取得自己的 CSRF header/token，同时保留会话 cookie，再调用 PATCH 或 multipart POST。MCP 的可信认证和写入确认由框架管理，不把这份浏览器 CSRF 当 MCP 操作确认。

公共操作文件存放在配置的 `MCP_SAMPLE_STORAGE`（未设时为系统临时目录的 `mcp-bridge-sample`），不使用生产路径。业务记录是内存数据，重启会恢复初始两个样本；操作/审计回执可能仍保留。运行新的独立演示时应选择新的隔离目录，并把“内存事实与持久回执不属于同一事务”作为演示边界，不能把该样例的数据持久性作为生产保证。

## 从干净检出启动

前提：JDK 17 或以上、Maven 3.8 或以上。以下命令都从仓库根目录执行，不依赖外部业务系统或数据库。

先安装本地组件依赖：

```shell
mvn -B -ntp install -DskipTests
```

显式创建一次本地演示密钥。工具位于应用源码集之外，不会随 Spring Boot 启动执行；它只接受一个**尚不存在**的目录，绝不覆盖已有目录或密钥。RSA 签名密钥为 3072 位，存储加密密钥为随机 32 字节。POSIX 上目录/文件分别设为 0700/0600；Windows 上目录继承父目录 ACL，选择仅当前账号可访问的位置。

PowerShell：

```powershell
New-Item -ItemType Directory -Path .local -Force | Out-Null
java mcp-bridge-sample/tools/InitializeDemoKeys.java .local/demo-keys
$sampleKeys = (Resolve-Path .local/demo-keys).Path
$env:MCP_DEMO_SIGNING_JWK = ([Uri](Join-Path $sampleKeys 'signing.jwk')).AbsoluteUri
$env:MCP_DEMO_STORAGE_KEY = ([Uri](Join-Path $sampleKeys 'storage.key')).AbsoluteUri
$env:MCP_SAMPLE_STORAGE = Join-Path (Resolve-Path .local).Path ('demo-run-' + [Guid]::NewGuid())
$env:MCP_DEMO_ALICE_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host 'Alice password (12+ characters)' -AsSecureString)).Password
$env:MCP_DEMO_BOB_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host 'Bob password (different, 12+ characters)' -AsSecureString)).Password
mvn -f mcp-bridge-sample/pom.xml spring-boot:run '-Dspring-boot.run.profiles=demo'
```

Bash（仓库路径不含空格时）：

```bash
mkdir -p .local
java mcp-bridge-sample/tools/InitializeDemoKeys.java .local/demo-keys
export MCP_DEMO_SIGNING_JWK="file:$PWD/.local/demo-keys/signing.jwk"
export MCP_DEMO_STORAGE_KEY="file:$PWD/.local/demo-keys/storage.key"
export MCP_SAMPLE_STORAGE="$(mktemp -d)"
read -r -s -p 'Alice password (12+ characters): ' MCP_DEMO_ALICE_PASSWORD; printf '\n'
read -r -s -p 'Bob password (different, 12+ characters): ' MCP_DEMO_BOB_PASSWORD; printf '\n'
export MCP_DEMO_ALICE_PASSWORD MCP_DEMO_BOB_PASSWORD
mvn -f mcp-bridge-sample/pom.xml spring-boot:run -Dspring-boot.run.profiles=demo
```

第二次启动可以直接复用已生成的密钥，跳过 `InitializeDemoKeys` 命令，并选用新的 `MCP_SAMPLE_STORAGE`。不要为了复用启动步骤删除正在运行进程的目录。停止演示后关闭持有口令的终端；`.local` 已被根 `.gitignore` 排除。密钥工具只输出文件 URI，不输出密钥内容。

## 连接与验收步骤

1. 普通 HTTP：`curl -u alice http://127.0.0.1:8091/sample/records` 交互输入 Alice 口令，应只出现 `alice-1`。改用 Bob 应只出现 `bob-1`。
2. 授权发现：访问 `http://127.0.0.1:8091/.well-known/oauth-authorization-server/mcp-bridge/oauth`；受保护资源发现：`http://127.0.0.1:8091/.well-known/oauth-protected-resource/mcp`。匿名直接调用 `/mcp` 应返回 401 并带资源发现地址。
3. 在支持预注册公开 OAuth 客户端与 S256 PKCE 的 MCP 客户端中配置服务 URL `http://127.0.0.1:8091/mcp`，客户端 ID `sample-desktop`、所需 scope。客户端负责监听其 loopback callback。浏览器以 Alice 或 Bob 登录并授权；不同 scope 会影响可发现与可调用的工具。
4. 调用 `sample_whoami`，再调用 `sample_records` / `sample_record` / `sample_native_record`。Alice 查询 `bob-1` 应被拒绝。调用 `sample_review` 提示词和读取 `sample://guide` 验证原生资源路径。
5. 写操作只有 `_prepare` / `_submit` 两个入口。prepare 参数包含 `requestId` 与 `arguments`，使用上面的稀疏修改例子；prepare 后原记录不变。将完整返回预览展示给用户并取得明确确认，再把该响应的 `operationId`、`revision`、`payloadHash` 原样交给 submit。重复 submit 不再增加版本；修改参数必须重新准备和确认。
6. 上传样例 JSON 可用 HTTP multipart `/mcp-bridge/files`，也可用 `bridge_file_begin` → `bridge_file_append` → `bridge_file_complete`。文件 ID 放入 `sample_import_prepare` 的 `arguments.file.fileId`。导入确认成功才创建本人的新记录；文件上传本身不执行业务导入。
7. `sample_export_file` 返回认证下载产物；用同一客户端的有效 token 下载。匿名访问返回 401，Bob 访问 Alice 的产物返回 404。`sample_export_url` 保留原 HTTP 下载 URL，该地址仍由 HTTP demo 的 Basic/CSRF 安全链保护，不会被框架代为抓取。

示例文件上限 128 KiB；每个主体文件配额 1 MiB、全局 8 MiB、每块 64 KiB；产物默认 24 小时到期、未完成上传默认 1 小时到期。文件和上传会话还绑定授权客户端，换客户端不能取用旧客户端的 fileId。OAuth 时长沿用组件默认 24 小时 / 7 天 / 30 天。调整配置需重启。

## 独立跨模块测试

本地安装最新公共组件后运行：

```shell
mvn -B -ntp -f mcp-bridge-sample/pom.xml -Dtest=SampleOAuthMcpIntegrationTest test
```

测试直接启动实际嵌入式 Tomcat，在临时可用端口用 JDK HttpClient 请求页面、表单、OAuth 和 MCP；不替换 JwtDecoder、身份 resolver、密码 verifier 或业务服务。口令和 RSA/AES 密钥在隔离测试中生成，状态文件位于 JUnit 临时目录，测试结束关闭应用并清理目录。scope、用户归属、业务版本、文件 ID 与撤销检查都经过实际 HTTP 入口。

## 验证要求与当前状态

需要完成的真实隔离链路：Alice/Bob 登录与跨 ID 拒绝；HTTP / 两种 MCP 声明的同一读写规则；prepare 后数据不变、执行确认后同一快照修改；旧业务版本拒绝；重复确认不重复修改；fileId 归属、实际导入及流式导出；resource/prompt 方法权限；撤权后已保存操作和回执仍重新授权。

2026-09-12 补充：已实现完整 demo 启动配置、显式密钥初始化命令、本人身份工具和 `SampleOAuthMcpIntegrationTest`。早期“demo 只提供 HTTP、OAuth 需另行配置”的说明已被上述完整 profile 替代。

2026-09-12 实际执行结果：`mvn -B -ntp -f mcp-bridge-sample/pom.xml -Dtest=SampleOAuthMcpIntegrationTest test` 成功，**1 个跨模块完整场景，0 失败、0 错误、0 跳过**。实际启动 Tomcat 并由 JDK HttpClient 通过 loopback HTTP 完成：普通 HTTP Basic/CSRF；Alice/Bob 密码登录、授权同意、S256 PKCE 换真实签名 JWT；MCP initialize/whoami/scope 列表过滤；两类注解的本人读写和跨用户拒绝；prepare 不修改、重复 submit 不重复修改、旧业务版本拒绝；HTTP multipart 上传后导入、MCP 分块上传及重复完成、认证流导出/下载/删除；原生 resource/prompt；令牌撤销后拒绝继续访问。该场景没有使用 MockMvc，也没有 mock 身份、JWT decoder 或业务服务。

首次调试曾在换得令牌后立即重用同一授权码；SAS 按防重放规则吊销该授权已发令牌，因此后续 initialize 返回 401。已纠正验收顺序，日常完整链路不重用授权码；没有为让测试通过放宽认证实现。

密钥初始化命令也已在真实临时目录执行：首次生成成功，第二次使用相同目录明确失败，既有两份文件的散列均未改变；该临时目录随后已清理。Java 编译、独立网络测试、密钥命令验证属于本地证据，尚未完成外部桌面 MCP 客户端的浏览器交互验收或 Linux 文件权限验证。所有数据均为内存合成数据，没有业务库连接、迁移、部署、提交或发布。
