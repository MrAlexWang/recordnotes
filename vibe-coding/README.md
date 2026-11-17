# vibe-coding · 规则与需求

这个文件夹是「跟 AI 一起改这个工程」的唯一约定来源。改工程前先看这里，改工程时按这里的规则来，改完把结果回写到需求文档。

```
vibe-coding/
  README.md                      本文件：怎么用、怎么改
  rules.md                       工程规则（AI 协作 + 代码约束）
  requirements.md                需求文档（现状 + 需求池 + 验收标准）
  templates/
    rule-template.md             新增一条规则的模板
    requirement-template.md      新增一条需求的模板
```

## 怎么用

**改规则**（比如"以后不要动 index.html"）
1. 打开 `rules.md`，在对应分类下加一行，编号接着往下排（`R-xx`）。
2. 一条规则只写一件事，写清楚"要什么 / 不要什么"。
3. 如果这条规则会改变做法，同步更新 `README.md` 里的构建/验证步骤。

**提需求**（比如"列表要支持按说话人筛选"）
1. 复制 `templates/requirement-template.md` 到 `requirements.md` 的「需求池」，编号 `R-19-0x`（`R-<版本>-<序号>`）。
2. 至少写清：用户故事、验收标准、影响文件、不做什么。
3. 做完后把状态改成 `已完成`，并把"实际改动"补上，留给下一次对照。

**规则和需求冲突时**：以 `rules.md` 为准；如果规则确实过时了，先改规则，再改代码。

## 和 AI 协作时的固定说法

- 「按 `vibe-coding/rules.md` 改」——让 AI 先读规则再动手
- 「这次只做 `requirements.md` 里的 R-19-02」——限定范围，避免顺手改别的
- 「改完按 rules 里的验证清单跑一遍」——构建 + 语法检查 + i18n 覆盖检查
- 「把结果回写到 `requirements.md`」——保持文档和代码同步

## 现状快照

- 版本：v18（全界面中英切换 / 模型可换 / 进度条修复）
- 构建：`build.ps1`（无 Gradle），产物 `dist/RecordNotes.apk`（约 27 MB）
- 网页预览：`tools/make_design.py` → `design/index.html`

详细规则见 [rules.md](rules.md)，需求见 [requirements.md](requirements.md)。
