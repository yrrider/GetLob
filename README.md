# GetLob — Express Feelings Over Widget ❤️

A real-time, end-to-end encrypted shared canvas and ambient connection app for couples and close friends on Android. GetLob transforms your home screen widget into a living window to your partner.

---

## ✨ Features

### 🪟 Frosted Glass UI & Custom Widget Styles
* **Glassmorphism Aesthetic**: Frosted specular sheen, translucent gradients, and subtle top-rim beveled light reflections.
* **Continuous Transparency Slider**: Dial from ultra-clean 5% translucent frosted glass to 100% solid surfaces.
* **Custom Border Styling**: Highlight your widget with customizable border colors (Frosted White, Neon Cyan, Rose Pink, Golden Glow, Lavender, or Borderless).
* **Calligraphy & Cursive Typography**: Express notes and moods in romantic, handwritten calligraphy (*Great Vibes*) or fluid cursive (*Dancing Script*).
* **Font Color Personalization**: Choose custom font colors that auto-sync across both paired devices in real time.
* **Clean Mood Display**: Minimalistic reaction display showing only your partner's emoji and mood without unnecessary clutter.

### 📱 Google-Style Floating Widget Overlay
* **Anchored Near the Widget**: Tapping the widget smoothly opens a floating action capsule right next to the widget on your home screen.
* **Zero Screen Dimming**: Seamless transparent touch backdrop that leaves your home screen and wallpaper unobstructed.
* **Smooth Spring Motion**: Google-inspired fluid entrance and exit animations.
* **Single-Row Minimalist Action Bar**:
  1. 🖼️ **Media**: Send photos, animated looping GIFs, or video clips.
  2. ✍️ **Text / Mood**: Compose notes or tap mood reaction chips.
  3. 📡 **Live Talk**: Launch walkie-talkie mode.
  4. 📳 **Vibe**: Send an instant tactile vibration pulse.
  5. 🆘 **SOS**: Emergency alarm that rings loudly on partner's phone.

### 🔔 Minimalist Notifications & Partner Identity
* **Minimalist Status Bar Logo**: Clean geometric paired-lobes vector icon.
* **Partner Name Headers**: Notification headers showcase your partner's custom nickname (settable by either user in-app) instead of generic system headers.

### 🔒 End-to-End Encryption & Security
* Zero-knowledge AES-256-GCM encryption with PBKDF2 key derivation.
* Passphrase-protected: photos, voice notes, and messages are encrypted locally before leaving your phone.

### 📡 Instant Walkie-Talkie & Live PTT
* Ultra-low latency voice communication powered by LiveKit WebRTC.
* Push-to-talk notifications with one-tap listening from home screen.

### 📳 Haptics & Urgent SOS Alerts
* **Vibe Pulse**: Send gentle haptic heartbeat pulses directly to your partner's hands.
* **Emergency SOS**: Sounds an audible alarm on the device's alarm audio stream with continuous vibration, bypassing silent mode and Do-Not-Disturb.

### 🎮 Shared Canvas & Games
* Play real-time turn-based Tic-Tac-Toe directly through the widget.
* Shared canvas memory keeping both devices synchronized.

---

## 🛠️ Tech Stack & Architecture

* **UI & Widget Framework**:
  * [Jetpack Glance 1.1.0](https://developer.android.com/jetpack/compose/glance) (AppWidget RemoteViews)
  * [Jetpack Compose & Material 3](https://developer.android.com/jetpack/compose)
  * [Coil](https://coil-kt.github.io/coil/) (High-performance image, GIF, and video frame decoding)
* **Backend & Cloud**:
  * [Firebase Firestore](https://firebase.google.com/docs/firestore) (Real-time sync of state, paired settings, and messages)
  * [Firebase Cloud Messaging](https://firebase.google.com/docs/cloud-messaging) (High-priority silent data pushes & background wakeups)
  * [Firebase Cloud Storage](https://firebase.google.com/docs/storage) (Encrypted media storage)
  * [Firebase Authentication](https://firebase.google.com/docs/auth) (Anonymous pairing authentication)
  * [Firebase Cloud Functions v2](https://firebase.google.com/docs/functions) (Automated message routing, token management, and cleanup)
* **Real-time Audio**:
  * [LiveKit Android SDK](https://livekit.io/) (WebRTC real-time voice streaming)

---

## 🚀 Getting Started

### Prerequisites
* Android Studio Ladybug (2024.2+) or newer
* JDK 17
* Android SDK 34 (Android 14) — Minimum supported: Android 9.0 (API 28)
* Firebase CLI (`npm install -g firebase-tools`)

### Setup Instructions

1. **Clone the Repository**:
   ```bash
   git clone https://github.com/yrrider/GetLob.git
   cd GetLob
   ```

2. **Firebase Configuration**:
   * Create a Firebase project at [console.firebase.google.com](https://console.firebase.google.com/).
   * Enable **Anonymous Authentication**, **Cloud Firestore**, **Cloud Storage**, and **Cloud Messaging**.
   * Download your `google-services.json` and place it in the `app/` directory:
     ```
     app/google-services.json
     ```

3. **Deploy Cloud Functions**:
   ```bash
   cd functions
   npm install
   firebase deploy --only functions,firestore:rules,storage:rules
   cd ..
   ```

4. **Build and Run the App**:
   * Open the project in Android Studio.
   * Sync Gradle files (`./gradlew assembleDebug`).
   * Run the app on your Android device or emulator.
   * Add the **GetLob** widget to your home screen!

---

## 📜 License

Distributed under the Apache License 2.0. See `LICENSE` for details.
