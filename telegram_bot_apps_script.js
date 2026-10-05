/**
 * =========================================================================
 * BỘ MÃ NGUỒN GOOGLE APPS SCRIPT: QUẢN LÝ BẢN QUYỀN T-CAR PRO QUA TELEGRAM
 * =========================================================================
 * - Miễn phí 100%, chạy vĩnh viễn trên Google Sheets.
 * - Nhận thông báo người dùng mới từ App T-Car gửi lên.
 * - Bấm 1 nút trên Telegram duyệt trực tiếp: [Duyệt 1 Tháng] [Duyệt Trọn Đời] [Từ Chối]
 * - App T-Car tự động kiểm tra và mở khóa ngay lập tức!
 */

// ==========================================
// 1. CẤU HÌNH TOKEN TELEGRAM CỦA BẠN TẠI ĐÂY
// ==========================================
const TELEGRAM_BOT_TOKEN = "ĐIỀN_BOT_TOKEN_TỪ_BOTFATHER_VÀO_ĐÂY"; // Ví dụ: "7123456789:AAHxxxxxx..."
const TELEGRAM_ADMIN_CHAT_ID = "ĐIỀN_CHAT_ID_CỦA_BẠN_VÀO_ĐÂY"; // Ví dụ: "123456789"
const SHEET_NAME = "Licenses";
// ID của bảng tính Google Sheet của bạn (lấy từ link docs.google.com/spreadsheets/d/ID/edit)
const SPREADSHEET_ID = "14vfUJwL33kXJ7ck6mJpT2Pnstp1FeR729UJQ0ktpaco";
const LICENSE_SHEET_GID = 2114262915;

/**
 * Khởi tạo hoặc lấy Sheet quản lý bản quyền
 */
function getOrCreateSheet() {
  let ss;
  try {
    ss = SpreadsheetApp.openById(SPREADSHEET_ID);
  } catch (e) {
    ss = SpreadsheetApp.getActiveSpreadsheet();
  }

  // Ưu tiên đúng sheet tab đang chứa danh sách Device ID trong link quản trị.
  // Dùng gid giúp không phụ thuộc tên tab có bị đổi hay không.
  let sheet = null;
  try {
    sheet = ss.getSheets().find(function(s) {
      return Number(s.getSheetId()) === Number(LICENSE_SHEET_GID);
    }) || null;
  } catch (e) {}

  if (!sheet) {
    sheet = ss.getSheetByName(SHEET_NAME);
  }

  if (!sheet) {
    const firstSheet = ss.getSheets()[0];
    if (firstSheet && firstSheet.getLastRow() === 0) {
      firstSheet.setName(SHEET_NAME);
      sheet = firstSheet;
    } else {
      sheet = ss.insertSheet(SHEET_NAME);
    }
    // Tạo tiêu đề các cột
    const headers = [
      "Device ID",
      "Email",
      "Thiết Bị",
      "Android",
      "Trạng Thái",
      "Gói Bản Quyền",
      "Ngày Kích Hoạt",
      "Hạn Dùng",
      "Cập Nhật Cuối",
      "Ghi Chú",
      "App",
      "Màn Hình Xe",
      "Vùng Dùng Được",
      "WebView",
      "Tỉ Lệ",
      "DPI",
      "Loại Màn",
      "Hz",
      "Insets",
      "Màn Điện Thoại",
      "Phone DPI",
      "HUD",
      "Screen ID",
      "Đồng Bộ Màn Hình",
      "Số Lần Sync"
    ];
    sheet.appendRow(headers);
    sheet.getRange(1, 1, 1, headers.length).setFontWeight("bold").setBackground("#0284C7").setFontColor("#FFFFFF");
    sheet.setFrozenRows(1);
  }
  return sheet;
}

/**
 * Xử lý yêu cầu POST:
 * 1. Từ App T-Car: Đăng ký yêu cầu kích hoạt mới (gửi thông báo Telegram cho Admin).
 * 2. Từ Telegram Webhook: Khi Admin bấm nút Duyệt trên màn hình Telegram.
 */
