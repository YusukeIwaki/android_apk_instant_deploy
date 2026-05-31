# android_apk_instant_deploy CLI

Admin API 用のコマンドラインツールです。サーバー URL と Admin API トークンは環境変数からのみ取得します。

```sh
ANDROID_APK_INSTANT_DEPLOY_SERVER=http://192.168.xx.x \
ANDROID_APK_INSTANT_DEPLOY_ACCESS_TOKEN=dev-admin-token \
android_apk_instant_deploy upload /path/to/upload.apk
```

```text
[==============>        ] 77%
DONE
```

## Commands

### upload

APK を `POST /admin/artifacts` にアップロードします。

```sh
ANDROID_APK_INSTANT_DEPLOY_SERVER=http://192.168.xx.x \
ANDROID_APK_INSTANT_DEPLOY_ACCESS_TOKEN=dev-admin-token \
android_apk_instant_deploy upload /path/to/upload.apk
```

### apps

登録済みアプリ一覧を表示します。

```sh
ANDROID_APK_INSTANT_DEPLOY_SERVER=http://192.168.xx.x \
ANDROID_APK_INSTANT_DEPLOY_ACCESS_TOKEN=dev-admin-token \
android_apk_instant_deploy apps
```

```text
display_name: xxx, package_name: com.example.xxx
display_name: xxx, package_name: com.example.xxx
```

### releases

指定した app の release 一覧を表示します。

```sh
ANDROID_APK_INSTANT_DEPLOY_SERVER=http://192.168.xx.x \
ANDROID_APK_INSTANT_DEPLOY_ACCESS_TOKEN=dev-admin-token \
android_apk_instant_deploy releases --app io.github.yusukeiwaki.camscanshare
```

```text
id: 134, version_code: 32, version_name: 1.2.3, filename: camscanshare.apk, sha256: abc123
```

### delete_release

指定した app の release を削除します。`--yes` を付けない場合は確認プロンプトを表示します。

```sh
ANDROID_APK_INSTANT_DEPLOY_SERVER=http://192.168.xx.x \
ANDROID_APK_INSTANT_DEPLOY_ACCESS_TOKEN=dev-admin-token \
android_apk_instant_deploy delete_release 134 --app io.github.yusukeiwaki.camscanshare
```

```text
Are you sure to delete release 134 for io.github.yusukeiwaki.camscanshare ? (y/N)
y
DONE
```

```sh
ANDROID_APK_INSTANT_DEPLOY_SERVER=http://192.168.xx.x \
ANDROID_APK_INSTANT_DEPLOY_ACCESS_TOKEN=dev-admin-token \
android_apk_instant_deploy delete_release 134 --app io.github.yusukeiwaki.camscanshare --yes
```

```text
DONE
```

### touch_policies

指定した package_name の app を現在の revision に持つ policy を touch します。policy の内容は変えず、同じ entries を持つ新しい revision を作成します。

```sh
ANDROID_APK_INSTANT_DEPLOY_SERVER=http://192.168.xx.x \
ANDROID_APK_INSTANT_DEPLOY_ACCESS_TOKEN=dev-admin-token \
android_apk_instant_deploy touch_policies --app io.github.yusukeiwaki.camscanshare
```

```text
policy SomePolicy1 updated
policy SomePolicy3 updated
policy SomePolicy4 updated
DONE
```

## Build

```sh
cd cli
go build -o android_apk_instant_deploy .
```

クロスビルド例:

```sh
GOOS=linux GOARCH=amd64 go build -o dist/android_apk_instant_deploy-linux-amd64 .
GOOS=darwin GOARCH=arm64 go build -o dist/android_apk_instant_deploy-darwin-arm64 .
GOOS=windows GOARCH=amd64 go build -o dist/android_apk_instant_deploy-windows-amd64.exe .
```
