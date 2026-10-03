# HƯỚNG DẪN 2 PHÚT THIẾT LẬP BOT TELEGRAM DUYỆT BẢN QUYỀN T-CAR PRO

Hệ thống hoạt động hoàn toàn tự động, **miễn phí 100% vĩnh viễn**, lưu trữ trên Google Sheet và thông báo qua Telegram cá nhân của bạn.

---

### BƯỚC 1: TẠO BOT TELEGRAM (MẤT 30 GIÂY)
1. Mở ứng dụng **Telegram** trên điện thoại hoặc máy tính.
2. Tìm kiếm bot chính thức: **`@BotFather`** (có dấu tích xanh).
3. Bấm `Start` và gửi tin nhắn: `/newbot`
4. Đặt tên hiển thị cho Bot (ví dụ: `T-Car Pro License Bot`).
5. Đặt username kết thúc bằng chữ `bot` (ví dụ: `tcar_pro_license_bot`).
6. BotFather sẽ gửi lại cho bạn một đoạn mã **HTTP API TOKEN** dạng:
   `7123456789:AAHxxxxxx-xxxxxxxxx...` ➔ *Hãy copy lưu lại đoạn này.*

---

### BƯỚC 2: LẤY CHAT ID CÁ NHÂN CỦA BẠN (MẤT 15 GIÂY)
1. Trên Telegram, tìm bot: **`@userinfobot`**.
2. Bấm `Start`. Bot sẽ trả về ID của bạn (ví dụ: `Id: 123456789`).
3. Copy dãy số này ➔ Đây chính là **`TELEGRAM_ADMIN_CHAT_ID`**.
4. Mở lại con bot bạn vừa tạo ở Bước 1, bấm **`Start`** để mở cuộc trò chuyện với bot.

---

### BƯỚC 3: TẠO GOOGLE SHEET & DÁN MÃ APPS SCRIPT (MẤT 1 PHÚT)
1. Mở trình duyệt vào [Google Sheets (docs.google.com/spreadsheets)](https://docs.google.com/spreadsheets) tạo 1 file bảng tính mới (đặt tên: `T-Car Pro Database`).
2. Trên thanh menu, chọn: **Tiện ích mở rộng (Extensions)** ➔ **Apps Script**.
3. Xóa hết mã cũ trong ô soạn thảo, mở tệp `telegram_bot_apps_script.js` vừa tạo và **copy toàn bộ dán vào**.
4. Thay 2 dòng đầu bằng thông tin của bạn:
   ```javascript
   const TELEGRAM_BOT_TOKEN = "DÁN_TOKEN_CỦA_BẠN_VÀO_ĐÂY";
   const TELEGRAM_ADMIN_CHAT_ID = "DÁN_CHAT_ID_CỦA_BẠN_VÀO_ĐÂY";
   ```
5. Bấm nút **Lưu (Save - biểu tượng đĩa mềm)**.

---

### BƯỚC 4: TRIỂN KHAI WEB APP (DEPLOY)
1. Ở góc trên bên phải màn hình Apps Script, bấm nút **Triển khai (Deploy)** ➔ chọn **Tùy chọn triển khai mới (New deployment)**.
2. Bấm biểu tượng bánh răng bên cạnh "Chọn loại", chọn: **Ứng dụng web (Web app)**.
3. Điền mô tả: `T-Car License Server`.
4. Mục **Ai có quyền truy cập (Who has access)**: Chọn **Bất kỳ ai (Anyone)**. *(Rất quan trọng để App và Telegram gửi dữ liệu tới được).*
5. Bấm **Triển khai (Deploy)**. Nếu Google yêu cầu cấp quyền truy cập Google Sheet, bấm *Ủy quyền (Authorize access)* và chọn tài khoản Google của bạn.
6. Copy đường link **URL ứng dụng web (Web app URL)** dạng:
   `https://script.google.com/macros/s/AKfycb.../exec`

---

### BƯỚC 5: KÍCH HOẠT KẾT NỐI TELEGRAM (MẤT 5 GIÂY)
1. Trong màn hình soạn thảo Apps Script, ở thanh công cụ phía trên có ô chọn hàm (thường hiện `doGet` hoặc `doPost`), bấm vào và chọn hàm: **`setTelegramWebhook`**.
2. Bấm nút **Chạy (Run)**.
3. Nhìn xuống ô Nhật ký thực thi (Execution log), nếu thấy hiện:
   `Kết quả cài Webhook: {"ok":true,"result":true,"description":"Webhook was set"}`
   ➔ **Chúc mừng! Bot Telegram đã được liên kết thành công với Google Sheets!**

---

### BƯỚC 6: DÁN URL VÀO APP T-CAR
- Mở file `LicenseConfig.kt` trong app và dán link Web App vừa copy vào `DEFAULT_API_URL`.
- Xong! Từ nay mỗi khi khách cài app T-Car và nhập Email, điện thoại của bạn sẽ "ting ting" báo tin nhắn Telegram kèm nút Duyệt 1-chạm!