function doPost(e) {
  try {
    if (!e || !e.postData || !e.postData.contents) {
      return ContentService.createTextOutput(JSON.stringify({ error: "No data" })).setMimeType(ContentService.MimeType.JSON);
    }

    const data = JSON.parse(e.postData.contents);

    // ==========================================
    // TRƯỜNG HỢP A: TELEGRAM WEBHOOK (Admin bấm nút duyệt)
    // ==========================================
    if (data.callback_query) {
      return handleTelegramCallback(data.callback_query);
    }

    // ==========================================
    // TRƯỜNG HỢP B: T-CAR APP GỬI YÊU CẦU ĐĂNG KÝ
    // ==========================================
    if (data.action === "register") {
      return handleAppRegistration(data);
    }

    // ==========================================
    // TRƯỜNG HỢP C: THTV APP GỬI HỒ SƠ MÀN HÌNH XE
    // Chỉ nhận khi Device ID đã APPROVED trong sheet Licenses.
    // ==========================================
    if (data.action === "screen_profile") {
      return handleScreenProfile(data);
    }

    return ContentService.createTextOutput(JSON.stringify({ status: "UNKNOWN_ACTION" })).setMimeType(ContentService.MimeType.JSON);
  } catch (err) {
    return ContentService.createTextOutput(JSON.stringify({ error: err.toString() })).setMimeType(ContentService.MimeType.JSON);
  }
}

/**
 * Đảm bảo các cột hồ sơ màn hình tồn tại ngay trong sheet Licenses.
 * A:J giữ nguyên dữ liệu bản quyền. Hồ sơ màn hình dùng K:Y.
 */
function ensureScreenProfileColumns(sheet) {
  const headers = [
    "App",
    "Màn Hình Xe",
    "Vùng Dùng Được",
    "WebView",
    "Tỉ Lệ",
    "DPI",
    "Loại Màn",
    "Hz",
    "Insets",
    "Màn Điện Thoại",
    "Phone DPI",
    "HUD",
    "Screen ID",
    "Đồng Bộ Màn Hình",
    "Số Lần Sync"
  ];

  // K = 11, ghi tiêu đề K:Y. Không đụng vào A:J hiện có.
  const existing = sheet.getRange(1, 11, 1, headers.length).getValues()[0];
  let needsWrite = false;
  for (let i = 0; i < headers.length; i++) {
    if (String(existing[i] || "").trim() !== headers[i]) {
      needsWrite = true;
      break;
    }
  }

  if (needsWrite) {
    sheet.getRange(1, 11, 1, headers.length).setValues([headers]);
    sheet.getRange(1, 11, 1, headers.length)
      .setFontWeight("bold")
      .setBackground("#0F766E")
      .setFontColor("#FFFFFF")
      .setHorizontalAlignment("center");
  }
}

/**
 * Nhận profile màn hình từ THTV và cập nhật NGAY TRÊN DÒNG license.
 * Chỉ Device ID đang APPROVED mới được ghi.
 *
 * Cột:
 * K App
 * L Màn Hình Xe
 * M Vùng Dùng Được
 * N WebView
 * O Tỉ Lệ
 * P DPI
 * Q Loại Màn
 * R Hz
 * S Insets
 * T Màn Điện Thoại
 * U Phone DPI
 * V HUD
 * W Screen ID
 * X Đồng Bộ Màn Hình
 * Y Số Lần Sync
 */
