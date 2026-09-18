# PikminPilot Android 0.2.8-alpha10

這版是針對 **iOS Stage 11.5.4.31 行為重新對齊** 的版本，不再讓 Android 自己另外發明一套判定。

## 這版修正

### 1. 選皮格子：恢復 iOS 的「一次偵測、整輪凍結」

iOS `PikminPilotRunnerUITests.selectPikminGrid()` 的做法是：進選皮頁後先截 **一張乾淨畫面**，偵測 5 欄 × 3 列的位置，接著整次選皮都沿用這組點。

前一版 Android 在第 6 隻前會重新截圖、重新跑 visual-energy detector。這時第一排已經變成 selected 狀態，而第二排如果有飛機、杯子等大型飾品，會改變畫面能量，可能把 slot 6 的中心拉歪。

0.2.8 改回 iOS 語意：

- 選任何一隻之前先偵測 grid
- `PIKMIN GRID FROZEN` 後不再重新偵測
- 第 6 / 11 隻也使用最初那張乾淨畫面算出的 slot
- Android 額外保留 `(N/MAX)` OCR ACK；漏點時重試**同一個 frozen slot**，不重新算座標

### 2. 粉 / 紫 / 白 / 岩圓圈：直接 port iOS magenta-anchor 算法

`ImageAutomationDetector.detectPikminFilter()` 以 **紫色 + 粉色兩個 magenta 圓圈**當 anchor：

- 左 anchor = 紫
- 右 anchor = 粉
- 兩者距離的一半 = 一格 spacing
- 白 = 紫 + 1 格
- 岩 = 粉 + 1 格

前一版 Android 額外把 generic filter-row spacing 混進最後 target，第二輪或不同手機可能漂移。這版移除這個 Android-only blending。

Log：

```text
PIKMIN FILTER TARGET ✅ • type=粉紅 • px=(...) • norm=(...) • detector=iOS-magenta-pair
PIKMIN FILTER TAP • screenshot=(...)/... → display=(...)/... • dispatch=COMPLETED
```

### 3. 已在搬運 / BUSY / COMPLETE：卡片狀態優先

iOS `FruitDetector` 的原則是 **card-first**：只要物品落在 BUSY、COMPLETE 或 partial blocked card，就不能被當 AVAILABLE。

Android 這版保留原本 iOS status-card port，並再加一層保守安全門：如果同欄、同物品附近已有原始 BUSY/COMPLETE border evidence，即使 Android 抗鋸齒讓完整 card rectangle 沒重建成功，也不會讓 OCR 推斷出的冰藍花苗 / 大花苗變成可點目標。

### 4. Screenshot 座標全部映射到 Android display 座標

這版把 cargo、前往探險、Pikmin filter、GO，以及列表/filter-row swipe 都改成 screenshot → display mapping。換不同解析度 / display mode 的手機時，不再假定 screenshot pixel = gesture pixel。

Green X 仍保留 0.2.6 起的 mapped tap + settle/retry。

## 使用方式

1. 先把 Pikmin Bloom 留在「探險」列表。
2. 切回 PikminPilot，設定水果/花苗、Pikmin 類型、數量、輪數。
3. START。
4. 會先等 3 秒讓遊戲回前景，再開始掃描。

## GitHub Build

把整個資料夾放到 GitHub repo 根目錄，執行：

**Actions → Build Android APK → Run workflow**

下載產生的 debug APK artifact。

開 App 後先確認 Log：

```text
BUILD 0.2.8-alpha10
```
