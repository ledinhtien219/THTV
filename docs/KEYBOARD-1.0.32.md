# THTV v1.0.32 (Build 205)

v1.0.31 chỉ tối ưu xử lý View sau SurfaceCallback.onClick, chưa sửa được việc host gộp thao tác chạm nhanh thành cử chỉ. Android Auto có thể gửi double tap dưới dạng onScale; code cũ bỏ qua callback này. Không có đủ tọa độ từng tap để khôi phục chính xác chuỗi phím bằng cách đoán từ onScale. Xem [SurfaceCallback](https://developer.android.com/reference/androidx/car/app/SurfaceCallback) và [Pioneer Android Auto gesture mapping](https://jpn.pioneer/ja/piomatixlbs/spec/Pioneer_Piomatix_LBS_SDK_User_Guides/Pioneer_Cloud_Navigation_SDK_User_Guide_for_Android/android_auto/top.html).

Mặc định nhập bằng SearchTemplate của Android Auto với setShowKeyboardByDefault(true). [SearchCallback](https://developer.android.com/reference/androidx/car/app/model/SearchCallback) trả snapshot đầy đủ và chuỗi cuối khi submit; không dựng lại từ từng callback chạm. Host tự quyết định khi nào có bàn phím (ví dụ khi đỗ xe); tính năng này giữ các giới hạn chuẩn của Android Auto. Có lựa chọn bàn phím THTV trong phần cài đặt thanh điều hướng, và fallback cũ khi không có car screen đăng ký.

CarInputSession giữ nguyên nội dung, snapshot thưa vẫn không mất chữ, submit cuối ưu tiên hơn preview cũ, chặn gửi trùng, giữ draft khi Back, cho phép xóa trống field web. Destination được chụp lúc mở (YouTube / địa chỉ / field web + URL hiện tại). Submit web chờ JS trả OK rồi mới pop screen, không tự tìm YouTube do mode trên Presentation mới. Callback của Presentation đã dismiss được unregister để không nhân đôi listener.

Unit tests: hàng nghìn snapshot nhanh, chữ Việt/số/URL/ký tự, paste/delete, chuỗi final mới hơn preview, submit trùng, lỗi thử lại/hủy/kết quả muộn. Workflow 37277225856 đã PASS testDebugUnitTest, kiểm thử IPTV và build APK/app test APK.

Đã cài đè APK Build 205 lên emulator-5554 thành công. Chứng chỉ SHA-256 của APK 1.0.32 khớp bản 1.0.27: dbdb9e695d351e19899e17d9fd9258b11f9cc24ab6df386c21c138d5a70a3de2. Host Android Auto đã nhận SearchTemplate và screenshot xác nhận màn hình nhập mới hiển thị.

CarHostKeyboardTest chưa PASS trên DHU: lệnh ADB input text/keyevent không đưa được chuỗi vào bàn phím projected host, assertion hết thời gian chờ submit. Không coi phép thử này là bằng chứng gõ nhanh thành công. Cần thay bước injection bằng tap qua CLI Desktop Head Unit và kiểm tra chuỗi cuối; Windows đang từ chối truy cập/chạy CLI SDK trong phiên này dù đã cấp quyền. Không suy diễn rằng GhostActivity có input connection tương đương bàn phím host.
