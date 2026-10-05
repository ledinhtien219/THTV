# THTV — quy trình Test → Duyệt Stable

## Nguyên tắc

- Mọi push lên `main` chỉ là **bản TEST NỘI BỘ**.
- Workflow **Build Internal Test APK** chỉ build + chạy test + lưu APK artifact.
- Workflow build thường **không tạo GitHub Release** và **không sửa `update.json`**.
- App người dùng chỉ đọc `update.json`, và chỉ chấp nhận khi:
  - `channel = "stable"`
  - `approved = true`

Vì vậy version trong source có thể cao hơn stable mà người dùng vẫn không nhận thông báo.

## Duyệt một build thành Stable

1. Test APK nội bộ trên xe/DHU.
2. Ghi lại **commit SHA** của đúng build đã test.
3. Vào **GitHub → Actions → Approve Stable Update → Run workflow**.
4. Điền:
   - **ref_to_promote**: commit SHA đã test.
   - **expected_version**: ví dụ `1.0.38`.
   - **release_notes**: mỗi dòng một thay đổi muốn hiện cho người dùng.
   - **approval**: gõ chính xác `DUYET STABLE`.
5. Workflow sẽ:
   - kiểm tra version không thấp hơn stable hiện tại;
   - chạy lại toàn bộ test;
   - build lại đúng commit đã test bằng signing key cố định;
   - tạo GitHub Release;
   - chỉ sau khi release thành công mới cập nhật `update.json`.

## Stable hiện tại

Stable ban đầu của cơ chế này là **v1.0.37 / Build 210**.

Các build sau (1.0.38, 1.0.39...) mặc định là **TEST** cho tới khi workflow duyệt stable hoàn tất.
