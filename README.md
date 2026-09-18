# PikminPilot Android — Stage 11.5.4.31 Android Alpha

這是依照你提供的 **PikminPilot Stage 11.5.4.31 iOS 專案**做的 Android 原生移植版。

## 現在已經有的東西

- Android 原生 App（Java，Android 11 / API 30 起）
- `AccessibilityService` 直接點擊與滑動
- `AccessibilityService.takeScreenshot()` 畫面擷取
- Active content / viewport 幾何偵測
- 「前往探險」按鈕偵測
- 紫 / 白 / 粉紅 / 岩石皮克敏 filter 偵測
- 自適應 5 欄 × 3 列 Pikmin 選擇格
- GO 按鈕偵測
- 搬運畫面綠色 X 偵測、點擊、二次驗證
- 多輪自動化 controller
- GitHub Actions 自動產生 APK

## 與 iOS 11.5.4.31 的主要差異

iOS 版在探險清單使用 Apple Vision OCR，再配合 BUSY / COMPLETE 卡片框線判斷，才把水果或花苗視為可點擊。

這個 Android 第一版目前把 iOS 原本的 **顏色 + connected-component 幾何**移植過來當 cargo fallback，所以清單辨識還沒有做到 iOS 那麼保守。換句話說：**critical tail（filter → N 隻 → GO → 綠 X）移植度較高；探險清單的水果/花苗安全判斷目前是 alpha。**

第一次實機使用請先用少量派遣次數測試。

## 直接用 GitHub 產 APK

這個資料夾本身就是 repo root。

1. 在 GitHub 建一個空 repo。
2. 把這個資料夾全部上傳 / push 到 repo root。
3. 進入 **Actions → Build Android APK → Run workflow**。
4. Build 完後，在該次 workflow 的 **Artifacts** 下載 `PikminPilot-Android-debug`。
5. 裡面就是可安裝的 `app-debug.apk`。

如果 push `v0.1.0-alpha1` 之類的 tag，workflow 也會把 debug-signed、可直接安裝的 APK 掛到 GitHub Release。

## Android 安裝後怎麼用

1. 安裝 APK。
2. 開啟 Pikmin Pilot。
3. 按「開啟輔助使用設定」。
4. 啟用 **Pikmin Pilot Automation**。
5. 回 App 選皮克敏種類、數量與派遣次數。
6. 按「開始 Pilot」。
7. App 會開啟 `com.nianticlabs.pikmin`，之後由 Accessibility Service 持續截圖與操作。

## 為什麼 Android 版不需要 iOS 那堆東西

Android 這條路不需要：DDI、CoreDevice、RSD、DTX、XCTest Runner、pairing record、VPN tunnel 或 Apple signing。Android 11+ 的 AccessibilityService 已經可以直接取得 screenshot 並 dispatch gesture。

## 下一個應該補的功能

把 `FruitDetector.swift` 裡的 OCR + BUSY/COMPLETE status-card 安全閘完整移植到 Android（例如 Android OCR/ML Kit），再用你的 Android 實機 screenshot 校正 thresholds。

## Build 基準

- Android Gradle Plugin: `8.13.2`
- Gradle: `8.13`
- compileSdk: `36`
- minSdk: `30`
- Java: `17`
