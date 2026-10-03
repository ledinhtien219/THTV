# Bấm đôi Next mở giọng nói — 1.0.14 (187)

Nguyên nhân của lỗi trong bản 1.0.13: đường nhận sự kiện phím gọi chuyển bài ngay lần đầu, rồi mở mic ở lần thứ hai. Lệnh chuyển bài đầu tiên không được hủy. Hai đường khác (`onSkipToNext` của MediaSession và `dispatchKeyEvent` của màn hình xe) chuyển bài trực tiếp, không dùng nhận diện bấm đôi.

Bản 1.0.14 dùng chung một bộ xử lý cho cả ba đường nhận Next:

- Khi bật **Bấm đúp phím Next mở Micro**, lần bấm đầu chỉ đặt lịch chờ. Nếu không có lần thứ hai, hết thời gian đã chọn (mặc định 500ms) mới chuyển bài/kênh một lần.
- Nếu bấm lần thứ hai trong thời gian chờ, hủy lịch chuyển bài trước khi mở mic. Callback cũ dù chạy muộn cũng không được chuyển bài.
- Bỏ qua sự kiện lặp khi giữ nút và cùng một sự kiện DOWN được nhận lại. Chặn các callback từ hai nguồn xuất hiện sát nhau trong 60ms và các lần bấm dư ngay sau khi mở mic.
- Nếu tắt bấm đôi mở mic, Next vẫn chuyển ngay. Nếu cấu hình Next một lần mở mic, mở mic ngay và không khởi động lại mic ở lần bấm sát tiếp theo.
- Hủy lệnh Next đang chờ khi đổi ứng dụng, bấm Previous, chủ động mở mic hoặc hủy dịch vụ media. Nút Next trên giao diện ứng dụng vẫn hoạt động trực tiếp.

Ví dụ với cài đặt 500ms: bấm ở 0ms và 200ms → mở mic ở lần thứ hai, không chuyển bài trước hoặc sau đó. Bấm một lần ở 0ms → chuyển bài sau 500ms. Hai lần cách nhau từ 500ms trở lên được tính là hai lần bấm đơn.

## Kiểm tra đã thực hiện

- Rà mã nguồn: sự kiện phím trong MediaSession, lệnh transport và phím của Presentation đều đi qua bộ xử lý chung. ACTION_UP không gọi xử lý Next.
- Chạy mô phỏng bằng đồng hồ giả trên JavaScript chuyển đổi từ phần logic Kotlin đã trích xuất: bấm đơn/đôi, hai nguồn khác nhau, giữ nút, nhận lại DOWN, thiếu timestamp, callback trùng, bấm lần thứ ba, ngưỡng 400/500/700ms, mốc hết hạn, tắt bấm đôi, gán một lần cho mic, hủy lệnh và callback đến muộn khi luồng giao diện bận. Các trường hợp đều đạt.
- Thêm 16 unit test Kotlin trong `SteeringNextPressHandlerTest.kt` để chạy lại bằng `gradlew.bat :app:testDebugUnitTest`.

**Giới hạn xác minh:** mô phỏng logic không thay thế việc biên dịch Kotlin hay thử phần cứng. Unit test Kotlin, build APK và thử DHU/vô lăng thực chưa được thực hiện vì môi trường hiện từ chối chạy Java.

Sau khi build/cài bản mới, bật bấm đôi Next mở Micro và chọn 500ms (hoặc 700ms nếu thường bấm chậm). Thử trên YouTube và IPTV: bấm đôi → chỉ mở mic; bấm đơn → chuyển sau thời gian chờ; giữ nút → không chuyển liên tiếp. Bản nguồn này bao gồm các sửa đổi trước đó về trình duyệt, icon ban ngày và kích thước kênh yêu thích.
