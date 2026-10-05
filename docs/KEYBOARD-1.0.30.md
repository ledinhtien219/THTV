# Bàn phím Android Auto v1.0.30 (Build 203)

CarKeyboardLayout nhận click SurfaceCallback trong tọa độ của bàn phím, không phụ thuộc getGlobalVisibleRect trên VirtualDisplay. Mỗi cú click gọi performClick đúng một lần trên phím gần nhất trong vùng phím cộng 4dp ở mép. Khoảng trắng lớn ngoài phím không tạo ký tự. Cùng hit map dùng cho native touch: commit trên UP trực tiếp, cho phép rung tay nhỏ; CANCEL/kéo ra xa hủy nhập; nhấn giữ chỉ gọi long-click, không gõ thêm ký tự thường.

CarPresentation ưu tiên keyboard đang mở và chặn click/scroll/fling đi xuống WebView. Javascript bridge không được mở lại và setText khi người dùng đang gõ. Telex và thao tác Editable giữ nguyên.

Kiểm tra tự động với Robolectric: 50 click Surface liên tiếp, 30 tap native, mép/gap, root lệch vị trí, hủy/kéo, nhấn giữ, chuyển mode và tọa độ không hợp lệ. Kiểm tra thực tế cần làm trên DHU/xe: gõ QWERTY nhanh, TELEX tiếng Việt, số/ký tự, dấu cách, xóa, Enter và đóng/mở lại. SurfaceCallback chỉ cung cấp click hoàn chỉnh nên nhấn giữ phụ thuộc host có gửi native touch hay không.
