# Thông báo cập nhật THTV

Từ v1.0.29 (Build 202), app đọc `update.json` từ GitHub raw khi mở MainActivity, sau khi đóng nhật ký bản vừa cài. Popup tiếng Việt hiển thị version, changelog, Để sau và Cập nhật. Mỗi versionCode chỉ tự hiện một lần trong dữ liệu app; xóa dữ liệu/gỡ app sẽ xóa trạng thái này. Cài đặt trên điện thoại và Cài đặt nâng cao có Kiểm tra cập nhật để hiện lại bản đã bỏ qua. Khi offline, kiểm tra tự động im lặng và kiểm tra thủ công báo thử lại.

`versionCode` phải tăng, `versionName` khớp Gradle, `changelog` là danh sách tiếng Việt và `downloadUrl` là HTTPS GitHub Release của repo này. Nút Cập nhật mở trình duyệt tải APK; người dùng cài đặt bằng trình cài Android. Không gửi mã máy/bản quyền đến nguồn cập nhật.

Workflow chạy unit test và build, dùng lại cache `thtv-stable-debug-keystore-v1`, rồi xuất bản APK thành Release `v<versionName>`. Không tự tạo keystore mới khi mất cache và không ghi đè release đã xuất bản. Nếu cache mất cần khôi phục chính keystore cũ, không đổi khóa. Cập nhật version Gradle, changelog nội bộ, update.json và tên artifact cùng nhau khi phát hành bản tiếp theo. Không cần redeploy Apps Script.

Bản 1.0.27 chưa có chức năng đọc remote nên người dùng cần cài 1.0.29 một lần; thông báo tự động áp dụng cho các bản tiếp theo.
