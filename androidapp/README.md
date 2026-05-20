# Android App

Activity-less Android companion app skeleton.

- Package/application ID: `io.github.yusukeiwaki.android_apk_instant_deploy`
- No launcher Activity is declared.
- The current app only provides the installable package base. Background sync, notification handling, APK verification, and install routing can be added as services/workers in later iterations.

## Build

Install Android SDK platform 35, then run:

```sh
gradle :app:assembleDebug
```
