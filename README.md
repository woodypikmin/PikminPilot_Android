# PikminPilot Android 0.2.3-alpha5-r1

> Repacked verification build. On app open the log must show `BUILD 0.2.3-alpha5-r1`.
> When the selection color row is swiped, the log prints `FILTER ROW SWIPE` with frame and exact pixel coordinates.
> Green-X detection prints every failed scan attempt and prints exact pixel + normalized coordinates when found.

# Pikmin Pilot Android — 0.2.0-alpha2

這版不是重新猜流程，而是依照原 iOS `Stage11_5_4_31_UNIVERSAL_POSTTAIL_TRANSITION_SETTLE` 的核心檔案移植：

- `PilotOptions.swift` → Android 的搬運目標 / 皮克敏種類 / 最低數量規則
- `FruitDetector.swift` → `CargoDetector.java`
  - 水果名稱 OCR 白名單
  - 花苗 `色花苗` 規則，以及 `冰藍花苗` / `大花苗` 例外
  - 明確排除單獨的 `花苗` 分頁文字
  - BUSY / COMPLETE card-first 阻擋
  - OCR 推導低飽和花苗盆栽位置
- `ImageAutomationDetector.swift` → `Detector.java`
  - 前往探險按鈕
  - 紫 / 白 / 粉紅 / 岩濾鏡列
  - GO
  - 搬運頁綠色 X
- `PikminPilotRunnerUITests.swift` → Android Accessibility gesture
  - 五欄 × 三列皮克敏自適應座標
- `Stage8FullLoopController.swift` → `PilotController.java`
  - 探險列表掃描 → 點水果/花苗 → 前往探險 → 左滑顏色列 → 選指定皮 → 選數量 → GO → 綠色 X → 確認回列表 → 下一輪

## UI

主畫面依 iOS `ContentView.swift` 的結構重做：

- Header / readiness
- 搬運次數
- 搬運目標：水果 / 花苗 / 水果＋花苗
- 皮克敏：粉紅 / 白 / 紫 / 岩
- 皮克敏數量 stepper
- 穩定 / 快速
- summary pills
- START PILOT
- 即時狀態與 diagnostics

## GitHub 產 APK

1. 把本資料夾內容放在 GitHub repo 根目錄。
2. GitHub → **Actions** → **Build Android APK** → **Run workflow**。
3. Build 完成後下載 artifact：`PikminPilot-Android-debug`。
4. 內含 `app-debug.apk`。

建立 `v0.2.0-alpha2` 之類的 tag 時，workflow 也會把 APK 掛到 GitHub Release。

## 使用方式

1. 安裝 APK。
2. 開啟 Pikmin Pilot。
3. 點「輔助使用」，啟用 **Pikmin Pilot Automation**。
4. 回 App 選擇搬運目標、皮克敏顏色、數量、搬運次數。
5. 按 **START PILOT**。

## 花苗路徑

花苗點入後不使用藍色圖形猜按鈕。它跟 iOS 11.5.4.19 一樣，用 OCR 尋找「前往探險」文字中心，點擊後再 OCR 確認「可以選擇最多…」選皮頁，確認成功才允許左滑顏色列。

## OCR

Android 使用 Google ML Kit Chinese Text Recognition，對應 iOS Vision OCR 的角色。第一次 build 需要 Gradle 從 Maven 下載依賴。



## 0.2.2-alpha4：START 前景切換等待

START 流程改為配合實際使用方式：先把 Pikmin Bloom 停在「探險」列表，再切回 Pikmin Pilot 按 START。Pilot 會先讓 Pikmin Bloom 回到前景，**固定等待 3 秒**，之後才做第一張截圖與水果／花苗掃描。

這版已移除 START 時額外的「探險頁 precheck/OCR 確認」。也就是不會一切換 App 就立刻判斷畫面；3 秒內完全不做截圖、不點擊、不滑動。3 秒後直接按照使用者已預先開好探險頁的前提開始正常掃描。

## 0.2.1-alpha3：START 與除錯流程

這版修正了 alpha2 最容易造成「按 START 後像沒反應」的兩個問題：

1. 切到 Pikmin Bloom 時不再丟掉 Pilot 的狀態/Log listener；回到 Pilot 可直接看到執行到哪一步。
2. START 後先做 Expedition-list preflight。尚未辨識到「探險」列表前，Pilot **不會送任何 swipe / tap**。若一直無法確認，會明確失敗並提示先把遊戲停在探險列表。

目前正確啟動方式：先開 Pikmin Bloom → 打開「探險」頁並停在三欄水果/花苗列表 → 用最近使用的 App 切回 Pikmin Pilot → 選設定 → START PILOT。Pilot 會切回遊戲、確認列表、開始 OCR/圖像掃描。

正常一輪順序：探險列表 → 找 AVAILABLE 水果/花苗 → 點物品 → 前往探險 → 確認選皮頁 → 顏色列往左滑 → 指定紫/白/粉/岩 → 選指定數量 → GO → 綠色 X → 連續確認回到探險列表 → 下一輪。

## 0.2.3-alpha5：Android 選皮顏色列左滑座標修正

Android 選皮頁不能直接照搬 iOS 的 `activeContentRect * 0.432`。在使用者提供的 912×2048 Android 截圖中，顏色圓圈中心約在 `y=826px`，也就是 `0.404H`；舊值會落在約 `885px`，已經進到第一排皮克敏卡片，因此滑不到顏色列。

現在顏色列固定從螢幕的 `(0.88W, 0.404H)` 向左拖到 `(0.43W, 0.404H)`；重試時也使用同一條顏色列座標。執行紀錄會印出 `FILTER ROW SWIPE` 的實際像素座標，方便再校準不同 Android 機型。