function handleScreenProfile(data) {
  const deviceId = String(data.deviceId || "").trim().toUpperCase();
  if (!deviceId) {
    return ContentService.createTextOutput(JSON.stringify({
      success: false,
      status: "SCREEN_PROFILE_REJECTED",
      error: "Missing deviceId"
    })).setMimeType(ContentService.MimeType.JSON);
  }

  const sheet = getOrCreateSheet();
  ensureScreenProfileColumns(sheet);

  const rows = sheet.getDataRange().getValues();
  let rowIndex = -1;
  let approved = false;

  for (let i = 1; i < rows.length; i++) {
    if (String(rows[i][0] || "").trim().toUpperCase() === deviceId) {
      rowIndex = i + 1;
      approved = String(rows[i][4] || "").trim().toUpperCase() === "APPROVED";
      break;
    }
  }

  if (rowIndex < 0) {
    return ContentService.createTextOutput(JSON.stringify({
      success: false,
      status: "SCREEN_PROFILE_DEVICE_NOT_FOUND",
      error: "Device ID not found in Licenses"
    })).setMimeType(ContentService.MimeType.JSON);
  }

  if (!approved) {
    return ContentService.createTextOutput(JSON.stringify({
      success: false,
      status: "SCREEN_PROFILE_NOT_LICENSED",
      error: "Device is not APPROVED"
    })).setMimeType(ContentService.MimeType.JSON);
  }

  const nowStr = Utilities.formatDate(new Date(), "GMT+7", "dd/MM/yyyy HH:mm:ss");

  const carW = Number(data.carWidth || 0);
  const carH = Number(data.carHeight || 0);
  const usableW = Number(data.usableWidth || 0);
  const usableH = Number(data.usableHeight || 0);
  const webW = Number(data.webWidth || 0);
  const webH = Number(data.webHeight || 0);
  const phoneW = Number(data.phoneWidth || 0);
  const phoneH = Number(data.phoneHeight || 0);

  const currentSyncCount = Number(sheet.getRange(rowIndex, 25).getValue() || 0);
  const syncCount = currentSyncCount + 1;

  const appText =
    String(data.appVersion || "") +
    (data.buildNumber ? " (" + String(data.buildNumber) + ")" : "");

  const carResolution = carW > 0 && carH > 0 ? carW + " × " + carH : "";
  const usableArea = usableW > 0 && usableH > 0 ? usableW + " × " + usableH : "";
  const webViewArea = webW > 0 && webH > 0 ? webW + " × " + webH : "";
  const phoneResolution = phoneW > 0 && phoneH > 0 ? phoneW + " × " + phoneH : "";

  const ratio = Number(data.aspectRatio || 0);
  const ratioText = ratio > 0 ? ratio.toFixed(3) + ":1" : "";

  const dpi = Number(data.carDpi || 0);
  const density = Number(data.carDensity || 0);
  const dpiText = dpi > 0
    ? String(dpi) + (density > 0 ? " / " + density.toFixed(2) + "x" : "")
    : "";

  const formFactor = String(data.formFactor || "");
  const orientation = String(data.orientation || "");
  const screenType = [formFactor, orientation].filter(String).join(" • ");

  const refresh = Number(data.refreshRate || 0);
  const hzText = refresh > 0 ? refresh.toFixed(1) + " Hz" : "";

  const insetText = [
    Number(data.insetLeft || 0),
    Number(data.insetTop || 0),
    Number(data.insetRight || 0),
    Number(data.insetBottom || 0)
  ].join("/");

  const phoneDpi = Number(data.phoneDpi || 0);
  const phoneDensity = Number(data.phoneDensity || 0);
  const phoneDpiText = phoneDpi > 0
    ? String(phoneDpi) + (phoneDensity > 0 ? " / " + phoneDensity.toFixed(2) + "x" : "")
    : "";

  const hudText =
    "Style " + String(data.hudStyleId || 0) +
    " • Scale " + String(data.hudScale || 0) + "%" +
    " • Opacity " + String(data.hudOpacity || 0) + "%";

  const screenValues = [[
    appText,                              // K
    carResolution,                       // L
    usableArea,                          // M
    webViewArea,                         // N
    ratioText,                           // O
    dpiText,                             // P
    screenType,                          // Q
    hzText,                              // R
    insetText,                           // S
    phoneResolution,                     // T
    phoneDpiText,                        // U
    hudText,                             // V
    String(data.screenSignature || ""),  // W
    nowStr,                              // X
    syncCount                            // Y
  ]];

  sheet.getRange(rowIndex, 11, 1, screenValues[0].length).setValues(screenValues);

  return ContentService.createTextOutput(JSON.stringify({
    success: true,
    status: "SCREEN_PROFILE_UPDATED",
    deviceId: deviceId,
    row: rowIndex,
    screenSignature: String(data.screenSignature || ""),
    syncCount: syncCount
  })).setMimeType(ContentService.MimeType.JSON);
}


