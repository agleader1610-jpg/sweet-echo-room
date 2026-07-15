# NetSniffer VPS WebSocket Proxy

> সাধারণ APK ব্যবহারের জন্য এখন আলাদা VPS setup দরকার নেই। Android app-এ built-in Auto WS Proxy default হিসেবে চালু আছে। এই folder শুধু তখন লাগবে যখন নিজের আলাদা VPS relay চালাতে চান।

এই ফোল্ডারের server-টাই VPS-কে WebSocket proxy বানায়। VPS নিজে proxy না; VPS-এর ভিতরে এই relay server চালু করলে Android app-এর WebSocket traffic আগে VPS-এ আসে, VPS সেটা capture/log করে, তারপর আসল website/server-এ forward করে।

## কাজের ফ্লো

```text
Website WebSocket
   ↓ app rewrite করে
Android app → wss://your-domain/ws-proxy?token=TOKEN&target=wss://real-server/ws
   ↓
VPS relay log করে: client→server এবং server→client frame
   ↓
Real WebSocket server
```

App-এ এখন default public relay URL দেওয়া আছে:

```text
wss://netsniffer.198-54-121-62.sslip.io/ws-proxy?token=ns-android-2026-relay-4d184ca9-2578-4035
```

`target=` app নিজে automatically যোগ করে। তাই user-কে original WebSocket URL লিখতে হবে না।

## সবচেয়ে সহজ setup

VPS-এ Node.js 20+ install করে এই folder upload/copy করুন, তারপর:

```bash
npm install
export RELAY_TOKEN="change-this-to-a-long-random-value"
export PORT=8787
npm start
```

এখন test করুন:

```bash
curl http://YOUR_VPS_IP:8787/health
```

যদি `{"ok":true}` আসে, relay server চলছে।

## GitHub deploy

`.github/workflows/vps-relay-deploy.yml` এই folder change হলে relay VPS-এ upload করে `netsniffer-ws-relay` systemd service restart করবে। GitHub repository secrets লাগবে:

```text
VPS_HOST
VPS_PORT
VPS_USER
VPS_PASSWORD
```

Optional override:

```text
VPS_RELAY_DOMAIN
VPS_RELAY_TOKEN
```

Default domain/token Android app-এর default URL-এর সাথে match করে।

## Domain + HTTPS/WSS

Android app-এ WebSocket relay URL হিসেবে সাধারণত `wss://...` দরকার। তাই domain-টা VPS IP-তে point করে Caddy/Nginx দিয়ে HTTPS terminate করুন। Caddy সবচেয়ে সহজ:

```bash
sudo apt install -y caddy
sudo cp Caddyfile.example /etc/caddy/Caddyfile
sudo nano /etc/caddy/Caddyfile   # your-domain.example বদলান
sudo systemctl reload caddy
```

এরপর app-এর **WS Relay** field-এ দিন:

```text
wss://your-domain.example/ws-proxy?token=change-this-to-a-long-random-value
```

VPS-এ Nginx থাকলে `nginx.conf.example` ব্যবহার করুন।

## Production service হিসেবে চালানো

Server বন্ধ হয়ে গেলে যেন proxy বন্ধ না হয়, systemd service ব্যবহার করুন:

```bash
sudo useradd --system --home /opt/netsniffer --shell /usr/sbin/nologin netsniffer || true
sudo mkdir -p /opt/netsniffer
sudo cp -r vps-ws-relay /opt/netsniffer/
cd /opt/netsniffer/vps-ws-relay
npm install --omit=dev
sudo cp netsniffer-ws-relay.service.example /etc/systemd/system/netsniffer-ws-relay.service
sudo nano /etc/systemd/system/netsniffer-ws-relay.service   # RELAY_TOKEN বদলান
sudo systemctl daemon-reload
sudo systemctl enable --now netsniffer-ws-relay
sudo systemctl status netsniffer-ws-relay
```

Live captured WebSocket frame দেখতে:

```bash
sudo journalctl -u netsniffer-ws-relay -f
```

## Optional host allow-list

শুধু নির্দিষ্ট WebSocket host allow করতে চাইলে:

```bash
export ALLOWED_WS_HOSTS="echo.websocket.events,example.com"
```

systemd ব্যবহার করলে service file-এর `ALLOWED_WS_HOSTS` line enable করুন।

## মনে রাখবেন

- এই relay **WebSocket frame** capture করে।
- সাধারণ HTTP/HTTPS request capture করতে হলে আলাদা HTTP proxy/VPN layer দরকার।
- token ছাড়া relay ব্যবহার করা যাবে না; public server-এ token অবশ্যই strong রাখবেন।