## সমস্যা এখন কী

বর্তমান system-এ আপনাকে `versionCode`, `min_version`, `latest_version` — এই সব number নিজে বসাতে হয়। এটা confusing এবং ভুল হওয়ার risk।

## নতুন সহজ System (proposed)

**মূল idea:** Number-এর বদলে **GitHub build number** automatic ব্যবহার করব। আপনি শুধু admin panel-এ **একটা button টিপবেন** — বাকি সব automatic।

### কীভাবে কাজ করবে

1. **APK build হওয়ার সময়:**
   - GitHub Actions প্রতি build-এ automatic একটা unique number দেয় (run number: 1, 2, 3, 4...)
   - এই number automatic APK-এর ভেতরে inject হয়ে যাবে (আপনার কিছু করতে হবে না)
   - Build শেষ হলে GitHub Releases-এ automatic upload হবে — direct download link পাবেন

2. **Admin Panel-এ আপনি যা দেখবেন:**

   ```
   ┌─────────────────────────────────────────┐
   │ বর্তমান latest APK: Build #7            │
   │ Download link: [auto-filled]           │
   │                                         │
   │ পুরনো user-দের জন্য minimum required:   │
   │ [ Build #5 ▼ ]  ← dropdown              │
   │                                         │
   │ [ Force update চালু ]  ☑                │
   │                                         │
   │ [  🚀 Publish this update  ]            │
   └─────────────────────────────────────────┘
   ```
   
   কোনো number টাইপ করতে হবে না। শুধু dropdown থেকে select করবেন কোন build-এর নিচের user-দের update করতে বাধ্য করতে চান।

3. **App-এ কী হবে:**
   - App জানে সে Build #5, launch-এ server-কে জিজ্ঞেস করবে
   - Server বলবে: "Latest #7, minimum #6 required, force ON"
   - `5 < 6` → block screen: **"নতুন update আছে, download করুন"** + button
   - Button টিপলে GitHub release-এর APK download শুরু

### আপনার Workflow (final)

**যখন final version publish করবেন (এখন):**
1. Code push → GitHub build হবে (ধরুন Build #7)
2. Admin panel খুলবেন → দেখবেন "Latest: Build #7" auto-filled
3. Minimum = Build #7 select করবেন
4. **Publish** টিপবেন → done ✅
5. এখন user-রা এই APK download করে ব্যবহার করবে

**পরে যখন update দিতে চাইবেন:**
1. Lovable-এ improvements করবেন, test করবেন (user-দের কিছুই হবে না)
2. Ready হলে code push করবেন → GitHub Build #8 তৈরি হবে
3. আপনি নিজে সেই APK install করে test করবেন
4. সব ঠিক থাকলে → Admin panel → Latest দেখাবে "Build #8" → Minimum = #8 select → **Publish**
5. সাথে সাথে পুরনো Build #7 user-রা block screen দেখবে → নতুন APK download করবে

**Bug এলে (rollback):**
- Admin panel-এ Minimum আবার Build #7-এ নামিয়ে দিলে block বন্ধ

### Technical Changes (সংক্ষেপে)

1. **`android-build.yml`**: `versionCode = ${{ github.run_number }}` inject করব → APK-এর ভেতরে automatic build number বসবে
2. **`app-version` API**: GitHub Releases API থেকে latest release-এর URL auto-fetch করে return করবে
3. **Admin panel**: 
   - "Latest APK" + download URL — auto-fetched, read-only
   - Recent builds-এর dropdown (last 10 releases)
   - শুধু "Minimum required build" dropdown + Force toggle + **Publish** button
   - কোনো text input নেই, কোনো number টাইপ নেই
4. **DB schema**: `min_version` field রাখব (integer, GitHub run number), `latest_version` / `apk_url` field remove (auto-fetch হবে)
5. **APK-এর version check**: `BuildConfig.VERSION_CODE` = GitHub run number, তুলনা straightforward

### যে সুবিধা পাবেন

- ✅ কোনো number মনে রাখতে হবে না
- ✅ APK link manual copy-paste করতে হবে না
- ✅ Confusion-এর কোনো scope নেই — শুধু dropdown + button
- ✅ Wrong number দেওয়ার risk নেই
- ✅ Rollback সহজ (dropdown-এ পুরনো build select)

এই plan-এ এগোবো?
