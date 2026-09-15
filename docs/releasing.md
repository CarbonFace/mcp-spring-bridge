# GitHub 与 Maven 发布准备

## 当前源码交付入口（2026-09-16）

用户要求公共组件通过 `master` 提供给其他接入者。本仓库公开，默认交付分支为 [`master`](https://github.com/CarbonFace/mcp-spring-bridge/tree/master)。2026-09-16 源码交付新增[动态不可变知识快照与指定版本读取](guidance.md)，并保留已验证的初始化通知修复 `f66a7c410ed0ec45ac41192944bd6e53c8c5356a`。本轮开发分支为 `codex/knowledge-runtime-guidance`，基线 `df698bd`；推送前重新获取远程，默认分支未发生漂移。增量先交付功能分支，再快进默认 `master`；旧开发分支与完整历史保留，不强推、不删除。普通克隆无需指定功能分支。

```shell
git clone https://github.com/CarbonFace/mcp-spring-bridge.git
cd mcp-spring-bridge
git rev-parse HEAD
mvn install
```

使用 Java 17 或 21、Maven 3.8.8 或更高版本，记录实际源码提交号；`install` 执行隔离验证并将组件安装到当前构建机器的 Maven 仓库。其他构建机器也须安装同一份源码。已有旧分支工作区须先保护本地改动，再获取并显式切换到 `master`；仅在旧分支运行 `pull` 不会切换交付入口。

当前仍为 `com.cogistra:mcp-bridge-spring-boot-starter:0.1.0-SNAPSHOT`，**未发布远程 Maven 制品**。这意味着源码已可供其他 Spring Boot 项目构建接入，但还不能只填写依赖坐标就从 Maven Central 下载。宿主仍需配置真实身份验证和业务权限；组件的隔离验证不替代宿主的真实环境验收。下游更新同名 SNAPSHOT 后必须重新打包并部署应用才会在运行环境生效。

动态指引增量在 Java 17.0.19 上已有 20 项定向验证及本机安装证据，见[2026-09-15 验证记录](verification.md#2026-09-15-动态知识快照与指定版本读取)。本次交付没有再改变这些 Java 源码，远程基线也未漂移，因此复用该验证，不将再次提交和推送计作新一轮测试。GitHub 会对本次提交运行 Java 17/21 × Ubuntu/Windows 隔离验证，实际结果应查看对应提交，不能沿用旧提交的绿色状态。此次没有发布 Maven 制品、部署服务或操作宿主业务数据库。

## 历史发布记录

2026-09-14：初始化通知修复已交付默认 `master`；修复提交的 [GitHub 验证](https://github.com/CarbonFace/mcp-spring-bridge/actions/runs/34843607883) 在 Java 17/21 × Ubuntu/Windows 四个任务全部通过。它是历史修复的验证记录，不代表 2026-09-16 增量的 CI 结果。

**2026-09-13 发布调整（分支入口已由上方 2026-09-14 规则替代）：用户已授权推送到其 GitHub，源码仓库为 `https://github.com/CarbonFace/mcp-spring-bridge`，保持 Cogistra / MIT 归属。初始私有，随后按用户要求改为公开，方便同事获取源码构建；已通过不带认证的 GitHub API 请求验证可访问且 `private=false`。当时发布分支为 `codex/mcp-controller-adapter`，首次代码提交 `c037bf575f6460abcbd88e1caeebcf5b2d44b034` 已推送并核对远程一致。Maven 制品未发布，获取源码后仍须先执行本地 install。**

记录日期：2026-09-12。用户确认：公共组件由 Cogistra 维护，Maven 组织标识为 `com.cogistra`，Java 包为 `com.cogistra.mcpbridge`，采用 MIT 许可证。仓库计划由用户后续上传 GitHub。

## 已准备的内容

- 独立 Maven 多模块工程，不读取其他公司的代码库、账户、数据库或本地环境文件。
- 根目录 `LICENSE` 标明 `Copyright (c) 2026 Cogistra`；POM 同步 MIT 信息。
- `README.md`、`CONTRIBUTING.md`、`SECURITY.md`、变更记录、独立示例与接入文档。
- `.github/workflows/verify.yml`：GitHub 收到 push/PR 后执行 Java 17/21、Linux/Windows 隔离构建。这里提供的是工作流定义，本地验证不能代替 GitHub 实际运行结果。
- 发布附加包配置生成源代码与 Javadoc；不会在普通构建中上传任何仓库。
- 本地 Java 17 构建已生成四个公共模块的运行包、源码包和 Javadoc 包；已检查运行包与源码包的 `META-INF/LICENSE`，运行包没有下游私有业务类。可复现证据见 [验证记录](verification.md)。

## 首次上传 GitHub 的历史准备流程

以下为首次发布前的步骤，仓库现已公开；当前获取入口以上方 `master` 说明为准，不需要重新创建仓库。

1. 由 Cogistra 创建目标 GitHub 仓库，确定真实组织名与仓库地址。本文不猜测 GitHub 账号或地址。
2. 在本地审阅 `git status` 和忽略规则。仅纳入源码、文档、构建配置；`.local`、`target`、账户口令、签名/加密密钥不得上传。
3. 执行 `mvn verify`，审阅实际结果及记录的未验证项，再提交代码并绑定目标远程仓库。当前未代为提交或推送。
4. 在 GitHub 配置受保护分支、私密安全报告入口和维护者权限；核对 CI 实际通过后再创建版本标签。

## 对外提供 Maven 依赖

上传 GitHub 与发布 Maven 构件是两个步骤。尚未发布时，其他项目可先克隆源码并运行 `mvn install`，然后引用 `com.cogistra:mcp-bridge-spring-boot-starter:0.1.0-SNAPSHOT`；不能声称该坐标已经存在于 Maven Central。

若发布 Maven Central，先为 `com.cogistra` 完成命名空间验证；反向域名使用权和 GitHub 仓库创建是不同的验证事项。再填写真实项目 URL、SCM、开发者资料和签名/发布凭据，按选定渠道设置仅发布时启用的 Maven 配置。参照 [Central 命名空间验证](https://central.sonatype.org/register/namespace/) 与 [构件要求](https://central.sonatype.org/publish/requirements/)。

当前 `release-artifacts` profile 只生成源码和 Javadoc 附件，**不会上传**。示例应用默认跳过 install/deploy，只作为源码和验收入口。实际发布前必须补齐真实 SCM/项目信息并选择发布渠道，不在仓库写入发布令牌或私钥。

本地重复安装同一 SNAPSHOT 后，下游的增量 `package` 可能沿用旧应用包。验证接入时应强制重建下游 jar（例如 `mvn -Dmaven.jar.forceCreation=true -DskipTests package`），再比对应用包内 `BOOT-INF/lib` 的组件与当前本地仓库制品；不能仅根据打包命令返回成功判断新依赖已进入交付包。正式发布应使用可追踪的固定版本。

## 首次发布执行记录（2026-09-13）

2026-09-12 的未发布状态为历史记录。2026-09-13 用户批准改为上传本人 GitHub，已建立上述私有源码仓库；本次先核验全工程，再提交推送。Maven 构件仍未远程发布，没有部署服务或对正式/旧系统数据库写入。

2026-09-13 22:29（Asia/Shanghai），Java 17 执行 `mvn --batch-mode --no-transfer-progress verify` 成功：core 11、files 6、starter 33、authorization-server 6、sample 1，共 57 项通过，files 另有 1 项 Windows 符号链接权限跳过，失败和错误为 0；各模块格式检查通过。日志 `target-prepublish-20260913.log`。starter 与此前已安装并由宿主验证的 SHA-256 均为 `16065D5D56AFAC642927CF69B87377B34A14F05388D3728C5744312009ADF11D`。GitHub 工作流仅构建和隔离验证，不部署或发布 Maven 制品。

首次代码提交的 [GitHub 验证](https://github.com/CarbonFace/mcp-spring-bridge/actions/runs/34763064421) 已完成，Java 17/21 × Ubuntu/Windows 四个任务全部成功。该结果只证明公共工程的隔离构建和验证；没有部署匠心服务、发布 Maven Central 制品或验证真实业务数据库。此后仅补充本段发布记录，不重复执行代码未变的 CI。