/**
 * Xử lý khi App T-Car gửi yêu cầu kích hoạt mới
 */
function handleAppRegistration(data) {
  const sheet = getOrCreateSheet();
  const deviceId = (data.deviceId || "").trim().toUpperCase();
  const email = (data.email || "").trim().toLowerCase();
  const deviceModel = (data.deviceModel || "Unknown Device").trim();
  const androidVer = (data.androidVer || "").trim();

  if (!deviceId || !email) {
    return ContentService.createTextOutput(JSON.stringify({ error: "Missing deviceId or email" })).setMimeType(ContentService.MimeType.JSON);
  }

  // Tìm xem Device ID đã tồn tại chưa
  const rows = sheet.getDataRange().getValues();
  let rowIndex = -1;
  for (let i = 1; i < rows.length; i++) {
    if (String(rows[i][0]).toUpperCase() === deviceId) {
      rowIndex = i + 1;
      break;
    }
  }

  const nowStr = Utilities.formatDate(new Date(), "GMT+7", "dd/MM/yyyy HH:mm:ss");

  if (rowIndex > 0) {
    // Đã có -> Cập nhật email, model, trạng thái PENDING nếu chưa duyệt
    const currentStatus = String(rows[rowIndex - 1][4]).toUpperCase();
    if (currentStatus === "APPROVED") {
      // Nếu đã được duyệt từ trước rồi thì trả về luôn không cần nhắn lại
      return ContentService.createTextOutput(JSON.stringify({
        status: "APPROVED",
        plan: rows[rowIndex - 1][5],
        expiry: rows[rowIndex - 1][7]
      })).setMimeType(ContentService.MimeType.JSON);
    }
    sheet.getRange(rowIndex, 2).setValue(email);
    sheet.getRange(rowIndex, 3).setValue(deviceModel);
    sheet.getRange(rowIndex, 4).setValue(androidVer);
    sheet.getRange(rowIndex, 5).setValue("PENDING");
    sheet.getRange(rowIndex, 9).setValue(nowStr);
  } else {
    // Thêm dòng mới
    sheet.appendRow([
      deviceId,
      email,
      deviceModel,
      androidVer,
      "PENDING",
      "CHƯA DUYỆT",
      "-",
      "-",
      nowStr,
      "Khách tự gửi yêu cầu"
    ]);
  }

  // GỬI TIN NHẮN ĐẾN TELEGRAM ADMIN KÈM 3 NÚT DUYỆT
  sendTelegramRegistrationAlert(deviceId, email, deviceModel, androidVer, nowStr);

  return ContentService.createTextOutput(JSON.stringify({
    success: true,
    status: "PENDING",
    message: "Đã gửi thông báo đến Quản trị viên trên Telegram"
  })).setMimeType(ContentService.MimeType.JSON);
}

/**
 * Gửi tin nhắn thông báo kèm nút bấm Inline Keyboard đến Telegram Admin
 */
function sendTelegramRegistrationAlert(deviceId, email, deviceModel, androidVer, timeStr) {
  const url = "https://api.telegram.org/bot" + TELEGRAM_BOT_TOKEN + "/sendMessage";

  const messageText = 
    "🔔 *YÊU CẦU KÍCH HOẠT T-CAR PRO MỚI!*\n\n" +
    "👤 *Email:* `" + email + "`\n" +
    "📱 *Thiết bị:* `" + deviceModel + "` (Android " + androidVer + ")\n" +
    "🔑 *Mã máy:* `" + deviceId + "`\n" +
    "⏰ *Thời gian:* `" + timeStr + "`\n\n" +
    "👉 _Bấm một nút bên dưới để duyệt kích hoạt tức thì cho khách:_";

  const keyboard = {
    inline_keyboard: [
      [
        { text: "✅ DUYỆT 1 THÁNG", callback_data: "appr_1m:" + deviceId },
        { text: "⭐ DUYỆT TRỌN ĐỜI", callback_data: "appr_life:" + deviceId }
      ],
      [
        { text: "❌ TỪ CHỐI", callback_data: "reject:" + deviceId }
      ]
    ]
  };

  const payload = {
    chat_id: TELEGRAM_ADMIN_CHAT_ID,
    text: messageText,
    parse_mode: "Markdown",
    reply_markup: keyboard
  };

  UrlFetchApp.fetch(url, {
    method: "post",
    contentType: "application/json",
    payload: JSON.stringify(payload),
    muteHttpExceptions: true
  });
}

