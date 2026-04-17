# Changelog

此檔案記錄專案的重要變更。
首筆條目依目前 repository 狀態整理，因目前尚無既有 changelog 或 release tag 可回溯。

## [1.0] - Initial documented release

### Added
- 建立 `AutoSRT Player` Android 播放器介面。
- 支援以 playlist URL 載入 EXTM3U 內容並開始播放。
- 支援直接貼上 EXTM3U 文字內容進行解析與播放。
- 支援播放器全螢幕切換。
- 顯示解析後的標題、媒體 URL、`User-Agent`、`Referer` 與字幕資訊。

### Supported
- 解析 `#EXTINF`、`#EXTVLCOPT` 與 `#EXTSUB` 欄位。
- 支援從 `#EXTVLCOPT` 讀取 `User-Agent` 與 `Referer` 請求標頭。
- 支援 `.srt` 與 `.vtt` 字幕格式。
- 當 `#EXTSUB` 缺失且 playlist URL 以 `.m3u` 或 `.m3u8` 結尾時，自動推導對應的 `.srt` 字幕 URL。

### Build / CI
- 新增 GitHub Actions workflow，自動建置 debug APK。
- 建置完成後上傳 debug APK artifact 供下載。

### Notes
- 目前 app 版本資訊為 `versionName 0.2.0`、`versionCode 5`。
- 後續版本請依時間倒序新增於此檔案上方。
