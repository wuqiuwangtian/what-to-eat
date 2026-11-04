# “吃什么”：GitHub 同类项目与复用建议

调研日期：2026-10-26｜方式：GitHub 检索、仓库 README、目录、许可证与部分关键源码静态核查；未安装或运行第三方项目。

## 1. 结论与检索范围

**有相近项目，可以借鉴交互、候选数据和本地推荐逻辑，无需全部从零开始。** 本轮重点核查 6 个候选，其中 4 个面向饮食选择，另 2 个提供菜谱或推荐研究基础。

尚未找到经本轮核验、同时满足“安卓极简单按钮 + 外卖历史导入 + 长短期饮食画像 + 低成本快速推荐 + 真人对照优于随机”的完整现成方案。这是有限检索的结论，不代表不存在其他项目。

检索覆盖“吃什么 / what to eat / food recommendation / personalized / history”等关键词，并沿 HowToCook 的相关项目链接继续查找。名称相似但仅做菜谱搜索的项目，不视为已解决个人外卖推荐问题。

## 2. 候选总览

| 项目 | 类型与已核查重合点 | 最值得借鉴 | 与本项目的主要差距 | 建议 |
| --- | --- | --- | --- | --- |
| [leecyno1/eatwhat](https://github.com/leecyno1/eatwhat) | Flutter 饮食选择 App；本地菜库、历史偏好加分、近期吃过降权、餐次调整 | 本地候选→过滤→打分→解释；离线回归评测 | 主交互较丰富；未证实有外卖历史导入和真人随机对照；许可证文件缺失 | 优先研究设计与算法，授权明确后再评估代码复用 |
| [ryanuo/whatToEat](https://github.com/ryanuo/whatToEat) | Nuxt 随机/筛选菜品工具，接入菜谱 JSON | 基础候选整理、随机方案、简洁决策体验 | Web 形态；未核实个人历史画像 | 适合作为体验与随机基线参考 |
| [boby4/Meal-Planner](https://github.com/boby4/Meal-Planner) | Next.js H5；随机、AI 条件推荐、“换一道” | 结果卡片、去重、AI 与随机模式拆分 | AI 换选会发起新请求；有积分与账号负担；不是安卓端侧推荐 | 适合借鉴局部流程，不宜原样搬主流程 |
| [AkkoSpace/eat-what](https://github.com/AkkoSpace/eat-what) | Next.js 娱乐推荐；临时/永久不要、点赞点踩、统计 | 区分“今天不想吃”和“长期不喜欢” | README 主要描述随机推荐；未证实历史订单学习；许可证文件缺失 | 产品机制参考 |
| [Anduin2017/HowToCook](https://github.com/Anduin2017/HowToCook) | 社区菜谱与做饭指南 | 基础菜品与食材、做法整理 | 不提供个人外卖历史或实时商家供给 | 候选内容来源之一 |
| [RUCAIBox/RecBole](https://github.com/RUCAIBox/RecBole) | Python/PyTorch 推荐研究框架 | 离线基线、统一数据格式、时序推荐实验 | 不是 Android App；授权说明有待澄清 | 可先研究评测设计，暂不作为已批准产品依赖 |

表中可复用性是本项目的评估意见，不是对第三方效果或质量的认证。

## 3. 关键项目的核查发现

### 3.1 eatwhat：最接近“复杂推荐藏在后台”的现有实现

不仅 README 声称做个性化，在其[推荐服务源码](https://github.com/leecyno1/eatwhat/blob/main/lib/v2/core/services/unified_recommendation_service_v2.dart)中也能看到近期菜品 ID、历史偏好分、跳过/不喜欢信号、餐次调整与最终排序，且可基于打分项组织推荐理由。这为低成本路线提供了可考察的实例。

其[习惯学习服务](https://github.com/leecyno1/eatwhat/blob/main/lib/v2/core/services/v2_meal_habit_learning_service.dart)使用本地存储和累积分数形成方向偏好；这属于可解释的轻量学习，不应把文件名中的 learning 理解为已经训练了深度模型。

值得保留的经验是：先从真实菜库召回，再根据真实特征选择与解释；用近期信号修正长期偏好。需要重新验证的是权重、重复间隔和硬限制处理，不能直接沿用常数就认为适用于个人外卖订单。

该仓库还有[推荐质量评测说明](https://github.com/leecyno1/eatwhat/blob/main/docs/evals/V2_RECOMMENDATION_QUALITY.md)，检查正式菜品身份、限制穿透、重复、多样性与延迟。它适合启发回归检查，但固定场景通过不等于真人采纳率优于随机；本项目仍需要独立用户对照实验。

[README](https://github.com/leecyno1/eatwhat/blob/main/README.md)还将外卖入口未开通、平台资质审批未完成列为自身限制。这说明这个候选没有消除本项目的数据接入不确定性；其外卖下单接入也不等于能够读取用户历史订单。这里仅记录该仓库自述，不外推为平台通用政策。

### 3.2 whatToEat：适合建立便宜、明确的基础方案

[README](https://github.com/ryanuo/whatToEat/blob/main/README.md)描述了随机选择、筛选及菜谱数据来源，属于与核心问题直接相关的决策工具；HowToCook 的[选择指南](https://github.com/Anduin2017/HowToCook/blob/master/tips/如何选择现在吃什么.md)也链接了它。

建议学习菜谱获取与基础选择方式，作为“不接 AI 也能立即给答案”的参考。其网页实现可供概念对照，最终安卓交付和个人画像仍需按本项目要求设计；本轮没有验证其线上服务或用户满意度。

### 3.3 Meal-Planner：可借鉴换选流程，也能看到成本陷阱

[推荐 Hook](https://github.com/boby4/Meal-Planner/blob/main/src/hooks/useRecommendation.ts)将随机推荐与 AI 推荐分开，传入历史名称避免重复，并包含点击间隔控制和 AI 请求取消处理；这些可作为局部实现参考。

但 AI 模式下的“换一道”会重新请求模型，源码中还有推荐前积分扣减。对本项目而言，建议只学习去重、状态和取消的思路，换选改用本地已排好的候选，账号、积分和多个首页入口不纳入默认主流程。源码出现 history 也不能直接视为真实进食历史。

其[README](https://github.com/boby4/Meal-Planner/blob/main/README.md)描述的菜谱查询与 AI 推荐需要网络，还将部分历史相关功能列为预留。复用前需以选定版本源码厘清“展示过”“收藏过”“吃过”三类事件，而不是继承笼统的历史字段。

### 3.4 eat-what：弱负反馈应有不同有效期

[README](https://github.com/AkkoSpace/eat-what/blob/main/README.md)区分当天不再出现与永久不要，并列出评分和行为统计。这与本项目区分“当前不想吃”和“长期排除”很契合。

建议把普通“换一个”继续当作弱信号，把临时屏蔽与长期排除放在可选次级操作里，保持主流程简单。本轮没有进一步验证该项目的推荐训练逻辑，因此不将其“帮助优化算法”的描述当成已有个性化效果证据。

### 3.5 HowToCook 与 RecBole：分别复用内容与研究方法

HowToCook 的[README 与菜谱索引](https://github.com/Anduin2017/HowToCook/blob/master/README.md)适合用于初始菜品整理。应保留数据来源与版本，补充外卖常见餐食、菜品别名和标签；不能据此生成实时价格、商家或配送承诺。

RecBole 的[README](https://github.com/RUCAIBox/RecBole/blob/master/README.md)提供统一推荐研究框架与多类算法。建议先参考其对照与数据组织方法，避免自行搭建整套实验框架；不把 Python/PyTorch 训练栈直接塞进安卓主流程。是否引入其代码，需先处理下节记录的授权说明差异。

## 4. 许可证与维护快照

以下“最近推送”来自仓库 API 的 `pushed_at`，按 UTC 日期记录；它可能包含非默认分支，不等于最近版本发布、持续维护或质量保证。核查时这些仓库均未标为 archived。

| 仓库及状态来源 | 最近推送 | 许可证核查 | 本轮复用处理 |
| --- | --- | --- | --- |
| [leecyno1/eatwhat](https://api.github.com/repos/leecyno1/eatwhat) | 2026-10-20 | README 写 MIT；完整文件树未找到独立 LICENSE，API license 为 null | 先作为设计参考，不列入已确认可直接复制代码的候选 |
| [ryanuo/whatToEat](https://api.github.com/repos/ryanuo/whatToEat) | 2026-10-19 | [MIT](https://github.com/ryanuo/whatToEat/blob/main/LICENSE)，API 与 README 一致 | 进一步评估局部复用 |
| [boby4/Meal-Planner](https://api.github.com/repos/boby4/Meal-Planner) | 2026-07-31 | 已核查 [MIT LICENSE](https://github.com/boby4/Meal-Planner/blob/main/LICENSE) | 局部流程候选；菜谱数据单独核查来源 |
| [AkkoSpace/eat-what](https://api.github.com/repos/AkkoSpace/eat-what) | 2025-06-11 | README 写 MIT；根目录无所链接的 LICENSE，API license 为 null | 先作为交互参考 |
| [Anduin2017/HowToCook](https://api.github.com/repos/Anduin2017/HowToCook) | 2026-10-26 | 已核查 [Unlicense](https://github.com/Anduin2017/HowToCook/blob/master/LICENSE) | 菜谱内容候选，保留来源与具体版本 |
| [RUCAIBox/RecBole](https://api.github.com/repos/RUCAIBox/RecBole) | 2025-02-24 | [LICENSE](https://github.com/RUCAIBox/RecBole/blob/master/LICENSE) 为 MIT，但 README 又写所有数据和代码仅限学术用途 | 标注说明冲突；先借鉴方法，代码使用范围待澄清 |

这里只记录仓库中的许可信息和不一致，未对后续产品的具体使用方式作法律判断。代码、图片、菜谱数据和模型权重应分别记录来源，不能由主仓库许可证替代所有第三方材料的核查。

## 5. 推荐的复用顺序

| 顺序 | 先考察或复用什么 | 仍需自己解决什么 |
| --- | --- | --- |
| 1 | whatToEat 的基础选择流程 + HowToCook 等可核查来源的菜品内容 | 外卖场景候选归一、极简安卓呈现、公平随机基线 |
| 2 | eatwhat 的本地画像、近期降权、分项解释和评测设计 | 授权澄清、真实订单导入、权重与时序更新、真人效果 |
| 3 | Meal-Planner 的局部去重和请求状态处理 | 将换选路径改为本地快速选择，保持无账号也可用 |
| 4 | RecBole 等框架的实验组织方式 | 授权澄清、数据适配；实验证明需要时再评估模型代码 |

建议先按模块借鉴，不直接整仓沿用。现有同类项目已经能帮助省掉基础候选、普通随机和部分工程工作的摸索；本项目最值得投入的部分仍是订单可获得性、低负担反馈与“确实比简单规则更合口味”的证据。

## 6. 下一阶段开始开发前的最小验证

1. 固定候选仓库版本，对许可与直接依赖作一次针对性确认；本轮不复制、安装或执行第三方代码。
2. 用同一组脱敏样本，对比基础随机、借鉴后的轻量规则与拟议画像方案，区分展示历史和进食历史。
3. 检查端侧体积、冷启动、离线可用性和换选延迟，避免继承与本项目无关的支付、社交和管理功能。
4. 再决定局部复用、单独适配还是自行实现；开源候选的存在不替代原始需求验收。
