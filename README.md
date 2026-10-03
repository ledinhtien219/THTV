# T-Car Pro - YouTube, IPTV & Speed HUD for Android Auto (v0.8.142)

Ứng dụng buồng lái ô tô đa phương tiện và cảnh báo an toàn cao cấp dành cho **Android Auto** và màn hình xe hơi ô tô:
- **Tối ưu vị trí thẻ Cấp quyền Giọng nói (Micro) trong Cài đặt**: Di chuyển thẻ Cấp quyền Micro tìm kiếm giọng nói lên đầu nhóm **CARD 3: ĐIỀU KHIỂN MEDIA & PHÍM VÔ LĂNG** cho đúng phân loại và tiện quản lý.
- **Khắc phục triệt để hiện tượng nháy thanh thời gian & tiến trình phát nhạc**: Lọc nhiễu sự cố nhảy thời lượng 0s tạm thời khi chuyển video/buffering, khóa kiểm tra biến đổi văn bản trước khi vẽ lại UI trên Card 2 Dashboard.
- **Tự động nhận diện chuẩn các loại màn hình ô tô thế hệ mới (Ford SYNC 4/4A & VinFast Smart Screen)**: Nhận diện chính xác tỷ lệ hiển thị màn dọc Ford SYNC 4A (Ranger, Everest, Mach-E) và màn ngang VinFast (VF8, VF9, VF6, VF7 15.6" / 12.9" HD).
- **Bộ nhận diện T-Car đẳng cấp**: Biểu tượng 3D kim loại thể thao sang trọng, hỗ trợ Adaptive Icon toàn màn hình điện thoại & xe hơi.
- **Tích hợp phím giọng nói vô lăng theo xe**: Kết nối trực tiếp nút bấm nói trên vô lăng ô tô (`KEYCODE_VOICE_ASSIST` / `KEYCODE_SEARCH` / `MediaSession`) với bộ tìm kiếm giọng nói.
- **Trung tâm giải trí Đa Thẻ (Multi-Cards Dashboard)**: YouTube không quảng cáo, IPTV truyền hình trực tuyến tự động nhớ kênh vừa xem, Đồng hồ tốc độ GPS, Thời tiết động và Lịch số.
- **Bộ chặn quảng cáo YouTube AdBlocker chuyên sâu (`YouTubeAdBlocker.kt`)**: Lọc chặn quảng cáo cấp mạng (`doubleclick`, `pagead`, `ptracking`), tự động tua video quảng cáo và tích hợp SponsorBlock bỏ qua phân đoạn tài trợ.
- **Hai thanh công cụ viên thuốc Capsule đồng bộ (Symmetrical Pill Toolbars)**: Cả 2 thanh công cụ (Dock chuyển app bên trái & Thanh điều hướng dưới) bo tròn viên thuốc 100% đồng bộ, cùng hiển thị khi chạm màn hình và tự động ẩn sau 10 giây.
- **Nút Quay lại (`‹`) hoạt động độc lập**: Chỉ lùi lịch sử trang nội bộ trong ứng dụng hiện tại, không gây chuyển app hay thoát app ngoài ý muốn.
- **Tìm kiếm giọng nói siêu tốc 0ms (Ultra-Low Latency Engine)**: Tự động trả kết quả ngay khi ngắt lời (0ms delay) và chốt câu nói trọn vẹn trong 0.35 giây.
- **Cảnh báo tốc độ & Camera HUD siêu nét (MB2AUTO High-Contrast Standard)**: Nền thẻ tối đậm 100% không bị mờ, viền Neon 3dp rực rỡ, chữ trắng bold nổi bật và cỡ icon lớn.
- **Tự động đặt tên file APK theo phiên bản**: Gradle tự động xuất file APK với tên và phiên bản rõ ràng (ví dụ: `T-Car_v0.8.142_debug.apk`).

---

## 🌟 Tính Năng Nổi Bật Bản Mới Nhất (v0.8.142)

