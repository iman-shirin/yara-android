#!/usr/bin/env bash
set -euo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
TOOLS_VERSION="13114758"
TOOLS_ARCHIVE="commandlinetools-linux-${TOOLS_VERSION}_latest.zip"
TOOLS_URL="https://dl.google.com/android/repository/${TOOLS_ARCHIVE}"

sudo mkdir -p "${SDK_ROOT}/cmdline-tools"
sudo chown -R "$(id -u):$(id -g)" "${SDK_ROOT}"

if [[ ! -x "${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager" ]]; then
  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir}"' EXIT

  curl --fail --location --retry 3 "${TOOLS_URL}" --output "${temp_dir}/${TOOLS_ARCHIVE}"
  unzip -q "${temp_dir}/${TOOLS_ARCHIVE}" -d "${temp_dir}"

  rm -rf "${SDK_ROOT}/cmdline-tools/latest"
  mv "${temp_dir}/cmdline-tools" "${SDK_ROOT}/cmdline-tools/latest"
fi

export PATH="${SDK_ROOT}/cmdline-tools/latest/bin:${SDK_ROOT}/platform-tools:${PATH}"

yes | sdkmanager --sdk_root="${SDK_ROOT}" --licenses >/dev/null
sdkmanager --sdk_root="${SDK_ROOT}" \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;35.0.0"

echo "Android SDK ready at ${SDK_ROOT}"
