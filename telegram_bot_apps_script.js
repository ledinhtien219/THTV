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
const SCREEN_PROFILE_SHEET_NAME = "ScreenProfiles";
// ID của bảng tính Google Sheet của bạn (lấy từ link docs.google.com/spreadsheets/d/ID/edit)
const SPREADSHEET_ID = "14vfUIJWl33kXI7ck6mlpt2Pnstp1FeR7Z9UIQ0ktpao";

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
  let sheet = ss.getSheetByName(SHEET_NAME);
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
      "Ghi Chú"
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
 * Khởi tạo/lấy sheet hồ sơ màn hình xe.
 * Mỗi Device ID chỉ có một dòng; app cập nhật lại dòng cũ khi profile thay đổi.
 */
function getOrCreateScreenProfileSheet() {
  let ss;
  try {
    ss = SpreadsheetApp.openById(SPREADSHEET_ID);
  } catch (e) {
    ss = SpreadsheetApp.getActiveSpreadsheet();
  }

  let sheet = ss.getSheetByName(SCREEN_PROFILE_SHEET_NAME);
  if (!sheet) {
    sheet = ss.insertSheet(SCREEN_PROFILE_SHEET_NAME);
    const headers = [
      "Device ID",
      "Email",
      "Thiết Bị",
      "Android",
      "App Version",
      "Build",
      "Car Resolution",
      "Usable Area",
      "WebView",
      "Aspect Ratio",
      "DPI",
      "Density",
      "xDPI",
      "yDPI",
      "Orientation",
      "Form Factor",
      "Refresh Hz",
      "Rotation",
      "Insets L/T/R/B",
      "Phone Resolution",
      "Phone DPI",
      "HUD Style",
      "HUD Scale",
      "HUD Opacity",
      "Screen Signature",
      "First Seen",
      "Last Seen",
      "Sync Count",
      "Ghi Chú"
    ];
    sheet.appendRow(headers);
    sheet.getRange(1, 1, 1, headers.length)
      .setFontWeight("bold")
      .setBackground("#0F766E")
      .setFontColor("#FFFFFF");
    sheet.setFrozenRows(1);
  }
  return sheet;
}

/**
 * Nhận profile màn hình từ THTV.
 * Bảo vệ dữ liệu bằng cách chỉ cho phép mã máy đã APPROVED trong Licenses.
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

  // Xác nhận mã máy đang được kích hoạt.
  const licenseSheet = getOrCreateSheet();
  const licenseRows = licenseSheet.getDataRange().getValues();
  let approvedEmail = "";
  let approved = false;

  for (let i = 1; i < licenseRows.length; i++) {
    if (String(licenseRows[i][0]).trim().toUpperCase() === deviceId) {
      const status = String(licenseRows[i][4] || "").trim().toUpperCase();
      approvedEmail = String(licenseRows[i][1] || "").trim();
      approved = (status === "APPROVED");
      break;
    }
  }

  if (!approved) {
    return ContentService.createTextOutput(JSON.stringify({
      success: false,
      status: "SCREEN_PROFILE_NOT_LICENSED",
      error: "Device is not APPROVED"
    })).setMimeType(ContentService.MimeType.JSON);
  }

  const sheet = getOrCreateScreenProfileSheet();
  const rows = sheet.getDataRange().getValues();
  let rowIndex = -1;
  let firstSeen = "";
  let syncCount = 0;

  for (let i = 1; i < rows.length; i++) {
    if (String(rows[i][0]).trim().toUpperCase() === deviceId) {
      rowIndex = i + 1;
      firstSeen = rows[i][25] || "";
      syncCount = Number(rows[i][27] || 0);
      break;
    }
  }

  const nowStr = Utilities.formatDate(new Date(), "GMT+7", "dd/MM/yyyy HH:mm:ss");
  if (!firstSeen) firstSeen = nowStr;
  syncCount += 1;

  const carW = Number(data.carWidth || 0);
  const carH = Number(data.carHeight || 0);
  const usableW = Number(data.usableWidth || 0);
  const usableH = Number(data.usableHeight || 0);
  const webW = Number(data.webWidth || 0);
  const webH = Number(data.webHeight || 0);
  const phoneW = Number(data.phoneWidth || 0);
  const phoneH = Number(data.phoneHeight || 0);

  const row = [
    deviceId,
    approvedEmail,
    String(data.deviceModel || ""),
    String(data.androidVer || "") + " / SDK " + String(data.sdkInt || ""),
    String(data.appVersion || ""),
    Number(data.buildNumber || 0),
    carW + " × " + carH,
    usableW + " × " + usableH,
    webW + " × " + webH,
    Number(data.aspectRatio || 0),
    Number(data.carDpi || 0),
    Number(data.carDensity || 0),
    Number(data.xdpi || 0),
    Number(data.ydpi || 0),
    String(data.orientation || ""),
    String(data.formFactor || ""),
    Number(data.refreshRate || 0),
    Number(data.rotation || 0),
    [
      Number(data.insetLeft || 0),
      Number(data.insetTop || 0),
      Number(data.insetRight || 0),
      Number(data.insetBottom || 0)
    ].join("/"),
    phoneW + " × " + phoneH,
    Number(data.phoneDpi || 0),
    Number(data.hudStyleId || 0),
    Number(data.hudScale || 0),
    Number(data.hudOpacity || 0),
    String(data.screenSignature || ""),
    firstSeen,
    nowStr,
    syncCount,
    "Tự động từ THTV"
  ];

  let status;
  if (rowIndex > 0) {
    sheet.getRange(rowIndex, 1, 1, row.length).setValues([row]);
    status = "SCREEN_PROFILE_UPDATED";
  } else {
    sheet.appendRow(row);
    status = "SCREEN_PROFILE_SAVED";
  }

  return ContentService.createTextOutput(JSON.stringify({
    success: true,
    status: status,
    deviceId: deviceId,
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
