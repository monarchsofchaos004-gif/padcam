# PadCam — 舊平板變自架嬰兒／幼兒看顧攝影機 / Turn an old Android tablet into a self-hosted baby monitor

> **狀態：alpha。** 相機／麥克風／串流程式碼來自作者自家每天在用的看顧工具，但「獨立 App」這個包裝是新的，
> 歡迎回報不同機型的問題。
> **Status: alpha.** The camera/mic/streaming code comes from a tool the author uses daily; the standalone-app packaging is new.

- **零雲端、零帳號、零月費**：平板自己就是伺服器。影像只在平板和你的手機之間傳。
- **怎麼看**：手機瀏覽器開 `http://<平板IP>:8080/<連結密碼>/`。同一個 WiFi 直接看；出門在外，兩邊都裝 [Tailscale](https://tailscale.com)（免費）就能看。
- **不偷偷錄**：有人在看時平板會亮系統的相機／麥克風指示點；沒人看約 12 秒自動關相機。
- **省電**：相機只在有人連進來時才開；平板可以關螢幕（App 持有喚醒鎖）。

- **Zero cloud, zero accounts, zero fees** — the tablet *is* the server; video goes only between the tablet and your phone.
- **Viewing** — open `http://<tablet-ip>:8080/<link-secret>/` in any browser on the same Wi-Fi; away from home, install Tailscale on both devices.
- **Honest** — the OS camera/mic indicator shows whenever someone is watching; the camera closes ~12 s after the last viewer leaves.
- **Frugal** — camera opens only on demand; the screen can be off.

## 安裝 / Install
1. 下載 APK（GitHub Releases），側載到平板。
2. 開 App → ① 授權相機／麥克風／通知 → ▶ 啟動看顧伺服器。
3. 畫面會列出網址（含 16 字連結密碼）。手機瀏覽器打開即可看；按「🔇 開聲音」聽聲音、「📸 存這張」存快照。
4. 建議：平板插電、把本 App 的電池最佳化關掉、勾「開機自動啟動」。

## 網址一覽 / Endpoints
| 路徑 | 內容 |
|---|---|
| `/<secret>/` | 看顧網頁 |
| `/<secret>/video.mjpg` | MJPEG 影像（任何瀏覽器、VLC、Home Assistant 都吃） |
| `/<secret>/audio.wav` | 串流 WAV 聲音（16 kHz 單聲道） |
| `/<secret>/snapshot.jpg` | 單張快照 |
| `/<secret>/state` | 狀態 JSON |
| `/<secret>/stop` | 關相機 |

連結密碼錯就一律 404。要換密碼按「重新產生連結密碼」，舊網址立刻失效。

## 安全 / Security
- 沒有 TLS（區網／Tailscale 內使用）。**不要把埠直接開到公網**；要遠端就用 Tailscale 或自己的 VPN。
- 連結密碼＝網址路徑第一段，16 字、31 種符號（約 79 bits）。不知道密碼的人只會看到 404。
- 影像從不經第三方伺服器；本 App 不連網際網路上的任何服務。
- 平板端系統指示點不可能被本 App 隱藏（也不該被隱藏）。

## 限制 / Limits
- MJPEG 約 3–4 張／秒、長邊 480 或 960 px，是「看顧」不是「錄影」；要 1080p 即時可以把 `video.mjpg` 換成 WebRTC 閘道（例如 go2rtc）當來源，路線圖上。
- Android 14 起「開機自動啟動」可能被系統擋（相機類前景服務不能從背景起），開一次 App 即可。
- 平板硬體決定畫質與夜間表現。

## 從原始碼編譯 / Build
JDK 17、Android SDK 34、Gradle 8.9：`cd app-android && gradle assembleDebug`。GitHub Actions 每次 push 自動編譯；打 `v*` tag 自動發 Release。

## 路線圖 / Roadmap
- [x] MJPEG＋WAV＋快照＋網頁，區網／Tailscale 直連
- [ ] H.264／WebRTC（1080p 即時）
- [ ] 聲音觸發通知（哭聲／大聲）
- [ ] 多台平板切換

## 授權 / License
MIT — see [LICENSE](LICENSE).
