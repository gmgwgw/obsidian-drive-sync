# VaultSync — Obsidian ↔ Google Drive 双方向同期 Android アプリ

Android 版 Obsidian の保管庫（ローカルフォルダ）と Google Drive 上の指定フォルダを双方向同期するための個人用 Android ネイティブアプリ（Kotlin + Jetpack Compose）です。

## 主な機能

- **3-Way ステートフル双方向同期**（Room DB に前回同期スナップショットを保持し、削除や更新を正確に判定）
- **コンフリクトコピー生成**（PC とスマホの両方で同時編集した場合、ローカル変更を `ノート名 (conflict yyyy-MM-dd HH-mm-ss).md` として退避しデータ損失を防止）
- **Obsidian `.trash/` 連携＋定期自動削除**（Drive 側で削除されたファイルをローカルの `.trash/` へ退避し、保持期限（初期値 30 日）を過ぎたファイルを同期時に自動クリーンアップ）
- **大量削除ブレーキ**（誤って空フォルダを同期した場合など、1回で 30% 以上かつ 10 件以上削除される場合は自動停止して確認を要求）
- **多様な同期トリガー**
  - アプリ内「今すぐ同期」ボタン
  - Android クイック設定タイル
  - ホーム画面ウィジェット（Jetpack Glance）
  - 定期バックグラウンド同期（WorkManager：15分〜12時間、Wi-Fi 限定・充電中限定指定可）
- **モダンな Android 実装（Deprecated 回避）**
  - `EncryptedSharedPreferences` を使用せず **Jetpack DataStore + Android Keystore (`AES/GCM/NoPadding`)** でトークンを暗号化保存
  - `AppAuth-Android` を使用せず **Custom Tabs (`androidx.browser`) + OkHttp** で OAuth 2.0 PKCE 認証を直接実装
  - Android 11+ (`minSdk = 30`) 専用として `MANAGE_EXTERNAL_STORAGE` を使用し、`.obsidian/` 等の隠しフォルダへ高速アクセス

---

## 1. 開発環境の準備（Android Studio 導入手順）

お使いの PC には Android SDK（`C:\Users\<ユーザー名>\AppData\Local\Android\Sdk`）が導入済みです。Android Studio をインストールすると、Gradle ビルドに必要な **JDK 17+（JetBrains Runtime）** も同時に導入されます。

1. **Android Studio のダウンロード & インストール**
   - [Android Studio 公式ページ](https://developer.android.com/studio) から Windows 用インストーラーをダウンロードして実行します。
   - インストールウィザードで既存の Android SDK（`C:\Users\<ユーザー名>\AppData\Local\Android\Sdk`）が自動認識されます。
2. **プロジェクトを開く**
   - Android Studio の **Open** から `c:\Users\goril\Documents\Workspace\obsidian-drive-sync` を選択します。
   - 初回 Gradle Sync が自動で走り、`gradle-9.6.0` と依存ライブラリが取得されます。
3. **PowerShell から直接 Gradle を実行したい場合**
   - Android Studio 同梱の JBR（JDK）を `JAVA_HOME` に設定します（例）：
     ```powershell
     $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
     $env:Path = "$env:JAVA_HOME\bin;$env:Path"
     java -version
     ```

---

## 2. Google Cloud Console での OAuth 2.0 クライアント作成手順

既存の Google Drive フォルダを読み書きするため、ご自身の Google アカウントで OAuth クライアント ID を作成します（無料枠内で利用できます）。

1. [Google Cloud Console](https://console.cloud.google.com/) にアクセスし、新しいプロジェクト（例：`Obsidian-VaultSync`）を作成します。
2. **「API とサービス」→「ライブラリ」** から **Google Drive API** を検索し、**有効にする** をクリックします。
3. **「API とサービス」→「OAuth 同意画面」** を設定します。
   - 対象（User Type）：**外部**
   - アプリ名：`VaultSync`、サポートメール・デベロッパー連絡先に自身の Gmail を入力
   - スコープ追加：`https://www.googleapis.com/auth/drive`
   - **テストユーザー**：ご自身の Gmail アドレスを追加してください。
   > **注意（7日間失効の仕様）**：同意画面が「テスト中」ステータスのままだと、リフレッシュトークンは **7 日間で失効** します。失効時はアプリの通知・ホーム画面からワンタップで再ログインできます。
4. **「認証情報」→「認証情報を作成」→「OAuth クライアント ID」** を選択します。
   - アプリケーションの種類：**Android**
   - パッケージ名：`com.obsidiandrivesync`
   - **SHA-1 証明書フィンガープリント**：Android Studio のデバッグ用鍵（通常 `C:\Users\<ユーザー名>\.android\debug.keystore`）の SHA-1 を取得して貼り付けます：
     ```powershell
     keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android
     ```
5. 発行された **クライアント ID**（`xxxxxxxx.apps.googleusercontent.com`）をコピーします。

---

## 3. `local.properties` の設定（秘密情報は Git 除外済み）

`local.properties.example` を参考に、プロジェクト直下に `local.properties` を作成（または Android Studio が生成したファイルに追記）してください。

```properties
sdk.dir=C:\\Users\\YOUR_USER\\AppData\\Local\\Android\\Sdk
GOOGLE_OAUTH_CLIENT_ID=YOUR_CLIENT_ID.apps.googleusercontent.com
```

`local.properties` は `.gitignore` に登録されているため GitHub にはアップロードされません。

---

## 4. テスト・ビルド・実機インストール

### 単体テストの実行
Android Studio 内の Gradle タブ、または Android Studio の Terminal から実行します：
```powershell
.\gradlew.bat :app:testDebugUnitTest
```
（※ もしプロジェクト直下に `gradlew.bat` がまだない場合は、Android Studio の Terminal または `gradle wrapper` で生成されるか、Android Studio の Run/Build ボタンから直接実行してください。）

### デバッグ APK ビルド & 実機インストール
USB デバッグを有効にした Android 端末を接続した状態で：
1. Android Studio 上部の **Run (`▶`)** ボタンで直接インストール＆起動、または
2. コマンドラインから APK をインストール：
```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## 5. スマホ側での初期設定と Obsidian 連携

1. VaultSync を起動し、ホーム画面の案内から **「すべてのファイルへのアクセス」** 権限を許可します。
2. **設定タブ** から以下を設定します：
   - **Google でログイン**
   - **ローカル保管庫フォルダを選択**（例：`/storage/emulated/0/Documents/ObsidianVault`）
   - **Google Drive フォルダを選択**（マイドライブ内の Obsidian フォルダ）
   - 必要に応じて定期同期間隔・`.trash` 自動削除期限（初期値 30 日）を調整
3. **今すぐ同期** を実行して Google Drive の Vault を端末へダウンロードします。
4. **Android 版 Obsidian** を開き、「ローカルの保管庫を開く（Open folder as vault）」から同じローカルフォルダを指定します。

---

## 6. Git / GitHub 連携

本プロジェクトは初期化済みです。GitHub 上に **Private リポジトリ** を作成し、以下のようにリモートを追加してプッシュしてください：

```powershell
git remote add origin https://github.com/<あなたのユーザー名>/obsidian-drive-sync.git
git push -u origin main
```
