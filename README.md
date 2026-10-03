# 🚘 THTV PRO - Trình Giải Trí Multi-Media & HUD Cảnh Báo Giao Thông Cho Ô Tô (v1.0.13)

[![Build & Release Android APK](https://github.com/ledinhtien219/THTV/actions/workflows/build-apk.yml/badge.svg)](https://github.com/ledinhtien219/THTV/actions/workflows/build-apk.yml)
[![GitHub Release](https://img.shields.io/github/v/release/ledinhtien219/THTV?color=blue)](https://github.com/ledinhtien219/THTV/releases)
[![Android SDK](https://img.shields.io/badge/Android%20SDK-29%2B-brightgreen)](https://developer.android.com)

**THTV PRO** là ứng dụng buồng lái ô tô đa phương tiện và cảnh báo giao thông an toàn cao cấp dành cho **Android Auto** và màn hình xe hơi ô tô (Android Box / Head Unit / Screen Projection).

---

## 🌟 Tính Năng Nổi Bật

### 📺 1. Trình Phát YouTube Không Quảng Cáo & SponsorBlock
* **Chặn quảng cáo tự động (`YouTubeAdBlocker.kt`)**: Lọc chặn cấp mạng (`doubleclick.net`, `googleadservices.com`, `/pagead/`, `/ptracking/`), tự động gia tốc x16 và tắt tiếng khi gặp quảng cáo.
* **Bỏ qua phân đoạn tài trợ (`SponsorBlockManager.kt`)**: Tự động gọi API SponsorBlock để tua qua các đoạn giới thiệu (intro), quảng cáo nhà tài trợ trong video.
* **Chất lượng linh hoạt & Phát ẩn**: Hỗ trợ tùy chỉnh độ phân giải, tự động phát tiếp video, chạy nền âm thanh khi chuyển tab.

### 📺 2. Truyền Hình IPTV & Web Application Hub
* **Tự lưu kênh IPTV gần nhất (`IptvModel.kt`)**: Quản lý danh sách kênh M3U/M3U8 chuẩn HLS, tự động nhớ và phát lại kênh truyền hình vừa xem (VTV1, VTV3, VTV6/VTV-Cần Thơ, HTV, K+...).
* **Trình duyệt WebApp đa năng (`WebAppModel.kt`)**: Tích hợp sẵn các nền tảng giải trí phổ biến như TV360, VTV Go, Zing MP3, Spotify, TikTok, Chrome...

### 🛡️ 3. Trạm Cảnh Báo Giao Thông HUD & Tốc Độ GPS
* **Đồng bộ cảnh báo Vietmap (`VietmapHudOverlay.kt`)**: Đọc thông báo trực tiếp từ Vietmap Live / Vietmap S2, hiển thị tốc độ giới hạn, camera phạt nguội, camera tốc độ với giao diện HUD tương phản cao 100%.
* **Đồng bộ cảnh báo Waze HLP (`WazeHudManager.kt`)**: Kết nối WebSocket thời gian thực nhận dữ liệu kẹt xe, tai nạn, cảnh báo chướng ngại vật từ Waze.
* **Đồng hồ tốc độ GPS thực tế (`GpsSpeedManager.kt`)**: Đo tốc độ di chuyển theo thời gian thực với độ chính xác cao.

### 🎙️ 4. Tìm Kiếm Giọng Nói Tiếng Việt 0ms & Bộ Gõ Telex Ô TÔ
* **Ultra-Low Latency Engine (`VoiceSearchManager.kt`)**: Nhận diện giọng nói tiếng Việt siêu tốc, tự động ngắt câu sau 350ms để trả về kết quả ngay lập tức.
* **Tích hợp phím vô lăng**: Tương thích hoàn toàn với nút bấm giọng nói trên vô lăng (`KEYCODE_VOICE_ASSIST` / `KEYCODE_SEARCH`).
* **Bộ gõ Telex màn hình xe (`VietnameseTelexEngine.kt`)**: Bộ gõ tiếng Việt tối ưu riêng cho thao tác chạm trên màn hình ô tô.

### 🎛️ 5. Giao Diện Buồng Lái Cockpit & Đồng Bộ Thanh Viên Thuốc (Capsule Toolbars)
* **Thanh Capsule viên thuốc đồng bộ**: Dock bên trái và thanh điều hướng dưới được tạo dáng capsule với viền Neon rực rỡ, tự động ẩn sau 10s để giữ không gian hiển thị tối đa.
* **Chế độ Ngày / Đêm thông minh**: Tự động hoặc thủ công chuyển đổi giao diện sáng/tối tối ưu cho tầm nhìn lái xe ban ngày và ban đêm.
* **Lịch Âm Dương & Thời tiết**: Đồng hồ kỹ thuật số kết hợp thời tiết động và lịch âm Việt Nam (`VietnameseLunarHelper.kt`).

### 🔐 6. Quản Lý Bản Quyền Tự Động Qua Telegram Bot
* **Kích hoạt mã thiết bị (`LicenseManager.kt`)**: Hệ thống mã hóa khóa bản quyền bảo mật.
* **Tích hợp Telegram Bot**: Cung cấp kịch bản Google Apps Script tự động duyệt và cấp key kích hoạt từ xa thông qua ứng dụng Telegram.

---

## 📂 Cấu Trúc Mã Nguồn Dự Án

```
THTV PRO/
├── .github/
│   └── workflows/
│       └── build-apk.yml                      # CI/CD tự động build & tạo GitHub Release khi push Tag
├── app/
│   ├── build.gradle.kts                       # Cấu hình v1.0.13 (Build 186), namespace com.carhud.app
│   ├── src/main/
│   │   ├── AndroidManifest.xml                # Khai báo dịch vụ Android Auto & Quyền hạn
│   │   ├── assets/                            # Trình phát IPTV HTML5, Leaflet Map, M3U Playlist
│   │   ├── java/com/carhud/aaproxy/
│   │   │   ├── MainActivity.kt                # Màn hình chính trên điện thoại
│   │   │   ├── SettingsActivity.kt            # Giao diện Cài đặt hệ thống
│   │   │   ├── ActivationActivity.kt          # Màn hình kích hoạt bản quyền thiết bị
│   │   │   ├── CarPresentation.kt             # Render màn hình phụ (Display 2 / Car Screen) & Capsule Toolbar
│   │   │   ├── CarHudAutoService.kt           # Dịch vụ CarAppService cho Android Auto
│   │   │   ├── CarHudAutoScreen.kt            # Xử lý tương tác màn hình Android Auto
│   │   │   ├── CarMediaBrowserService.kt      # Dịch vụ MediaBrowser & MediaSession điều khiển nút vô lăng
│   │   │   ├── CarMediaManager.kt             # Quản lý luồng phát âm thanh & video
│   │   │   ├── CarDashboardView.kt            # Bộ hiển thị các Card Dashboard Cockpit
│   │   │   ├── YouTubeAdBlocker.kt            # Engine chặn quảng cáo YouTube cấp mạng
│   │   │   ├── SponsorBlockManager.kt         # Tích hợp API SponsorBlock tự động tua intro/tài trợ
│   │   │   ├── YouTubePlayerHelper.kt         # Điều khiển trình phát YouTube WebView
│   │   │   ├── IptvModel.kt                   # Quản lý danh sách kênh IPTV M3U & nhớ kênh vừa xem
│   │   │   ├── WebAppModel.kt                 # Quản lý các ứng dụng Web/OTT tích hợp
│   │   │   ├── VietmapHudOverlay.kt           # Bảng cảnh báo tốc độ & camera Vietmap HUD
│   │   │   ├── VietmapNotificationListenerService.kt # Đọc thông báo Vietmap Live/S2
│   │   │   ├── WazeHlpWebSocketManager.kt     # Kết nối WebSocket Waze HLP cảnh báo giao thông
│   │   │   ├── WazeHudManager.kt              # Quản lý hiển thị HUD Waze
│   │   │   ├── VoiceSearchManager.kt          # Engine giọng nói 0ms
│   │   │   ├── VietnameseTelexEngine.kt       # Bộ gõ Telex tiếng Việt
│   │   │   ├── VietnameseLunarHelper.kt       # Tra cứu Lịch Âm Dương
│   │   │   ├── WeatherManager.kt              # Cập nhật thời tiết động
│   │   │   ├── LicenseManager.kt              # Quản lý và xác thực bản quyền
│   │   │   └── AppCrashHandler.kt             # Bộ bắt lỗi crash ứng dụng
│   │   └── res/                               # Drawable, Layout, Layouts HUD, Strings (VI/EN)
├── docs/                                      # Tài liệu tính năng & hình ảnh giao diện
├── telegram_bot_apps_script.js                # Code Google Apps Script cho Bot Telegram cấp key
├── HUONG_DAN_TAO_BOT_TELEGRAM.md             # Hướng dẫn tạo Bot Telegram kích hoạt bản quyền
├── build.gradle.kts
└── settings.gradle.kts                        # Module đơn :app
```

---

## 🛠️ Hướng Dẫn Biên Dịch & Lấy File APK

### 1. Biên Dịch Bằng Gradle Wrapper

Mở Terminal / PowerShell tại thư mục gốc của dự án:

* **Biên dịch bản Release (Khuyên dùng):**
  ```powershell
  .\gradlew.bat assembleRelease
  ```
  **File APK đầu ra:**
  👉 `app/build/outputs/apk/release/THTV_v1.0.13_release.apk`

* **Biên dịch bản Debug:**
  ```powershell
  .\gradlew.bat assembleDebug
  ```
  👉 `app/build/outputs/apk/debug/THTV_v1.0.13_debug.apk`

### 2. Cài Đặt Trực Tiếp Qua ADB

```powershell
adb install -r app/build/outputs/apk/release/THTV_v1.0.13_release.apk
```

---

## 🚀 Quy Trình Tự Động Tạo GitHub Release (CI/CD)

Mỗi khi bạn đẩy một Tag phiên bản mới lên GitHub, GitHub Actions sẽ tự động biên dịch và tạo một bản **Release** kèm sẵn 2 file APK:

```bash
git tag -a v1.0.13 -m "Release THTV PRO v1.0.13"
git push origin v1.0.13
```

Trạng thái biên dịch và danh sách APK phát hành sẽ tự động cập nhật tại mục [Releases](https://github.com/ledinhtien219/THTV/releases).

---

## 📄 Bản Quyền
Dự án được xây dựng và tối ưu riêng cho hệ sinh thái màn hình ô tô Android & Android Auto. Mọi quyền được bảo lưu.
