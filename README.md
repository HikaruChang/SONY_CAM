# BraviaCam Guard (`com.sonycam.guard`)

> **Powered by NyphexAI, Developed by Hikaru(i@rua.moe)**

專為 **Sony BRAVIA Android TV (Android 14 / SDK 34)** 與 **Sony BRAVIA CAM (`CMU-BC1`, USB VID:PID `054c:0e4e`)** 設計的自建區網監控、Camera/麥克風存取資安監管與系統禮讓守護 APK。

在純區網、零雲端上傳的前提下，提供標準 MJPEG / Snapshot / 可選 WAV 雙聲道麥克風串流、Synology Surveillance Station 原生對接，並透過主動哨兵監管與禮讓機制，避免背景惡意竊視同時**絕不與 Sony 系統功能搶奪鏡頭使用權**。

---

## 核心特色

1. **三種智慧守衛與禮讓模式**
   - **`COOPERATIVE`（智慧禮讓常駐，預設）**：持續提供本地監控與資安保護；當系統或其他前台應用程式需要鏡頭、或開啟「觀影自動讓位給系統」且系統媒體正在播放時，自動讓出 `CameraDevice` 給系統功能使用，結束後自動恢復。
   - **`ON_DEMAND`（按需喚醒零佔用）**：平時完全釋放 Camera 硬體（`IDLE` 零佔用），僅在 HTTP 串流 / 快照請求到達時喚醒 `CMU-BC1`，所有連線結束閒置約 10 秒後自動關閉鏡頭，最大化確保 Sony 原生功能隨時可用。
   - **`WATCHDOG_ONLY`（純哨兵監管）**：不主動開啟串流鏡頭，僅以哨兵監控全機 Camera/麥克風外部佔用事件並記錄審計日誌。
2. **Camera & 麥克風資安監管（Watchdog + Audit Log）**
   - 透過 Android `CameraManager.AvailabilityCallback` 與音訊擷取回呼即時偵測異常或未預期佔用，保留完整時間軸審計日誌（`/api/audit`）。
   - 麥克風收音預設關閉（`micEnabled: false`），可視需要於 TV UI 或 Web Console 動態啟用 USB-Audio 16kHz 16-bit PCM 雙聲道串流（`/audio.wav`）。
3. **開機自動啟動支援（Android TV 14 Cold Boot）**
   - 結合 `BootReceiver` 與 `BootTrampolineActivity`（配合 `SYSTEM_ALERT_WINDOW` / 電池最佳化豁免設定），冷開機後自動拉起前台守護服務與 HTTP Server。
4. **純標準 Java + Android SDK 無 Gradle 依賴快速編譯**
   - 僅依賴 JDK 11/21 與 Android SDK `build-tools` + `android-34`，數秒內完成資源編譯、DEX 轉換、對齊與 APK 簽章驗證。

---

## 實測硬體與環境規格

| 項目 | 規格 / 數值 |
| --- | --- |
| **目標電視** | Sony `BRAVIA 4K AE2` (`BRAVIA_AE2`), Android 14 (API 34) |
| **外接鏡頭** | Sony `CMU-BC1` (`CMU-BC1M Multifunction Device`, `VID:PID 054c:0e4e`) |
| **Camera2 ID** | `112` (`LENS_FACING_EXTERNAL`, `YUV_420_888`) |
| **支援解析度** | `320x240`, `640x480`, `848x480`, `1280x720`, `1920x1080`（最高 30fps） |
| **音訊輸入** | USB-Audio `16000 Hz`, 16-bit PCM Stereo (`card=4, device=0`) |
| **預設服務埠** | HTTP `8943`（僅限區網與 Token 驗證存取） |

---

## HTTP 端點與驗證方式

所有保護端點（除登入表單外）均支援以下任一驗證方式：
- Query Parameter: `?token=<ACCESS_TOKEN>`
- HTTP Header: `Authorization: Bearer <ACCESS_TOKEN>`
- HTTP Basic Auth: `Authorization: Basic ...`（相容 **Synology Surveillance Station `CURLAUTH_ANY`**，密碼或使用者名稱填入 `<ACCESS_TOKEN>` 皆可，亦完整支援 `HEAD` 探測請求）
- 登入後的 Web Session Cookie

