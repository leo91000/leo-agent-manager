# Android updates from tags

Stable `vMAJOR.MINOR.PATCH` tags run Android tests, lint, debug/release builds and
emulator checks before publishing a signed `leo-android.apk` and
`android-update.json` in a GitHub release. Push the commit to main, wait for CI,
then push the version tag. Prerelease tags are rejected by the publication job.
The server/container workflow continues to use the same tags.

The app checks the latest stable release on start, throttled to once per six
hours during a process lifetime. Settings offers an explicit check. Offline or
unavailable automatic checks do not block login or chat. A prompt offers download,
then installation; it can be dismissed. Android's permission screen may require
allowing Leo to install apps, then tapping Install again after returning. The
system can require installation confirmation. There is no silent-install promise
or background download service; a killed download can be retried.

Downloads use a separate unauthenticated HTTPS client. The metadata is bounded,
URLs are fixed to this repository and tag, APKs are capped at 100 MiB, and size,
SHA-256, package ID, version, minimum SDK and signing certificate are checked before
installation. Partial files are removed. Installation rechecks the cached file.
Android also verifies APK signatures during installation. Signing-key rotation
is intentionally unsupported; keep the original key.

## Signing and versioning

GitHub Actions repository secrets `ANDROID_KEYSTORE_BASE64` and
`ANDROID_STORE_PASSWORD` hold the persistent PKCS12 keystore and password. The
key alias is `leo-android`. Never regenerate these for normal updates, commit a
keystore, or include it in build artifacts. Keep an independent secure backup of
the original keystore/password; GitHub Actions secrets are not an exportable backup.
Local signed builds accept `LEO_ANDROID_KEYSTORE` (absolute path) and
`LEO_ANDROID_STORE_PASSWORD`. Without them, local release builds remain unsigned.
Signing builds disable Gradle's configuration cache.

`LEO_ANDROID_VERSION` defaults to the development version in `app/build.gradle.kts`;
on tags CI takes it from the tag. Android `versionCode` is
`100000000 + major * 1000000 + minor * 1000 + patch` (major 0–1999, minor/patch
0–999). These codes exceed historical debug builds and grow with SemVer.
The release script validates the build metadata against the tag. The stable
publication job is serialized and refuses an equal/older version. Assets are
uploaded to a draft before publication; an already published APK is immutable.
A failed upload can resume the draft by rerunning the publication job.

## First installation

Historical CI APKs used automatically generated debug keys, without persistent
signing. They may not accept the new release certificate. In that case Android
will reject installation: uninstall the old debug app once, then install the
release APK. This removes local preferences/session/cache and requires signing in
again; server-side conversations and tasks remain on the server. Subsequent
release-to-release updates preserve local data. Debug and release builds still
share the package ID and cannot coexist.
