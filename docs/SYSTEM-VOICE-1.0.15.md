# Mic hệ thống Android Auto — 1.0.15 (188)

## Cách thử

1. Build/cài bản nguồn này. Trên điện thoại vào **Cài đặt → MIC HỆ THỐNG ANDROID AUTO → Nhận lệnh từ mic hệ thống**. Công tắc mặc định bật và độc lập với bấm đôi Next/mic riêng của THTV.
2. Mở THTV trên Android Auto trước, sau đó bấm nút mic hệ thống.
3. Thử nói **“Phát VTV1 trên THTV”**, **“Phát HTV7 trên THTV”**, hoặc **“Phát bài Nắng ấm xa dần trên THTV”**. Nếu Google chưa nhận tên ứng dụng, thử **“trên THTV Media”**.
4. Vào **LỆNH GẦN NHẤT ĐÃ NHẬN** để xem thời điểm, nội dung và kết quả xử lý. Nếu chưa có lệnh mới từ nguồn “Mic hệ thống”, trợ lý chưa chuyển yêu cầu vào THTV. Việc Google mở một ứng dụng khác không chứng minh bộ xử lý THTV đã nhận lệnh.
5. Nút **THỬ LỆNH TRONG THTV** cho nhập “mở kênh VTV1” hoặc “mở nhạc Sơn Tùng” để thử riêng bộ xử lý khi Android Auto đang mở. Nút này không thử nhận dạng giọng nói hay khả năng chuyển lệnh của Google.

## Hành vi

- Nhạc: mở kết quả tìm kiếm YouTube, chờ kết quả video và chọn video đầu tiên phù hợp. Bỏ qua kết quả quảng cáo, kênh, Shorts và liên kết ngoài. Không chọn kết quả thuộc từ khóa trước. Có thể chờ tối đa khoảng 30 giây; kết quả còn phụ thuộc kết nối, giao diện và khả năng phát của YouTube.
- TV: nhận tên kênh thông dụng, một số cách đọc số bằng tiếng Việt và tên kênh nhập riêng khi có tiền tố “mở kênh”. Tra tên chính xác sau khi chuẩn hóa; VTV1 không khớp VTV10. Kênh phải có trong danh sách IPTV. Không tìm thấy thì báo lỗi.
- “Mở truyền hình” mở IPTV/kênh gần nhất; yêu cầu rỗng từ trợ lý tiếp tục nội dung đang phát. “Mở nhạc” thử bài YouTube gần nhất, nếu chưa có sẽ tìm “nhạc Việt”.
- Tắt công tắc ngừng nhận lệnh mới và hủy tìm kiếm đang chờ; không dừng bài/kênh đã phát. Đổi ứng dụng, quay lại hoặc chạm trang tìm kiếm cũng hủy việc tự chọn nhạc đang chờ.
- Khi nhận lệnh trợ lý, hủy lần Next đang chờ để tránh vừa mở nội dung vừa chuyển bài. Các sửa lỗi bấm đôi Next của 1.0.14 được giữ lại.
- Chỉ gọi mở kênh một lần khi nhận được kết quả danh sách; bỏ hai lần gọi lại theo lịch cũ có thể làm tải lại luồng truyền hình.

## Phạm vi tích hợp

Nút mic hệ thống vẫn thuộc Google Assistant/Gemini. THTV nhận lệnh media do trợ lý chuyển qua `MediaSession.onPlayFromSearch`, hoặc Activity nhận `MEDIA_PLAY_FROM_SEARCH`. `onPrepareFromSearch` lưu yêu cầu, chỉ thực hiện khi có `onPlay`. Khi tắt tính năng, không công bố các action tìm kiếm giọng nói trên MediaSession.

Tích hợp hiện cần THTV đã mở trên Android Auto để có màn hình phát. Nếu chưa sẵn sàng, trả lỗi yêu cầu mở THTV trước; không xếp hàng để tự phát vào lần mở sau. Không thay đổi ứng dụng trợ lý mặc định và không chiếm nút mic của hệ thống. Khả năng Google nhận tên và chọn THTV cần thử trên thiết bị, tài khoản và bản Android Auto thực tế.

Tham khảo chính thức: [Voice actions cho ứng dụng media trên xe](https://developer.android.com/training/cars/media/voice-actions), [Tích hợp ứng dụng media với Assistant](https://developer.android.com/media/implement/assistant).

## Xác minh bản nguồn

- Đã chạy 12 kiểm tra trình duyệt Edge với trang mẫu cho chính file `system_voice_search.js`: mobile/desktop, tiếng Việt, từ khóa cũ, sai trang/hostname, quảng cáo, liên kết không hợp lệ và kết quả tải muộn. Đều đạt. Đây không phải thử trực tiếp YouTube.
- Đã chạy lại 18 tình huống mô phỏng logic trích xuất từ bộ xử lý Next và kiểm tra các đường nhận phím dùng chung bộ xử lý. Đều đạt; không phải thực thi Kotlin/JVM.
- Có thêm 7 unit test Kotlin trong `SystemVoiceCommandParserTest.kt`, bao gồm tên kênh, số đọc tiếng Việt, lệnh nhạc, metadata trợ lý và phân biệt VTV1/VTV10. Chạy bằng `gradlew.bat :app:testDebugUnitTest` khi có môi trường Android/JDK phù hợp.
- **Chưa build APK, chưa chạy unit test Kotlin và chưa thử mic Google/DHU/vô lăng thực**: môi trường hiện từ chối chạy Java. Cần xác minh trên thiết bị trước khi kết luận chức năng mic hệ thống hoạt động đầy đủ.

Bản 1.0.15 giữ các sửa trước về giao diện YouTube/Android Auto, IPTV, trình duyệt, icon ban ngày, kênh yêu thích và phím vô lăng.