| 端點 | 方法 | 說明 |
| --- | --- | --- |
| `/` | `GET` | 存取驗證登入頁 / Web 控制與即時監看儀表板 |
| `/stream.mjpg` | `GET` / `HEAD` | 即時 MJPEG 視訊串流（`multipart/x-mixed-replace`） |
| `/snapshot.jpg` | `GET` / `HEAD` | 單張最新 JPEG 畫面快照 |
| `/motion/latest.jpg` | `GET` | 最近一次移動偵測觸發快照 |
| `/audio.wav` | `GET` / `HEAD` | 即時 USB 麥克風 16kHz Stereo WAV 音訊串流（需先開啟麥克風開關） |
| `/api/status` | `GET` | 系統狀態、目前模式、解析度/FPS、佔用者與音量電平 JSON |
| `/api/audit` | `GET` | 資安監管與系統禮讓審計日誌 JSON |
| `/api/control` | `POST` | 動態切換模式（`cooperative` / `ondemand` / `watchdog`）、解析度/FPS、麥克風開關、觀影讓位與開機自啟設定 |

> 存取金鑰（Token）可在電視端 **BraviaCam Guard** App 畫面直接檢視與重新產生。

---

## Synology Surveillance Station 設定方式

在 Surveillance Station「網路攝影機」新增裝置時選擇手動設定：

1. **品牌**：`[使用者自訂]`
2. **影像格式**：`MJPEG`
3. **類型**：`串流 - HTTP`
4. **路徑**：`stream.mjpg`（或搭配埠號後填入完整位址）
5. **IP 位址 / 埠號**：填入電視區網 IP 與 `8943`
6. **使用者名稱 / 密碼**：使用者名稱任意（例如 `admin`），密碼填入 **BraviaCam Guard App 顯示的 Access Token**
7. 若需要 **零背景佔用**，請將守護模式切換為 **按需喚醒（`ON_DEMAND`）**，Surveillance Station 連線時會自動喚醒鏡頭、斷線 10 秒後自動釋放。

---

## 本地編譯與部署

### 環境需求
- JDK 11+（本機 macOS 使用 Android Studio JBR / OpenJDK 21；CI 使用 Temurin 21）
- Android SDK：`platforms/android-34` 與 `build-tools`（如 `35.0.0` / `36.0.0`）
- `zip`、`adb`（部署至電視時需要）

### 編譯已簽章 APK
```bash
bash scripts/build.sh
```
編譯完成後會產生並自動驗證：
- `build/sonycam-guard.apk`

### 透過 ADB 安裝到 Sony BRAVIA TV
```bash
adb connect <TV_IP>:<ADB_PORT>
adb install -r build/sonycam-guard.apk
adb shell am start -n com.sonycam.guard/.MainActivity
```
首次啟用請在電視畫面確認 **Camera** 權限，並依需要授與 **麥克風（`RECORD_AUDIO`）** 與 **顯示於其他應用程式上層（`SYSTEM_ALERT_WINDOW` / 電池最佳化豁免）** 以支援開機自動啟動。

---

## GitHub Actions 自動發版（Auto Release）

本專案包含 [.github/workflows/release.yml](.github/workflows/release.yml)：
- 當推送 `v*` 格式的 Git Tag（例如 `git tag v1.3.0 && git push origin v1.3.0`）或於 GitHub Actions 手動觸發 `workflow_dispatch` 時，會自動於 `ubuntu-latest` 編譯、簽章與驗證 APK。
- 發佈至 GitHub Releases 並自動附上三個檔案：
  - `sonycam-guard.apk`
  - `sonycam-guard-<tag>.apk`
  - `SHA256SUMS.txt`

> 如需在不同 CI 發版間保持同一組 APK 簽章金鑰以利直接覆蓋升級，可於 Repository Settings -> Secrets 新增 `SIGNING_KEYSTORE_BASE64`（為 `keystore/sonycam.jks` 的 Base64 編碼，密碼與 alias 沿用 `sonycamguard` / `sonycam`）；未設定時 CI 會自動產生臨時金鑰完成簽章發佈。

---

**Powered by NyphexAI, Developed by Hikaru(i@rua.moe)**