/**
 * Xử lý khi Admin bấm nút trên Telegram
 */
function handleTelegramCallback(callbackQuery) {
  const callbackId = callbackQuery.id;
  const data = callbackQuery.data || "";
  const parts = data.split(":");
  const action = parts[0];
  const deviceId = (parts[1] || "").toUpperCase();

  const sheet = getOrCreateSheet();
  const rows = sheet.getDataRange().getValues();
  let rowIndex = -1;
  let clientEmail = "";
  let clientModel = "";

  for (let i = 1; i < rows.length; i++) {
    if (String(rows[i][0]).toUpperCase() === deviceId) {
      rowIndex = i + 1;
      clientEmail = rows[i][1];
      clientModel = rows[i][2];
      break;
    }
  }

  const now = new Date();
  const nowStr = Utilities.formatDate(now, "GMT+7", "dd/MM/yyyy HH:mm");
  let statusText = "";
  let planName = "";
  let expiryStr = "";
  let toastMsg = "";

  if (action === "appr_1m") {
    const expDate = new Date(now.getTime() + 30 * 24 * 60 * 60 * 1000);
    expiryStr = Utilities.formatDate(expDate, "GMT+7", "dd/MM/yyyy");
    planName = "1 THÁNG";
    statusText = "APPROVED";
    toastMsg = "✅ Đã duyệt 1 tháng cho " + clientEmail;
  } else if (action === "appr_life") {
    expiryStr = "VĨNH VIỄN";
    planName = "TRỌN ĐỜI";
    statusText = "APPROVED";
    toastMsg = "⭐ Đã duyệt Trọn đời cho " + clientEmail;
  } else if (action === "reject") {
    statusText = "REJECTED";
    planName = "BỊ TỪ CHỐI";
    expiryStr = "-";
    toastMsg = "❌ Đã từ chối " + clientEmail;
  }

  if (rowIndex > 0) {
    sheet.getRange(rowIndex, 5).setValue(statusText);
    sheet.getRange(rowIndex, 6).setValue(planName);
    sheet.getRange(rowIndex, 7).setValue(nowStr);
    sheet.getRange(rowIndex, 8).setValue(expiryStr);
    sheet.getRange(rowIndex, 9).setValue(nowStr);
  }

  // 1. Trả lời popup trên Telegram
  answerTelegramCallback(callbackId, toastMsg);

  // 2. Chỉnh sửa tin nhắn Telegram gốc để hiển thị đã duyệt & xóa nút bấm
  editTelegramMessage(
    callbackQuery.message.chat.id,
    callbackQuery.message.message_id,
    "🎉 *BẢN QUYỀN T-CAR PRO ĐÃ ĐƯỢC XỬ LÝ!*\n\n" +
    "👤 *Email:* `" + clientEmail + "`\n" +
    "📱 *Thiết bị:* `" + clientModel + "`\n" +
    "🔑 *Mã máy:* `" + deviceId + "`\n" +
    "📦 *Gói:* *" + planName + "*\n" +
    "📅 *Hạn dùng:* `" + expiryStr + "`\n" +
    "⚡ *Trạng thái:* `" + statusText + "`\n" +
    "👮 *Duyệt bởi:* Admin lúc " + nowStr
  );

  return ContentService.createTextOutput(JSON.stringify({ result: "OK" })).setMimeType(ContentService.MimeType.JSON);
}

