# PikminPilot Android 0.2.9-alpha11

這版是針對 alpha10 的三個回歸修正。

## 主要修正

1. **選皮恢復快速模式**
   - 不再每點一隻就 OCR `N/MAX`。
   - 一張乾淨 screenshot 算好 5×3 格子後直接連點。
   - 如果某個探險最多只能選 5 隻，而設定 6 隻，第 6 下沒被接受也不會報錯；接著直接等 GO。

2. **巨大飾品不再拉歪點擊中心**
   - 不再在每個格子裡追逐 visualScore 最大點。
   - X 永遠使用 5 欄幾何中心。
   - Y 仍會依手機畫面動態偵測三排，但改用 5 欄 trimmed score，單一飛機／杯子／帽子不會主導 row Y。

3. **冰藍花苗「前往探險」改成狀態機**
   - CTA 沒點之前：找 OCR 或綠色框幾何。
   - CTA 一旦送出 tap：不會再回頭報「前往探險未辨識到」。
   - 接下來只等「已進選皮頁」證據：`可以選擇最多`、`飾品/自動`、或紫/粉 magenta pair。

4. **粉紅圓框先辨識、必要時才左滑**
   - 選皮頁剛打開時先直接找目標顏色。
   - 粉紅如果本來就在畫面上，就直接點，不先做多餘左滑。
   - 只有真的找不到時才使用 universal filter-row swipe。

5. **保留 card-first BUSY/COMPLETE 安全排除**
   - 已在搬運的水果／花苗仍會先被 BUSY / COMPLETE / partial-card 排除。

## 建置

GitHub → Actions → **Build Android APK** → Run workflow。

Artifact 內下載 `app-debug.apk`。

## 版本確認

App Log 第一行應看到：

```text
BUILD 0.2.9-alpha11 • fast geometric grid + stateful seedling CTA + direct-first filter + card-first safety
```

## 這版最有用的 Log

```text
SEEDLING CTA TAP SENT ✅
SEEDLING TRANSITION ✅ • selection page • proof=...
PIKMIN FILTER DIRECT ✅
PIKMIN FILTER AFTER SWIPE ✅
PIKMIN GRID STABLE ✅ • geometric centres
PIKMIN TAP 1/6 ...
```
