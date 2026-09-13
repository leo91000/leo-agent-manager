# Login shells must keep mise's per-command project selection, too.
export PATH="$HOME/.local/share/mise/shims:/usr/local/share/mise/shims:$HOME/.cargo/bin:/pnpm/bin:/pnpm:$PATH"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/.local/share/android/sdk}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$HOME/.android}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin"
