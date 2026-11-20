# 设备故障工单摘要系统

本系统供维修员录入和处理设备故障，供主管按设备查找历史记录、比较摘要及核对原文。管理员管理账号及凭据。前端采用 Vue 3 和 Element Plus。业务表单、筛选、弹窗、分页与错误提示按需加载组件，使用简体中文和系统的墨绿主题。后端采用 Spring Boot、MySQL 8.4 和 Jieba Java，摘要计算在本机完成。

## 打开本机实例

已配置本机实例的 Windows 用户可在浏览器打开 [工单系统](http://127.0.0.1:18080)。本机环境使用独立的 MySQL 数据目录和 13316 端口。重新开机后，从本项目目录运行下列命令。首次从源码搭建请参照下文的“构建与验证”和“在其他环境运行”。

```powershell
& '.\本机启动.ps1'
```

本机管理员账号为 `admin`，使用已设置的密码登录。

登录页下方的「管理员测试」「主管测试」「维修员测试」可一键进入对应角色。管理员进入账号管理，主管和维修员进入工单列表。三个测试身份共用独立的演示库，主管可以建立设备，维修员可以在同一库中报障。首次初始化会提供一台示例设备 P-02。退出后可换另一身份查看结果。

管理员先在「账号管理」创建一名维修主管和一名维修员。两名业务用户用各自的初始密码登录并改密。主管在「设备台账」新增设备；维修员随后到工单列表新建报障、开始处理、补录措施并完成。主管从设备历史打开该单，点击任一算法选出的句子，即可看到它在原文中的位置。

## 在其他环境运行

从源码构建后，运行包位于 `backend/target/workorders.jar`，包含前端和算法资源。准备 Java 17 或更高版本，以及一个独立的 MySQL 8.4 数据库和专用数据库账号。数据库字符集用 utf8mb4，给该账号在该库内建表和读写的权限。应用首次启动会建五张表，并在空用户表中创建管理员。

在启动进程的环境中设置以下值。为了避免把凭据写入命令历史，PowerShell 示例使用隐藏输入。

```powershell
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/workorders?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8'
$env:DB_USER = 'workorders'
$env:DB_PASSWORD = [Net.NetworkCredential]::new('',(Read-Host '数据库密码' -AsSecureString)).Password
$env:BOOTSTRAP_ADMIN_USERNAME = 'admin'
$env:BOOTSTRAP_ADMIN_PASSWORD = [Net.NetworkCredential]::new('',(Read-Host '首次管理员密码（8至64字符）' -AsSecureString)).Password
java -jar '.\backend\target\workorders.jar'
```

默认监听 `127.0.0.1:8080`。可通过 `APP_PORT` 和 `APP_ADDRESS` 指定监听端口及地址。账号创建后，重启会沿用已保存的账号和密码。

如需在其他本机实例启用测试按钮，另建名为 `workorders_demo` 的 MySQL 库，为它分配仅访问该库的独立账号，在启动进程中设置 `DEMO_DB_URL`、`DEMO_DB_USER`、`DEMO_DB_PASSWORD`。应用会在此库内建表并初始化三个测试账号。本机回环地址可使用快捷入口，未配置演示库的实例使用普通密码登录。

## 构建与验证

在源码根目录执行以下命令，需要 Node.js 22.12+ 或 24、JDK 17 和 Maven 3.9。本地开发时可运行前端的 `npm run dev`，Vite 将 API 请求代理至后端。前端生产构建先于 Maven 打包，静态资源将一同写入可执行 JAR。

```powershell
Set-Location frontend
npm ci
npm run build
Set-Location ../backend
mvn clean package
```

Maven 测试使用固定语料重算两种算法，报告写入 `backend/target/nlp-evaluation.json`；包含逐条选句、原文定位、事实覆盖和运行耗时。60 条开发记录和 40 条独立测试记录均为自构工单。工单功能的 HTTP 验收位于 `tests/http_acceptance.py`，运行时会在专用测试库创建账号、设备和工单。通过环境变量 WORKORDER_TEST_URL、WORKORDER_TEST_ADMIN、WORKORDER_TEST_PASSWORD 指定独立测试实例。若测试管理员仍处于首次改密状态，还需设置 WORKORDER_TEST_NEW_PASSWORD。设置后运行 `python tests/http_acceptance.py`。

## 源码入口

| 位置 | 内容 |
|---|---|
| frontend/src/views | 登录与改密、账号、设备、工单列表、编辑和双算法对照 |
| backend/src/main/java/cn/workorders | 身份与权限、设备和账号、工单事务、分词、TF-IDF、TextRank |
| backend/src/main/resources/schema.sql | 业务表、索引与外键 |
| backend/src/main/resources/nlp | 固定版本的领域词典、停用词、IDF 及资源校验清单 |
| backend/src/test/resources/nlp | 开发语料、独立评估语料和事实标注 |
