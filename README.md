# Vocabulary Trainer 桌面版

这是一个由旧 Java 命令行背单词工具重构而来的 JavaFX + SQLite 桌面应用。第一版目标是保证项目可编译、可运行，并让核心复习流程可用：单词 CRUD、旧 txt 导入、相似度反馈、Again/Hard/Good/Easy 自评和间隔重复调度。

## 技术栈

- Java 17+
- JavaFX
- Maven
- SQLite 本地数据库
- JUnit 5

## 主要功能

- Dashboard：显示总词数、今日待复习、今日完成、正确率、已掌握词数和每日目标进度。
- Review：显示英文，输入中文释义，提交后展示正确答案、相似度和 Mock AI 提示，再用 Again/Hard/Good/Easy 自评。
- Add / Import：添加单词，导入旧版分号分隔 txt 文件。
- Word List：搜索、查看、编辑、删除单词，并显示记忆强度、间隔和状态。

## 数据库位置

默认数据库会自动创建在：

```text
%USERPROFILE%\.vocab-trainer\vocab.db
```

首次启动会自动创建 SQLite 表和默认词库。

## 旧文件导入

导入格式保持兼容旧工具：

```text
english;chinese;addedDate;lastReviewed;easiness;interval;consecutiveCorrect
```

日期格式为：

```text
yyyy-MM-dd HH:mm:ss
```

导入时会跳过坏行和重复单词，并在界面中汇总提示；原 txt 文件不会被修改。仓库中的 `gre单词.txt` 可作为导入样例。

## 构建与运行

本机需要先安装 Maven，然后在项目根目录执行：

```bash
mvn test
mvn javafx:run
```


如果当前系统没有把 Maven 加入 PATH，但安装了 IntelliJ IDEA，也可以使用 IntelliJ 自带 Maven，例如：

```powershell
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1.1\plugins\maven\lib\maven3\bin\mvn.cmd' test
```
如果只想打包或验证编译：

```bash
mvn package
```

## AI 配置

当前第一版只接入 `MockAiService`，没有硬编码 API key，也不会依赖外部网络。后续可以在 `AiService` 接口下增加真实 AI 实现，并通过 `OPENAI_API_KEY` 或 `VOCAB_AI_API_KEY` 等环境变量启用。

## 架构

```text
src/main/java/com/vocabtrainer
├─ app          JavaFX 应用入口
├─ domain       WordCard、Deck、ReviewLog、ReviewRating
├─ repository   SQLite 初始化和 CRUD
├─ service      复习调度、相似度、导入、统计、AI 接口
├─ ui           JavaFX 页面
└─ util         日期和路径工具
```

业务逻辑不再依赖 `Scanner`、`System.out` 或 `System.exit()`；UI、服务、数据库和算法已分层。

## 测试覆盖

当前测试覆盖：

- `SimilarityService`
- `ReviewScheduler`
- `ImportExportService`
- SQLite repository CRUD 和复习日志

## 后续 TODO

- 增加 Goals/Achievements 的完整数据表和 UI。
- 增加真实 AI 服务、SQLite AI 缓存和配置页开关。
- 增加 CSV/JSON 导入导出。
- 增强统计图表和作品集展示截图。