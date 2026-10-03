# Trình duyệt Android Auto — 1.0.12 (185)

Mở **Ứng dụng → Trình duyệt Web**. Chạm thanh địa chỉ phía trên, nhập tên miền (ví dụ `vnexpress.net`) hoặc từ khóa, rồi bấm **Đi →**. Thanh này mở bàn phím trực tiếp trên màn hình Android Auto.

Thay đổi trong bản này:

- Thêm thanh địa chỉ/tìm Google cố định, nút về màn hình chính, quay lại, tiến tới, tải lại/dừng tải và micro tìm web.
- Dành riêng khoảng trống phía trên cho thanh địa chỉ; trang web tự chọn tỉ lệ theo viewport.
- Tách nhập địa chỉ khỏi điền ô trên trang; địa chỉ mới không bị gửi nhầm vào biểu mẫu đang mở.
- Hỗ trợ tìm kiếm bằng `textarea[name=q]` của Google, input thông thường, ô `type=search` và biểu mẫu tìm kiếm.
- Giữ tiếng Việt khi điền ô; không tự gửi biểu mẫu thường. Nếu ô đã biến mất, giữ nội dung trong bàn phím và báo lỗi thay vì gửi nội dung đó lên Google.
- Tắt chuyển đổi Telex trong chế độ thanh địa chỉ để không làm biến dạng `www` và tên miền.
- Tìm kiếm giọng nói trong trình duyệt được chuyển sang tìm web. Trang/lịch sử trình duyệt được giữ khi Android Auto tạo lại surface với WebView hiện có.
- Khi chuyển từ ứng dụng khác sang trình duyệt, lịch sử cũ được xóa sau khi tải trang đầu để nút quay lại không trở về YouTube/IPTV.

Đây vẫn là trình duyệt tích hợp dùng Android WebView. Bản sửa bổ sung các thao tác duyệt web chính theo kiểu Chrome; không cung cấp đồng bộ tài khoản Chrome, quản lý nhiều tab hoặc toàn bộ tính năng của Chrome.

Kiểm tra đã chạy: JavaScript trích từ mã nguồn trên Edge headless với trang kiểm thử — textarea/input tìm kiếm, tiếng Việt, gửi biểu mẫu, xử lý Enter, không gửi lặp, điều hướng kết quả và lịch sử back/forward/reload. Các kiểm tra cũ về cầu nối bàn phím, ô nhập động và bố cục YouTube ở năm kích thước màn hình cũng qua.

Chưa xác nhận build APK hoặc thao tác native trên thiết bị/DHU: môi trường hiện tại báo `Access is denied` khi chạy Java. Các unit test `BrowserNavigationTest` đã thêm nhưng chưa chạy. Để kiểm tra trong Android Studio/terminal có JDK hoạt động, chạy `gradlew.bat :app:testDebugUnitTest :app:assembleDebug`, sau đó cài APK mới và thử trên DHU.

Nên kiểm tra trên DHU: mở trình duyệt → tìm từ khóa → mở một kết quả → quay lại/tiến tới/tải lại; nhập một tên miền; nhập vào ô tìm kiếm Google; mở/đóng bàn phím và thử micro; về màn hình chính rồi mở lại trình duyệt. Xác nhận thanh địa chỉ không bị HUD che và không xuất hiện trong YouTube/IPTV.