function answerTelegramCallback(callbackId, text) {
  const url = "https://api.telegram.org/bot" + TELEGRAM_BOT_TOKEN + "/answerCallbackQuery";
  UrlFetchApp.fetch(url, {
    method: "post",
    contentType: "application/json",
    payload: JSON.stringify({ callback_query_id: callbackId, text: text, show_alert: false }),
    muteHttpExceptions: true
  });
}

function editTelegramMessage(chatId, messageId, text) {
  const url = "https://api.telegram.org/bot" + TELEGRAM_BOT_TOKEN + "/editMessageText";
  UrlFetchApp.fetch(url, {
    method: "post",
    contentType: "application/json",
    payload: JSON.stringify({
      chat_id: chatId,
      message_id: messageId,
      text: text,
      parse_mode: "Markdown",
      reply_markup: { inline_keyboard: [] } // Xóa nút bấm để không bị bấm trùng
    }),
    muteHttpExceptions: true
  });
}

/**
 * Xử lý yêu cầu GET: App T-Car kiểm tra tình trạng bản quyền
 * URL: ?action=check&deviceId=...&email=...
 */
function doGet(e) {
  try {
    const action = e.parameter.action;
    const deviceId = (e.parameter.deviceId || "").trim().toUpperCase();

    if (action === "check" && deviceId) {
      const sheet = getOrCreateSheet();
      const rows = sheet.getDataRange().getValues();

      for (let i = 1; i < rows.length; i++) {
        if (String(rows[i][0]).toUpperCase() === deviceId) {
          const email = String(rows[i][1] || "");
          const status = String(rows[i][4] || "PENDING");
          const plan = String(rows[i][5] || "CHƯA DUYỆT");
          let expiryRaw = rows[i][7];
          let expiryStr = "";
          if (expiryRaw instanceof Date) {
            expiryStr = Utilities.formatDate(expiryRaw, "GMT+7", "dd/MM/yyyy");
          } else {
            expiryStr = String(expiryRaw || "-");
          }

          return ContentService.createTextOutput(JSON.stringify({
            status: status, // APPROVED / PENDING / REJECTED
            plan: plan,
            expiry: expiryStr,
            email: email
          })).setMimeType(ContentService.MimeType.JSON);
        }
      }

      return ContentService.createTextOutput(JSON.stringify({
        status: "NOT_FOUND"
      })).setMimeType(ContentService.MimeType.JSON);
    }

    if (action === "setWebhook") {
      const targetUrl = "https://script.google.com/macros/s/AKfycbx3VghzkpJBZWku2wN82l3tmJI1IwHSqhPJF6ad4FnW9DKpS-yblaDTDGKw31s4EngNZA/exec";
      const setUrl = "https://api.telegram.org/bot" + TELEGRAM_BOT_TOKEN + "/setWebhook?url=" + encodeURIComponent(targetUrl);
      const resp = UrlFetchApp.fetch(setUrl).getContentText();
      return ContentService.createTextOutput(resp).setMimeType(ContentService.MimeType.JSON);
    }

    return ContentService.createTextOutput(JSON.stringify({
      app: "T-Car Pro License Server",
      status: "RUNNING"
    })).setMimeType(ContentService.MimeType.JSON);
  } catch (err) {
    return ContentService.createTextOutput(JSON.stringify({ error: err.toString() })).setMimeType(ContentService.MimeType.JSON);
  }
}

/**
 * Hàm hỗ trợ thiết lập Webhook Telegram chỉ với 1 cú click chuột trong Apps Script
 */
function setTelegramWebhook() {
  // Điền trực tiếp URL Web App (/exec) để đảm bảo Telegram không bị chuyển hướng đăng nhập Google
  const webAppUrl = "https://script.google.com/macros/s/AKfycbx3VghzkpJBZWku2wN82l3tmJI1IwHSqhPJF6ad4FnW9DKpS-yblaDTDGKw31s4EngNZA/exec";
  const url = "https://api.telegram.org/bot" + TELEGRAM_BOT_TOKEN + "/setWebhook?url=" + encodeURIComponent(webAppUrl);
  const resp = UrlFetchApp.fetch(url).getContentText();
  Logger.log("Kết quả cài Webhook: " + resp);
}