### 1. 🎙️ Di Chuyển Thẻ Cấp Quyền Giọng Nói Vào Nhóm Điều Khiển Media
* **Tối ưu vị trí thiết lập**: Đưa thẻ kiểm tra / cấp quyền Micro tìm kiếm giọng nói lên đầu **CARD 3: ĐIỀU KHIỂN MEDIA & PHÍM VÔ LĂNG** ([SettingsActivity.kt](file:///C:/Users/PC/Documents/antigravity/youtubepro1/youtubepro/app/src/main/java/com/carhud/aaproxy/SettingsActivity.kt#L880)) giúp người dùng dễ dàng cấp quyền tìm kiếm bằng giọng nói ngay khi cài đặt Media.

### 2. 🛡️ Bộ Chặn Quảng Cáo Chuyên Sâu (`YouTubeAdBlocker.kt`) & SponsorBlock
* **Lọc chặn cấp mạng**: Tự động chặn các yêu cầu quảng cáo, banner, pop-up (`doubleclick.net`, `googleadservices.com`, `/pagead/`, `/ptracking/`).
* **Tua quảng cáo tự động**: Gia tốc 16x và tắt tiếng đối với video quảng cáo YouTube.
* **Tích hợp SponsorBlock**: Tự động tua qua các phân đoạn quảng cáo tài trợ trong video.

### 3. 💊 Hai Thanh Công Cụ Viên Thuốc Đồng Bộ (Capsule Pill Toolbars)
* **Giao diện bo tròn viên thuốc 100%**: Thanh Dock bên trái và Thanh điều hướng dưới được tạo dáng capsule viên thuốc chuẩn đẹp với viền 2dp Neon (`#00E5FF` / `#0284C7`).
* **Đồng bộ xuất hiện & Tự ẩn 10s**: Chạm vào bất kỳ điểm nào trên màn hình, cả 2 thanh cùng hiển thị và sẽ tự động mờ dần ẩn đi sau 10 giây không thao tác.
* **Biểu tượng nét cao**: Icon được căn lề chuẩn cân đối, viền bao quanh rõ ràng, sắc nét.

### 4. 📺 Nhớ Kênh TV Gần Nhất & Nút Back Độc Lập
* **Tự lưu kênh IPTV**: Khi xem bất kỳ kênh truyền hình nào (VTV1, VTV3, VTV6, Vĩnh Long...), ứng dụng sẽ lưu lại và **tự động phát đúng kênh đó khi bật lại app**.
* **Nút Back (`‹`) độc lập**: Chỉ thực hiện lùi lịch sử trang trong ứng dụng hiện tại, không gây chuyển app hay đóng app ngoài ý muốn.

### 5. 🎯 Cảnh Báo HUD Tương Phản Cao (Sắc Nét 100%)
* **Chống mờ / Chống chói**: Chuyển màu nền thẻ cảnh báo sang tông tối đậm 100% không xuyên thấu (`#FF0F172A`).
* **Viền 3dp Neon rực rỡ**: Khung bao quanh bằng đường viền 3dp tương ứng từng cấp độ cảnh báo (Đỏ, Cam, Xanh).
* **Cữ chữ & Icon lớn**: Tiêu đề 18sp bold màu trắng tinh, khoảng cách màu Vàng chanh (`#FFFF00`), icon emoji 32sp.

---

## 📂 Cấu Trúc Mã Nguồn Chính

```
youtubepro/
├── app/
│   ├── build.gradle.kts             # Cấu hình v0.8.139 (Build 165), tự động đặt tên file APK
│   ├── src/main/
│   │   ├── AndroidManifest.xml      # Khai báo dịch vụ CarAppService & quyền hạn
│   │   ├── java/com/carhud/aaproxy/
│   │   │   ├── MainActivity.kt               # Giao diện chính điện thoại
│   │   │   ├── CarPresentation.kt            # Render buồng lái, thanh công cụ capsule & xử lý phím vô lăng
│   │   │   ├── CarHudAutoService.kt          # Dịch vụ CarAppService Android Auto (Cảm biến xe & rút cáp USB)
│   │   │   ├── CarHudAutoScreen.kt           # Tiếp nhận sự kiện chạm/cuộn từ màn xe
│   │   │   ├── CarDashboardView.kt           # Bộ hiển thị các Card Dashboard (Tốc độ, Thời tiết, Đồng hồ, YouTube/IPTV)
│   │   │   ├── YouTubeAdBlocker.kt           # Bộ chặn quảng cáo YouTube cấp mạng & SponsorBlock
│   │   │   ├── VoiceSearchManager.kt         # Engine giọng nói 0ms (Tự chốt câu 350ms, phản hồi siêu tốc)
│   │   │   ├── VietmapHudOverlay.kt          # Bảng cảnh báo tốc độ HUD tương phản cao 100% sắc nét
│   │   │   ├── WazeHudManager.kt             # Quản lý cài đặt kiểu HUD, thu phóng, độ mờ, trạng thái khóa
│   │   │   ├── YouTubePlayerHelper.kt        # Bộ điều khiển YouTube, IPTV & bộ lọc quảng cáo
│   │   │   ├── CarMediaManager.kt            # Điều phối phát nhạc/video & phím vô lăng MediaSession
│   │   │   ├── SettingsActivity.kt           # Giao diện Cài Đặt 7 thẻ gập mở logic
│   │   │   └── VietmapStateRepository.kt     # Quản lý luồng dữ liệu tốc độ và cảnh báo
│   │   └── res/                              # Giao diện, biểu tượng xe hơi, vector drawable
├── gradle/
│   └── wrapper/
├── build.gradle.kts
└── settings.gradle.kts                      # Module đơn :app siêu tối ưu
```

---

## 🛠️ Hướng Dẫn Biên Dịch & Lấy File APK

### 1. Lệnh Biên Dịch Qua Terminal / PowerShell
Mở thư mục dự án trong PowerShell hoặc Command Prompt:

* **Biên dịch bản Debug (Khuyên dùng):**
  ```powershell
  .\gradlew.bat assembleDebug
  ```
  **File APK đầu ra có tên phiên bản rõ ràng:**
  👉 `app/build/outputs/apk/debug/T-Car_v0.8.139_debug.apk`

* **Biên dịch bản Release:**
  ```powershell
  .\gradlew.bat assembleRelease
  ```
  👉 `app/build/outputs/apk/release/T-Car_v0.8.139_release.apk`

### 2. Cài Đặt Trực Tiếp Lên Thiết Bị Qua ADB
```powershell
adb install -r app/build/outputs/apk/debug/T-Car_v0.8.139_debug.apk
```

---

## 📄 Bản Quyền & Giấy Phép
Dự án được xây dựng và tối ưu riêng cho hệ sinh thái xe hơi Android Auto. Mọi quyền được bảo lưu.
