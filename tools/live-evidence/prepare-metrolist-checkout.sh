#!/usr/bin/env bash
set -euo pipefail

APP_REVISION=8835a0c374cf1c6efbb0e1f1150434db4672d5f3
BASELINE_REVISION=13f8e4d36d249024cfe356c9cd67cd7fd8ee6493
CANDIDATE_REVISION=68ba2a9627c7c85ec04968ef1d1649c2547a64f2

if (($# != 2)); then
    echo "usage: $0 <metrolist-checkout> <innertubex-checkout>" >&2
    exit 2
fi

app=$(realpath "$1")
library=$(realpath "$2")
test_path=shared/src/desktopTest/kotlin/com/metrolist/shared/youtube/innertube/LivePremiumAbBenchmarkTest.kt
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

[[ $(git -C "$app" rev-parse HEAD) == "$APP_REVISION" ]] || {
    echo "Metrolist checkout is not the measured revision" >&2
    exit 1
}
library_revision=$(git -C "$library" rev-parse HEAD)
[[ $library_revision == "$BASELINE_REVISION" || $library_revision == "$CANDIDATE_REVISION" ]] || {
    echo "InnerTubeX checkout is neither the exact baseline nor original PR #11 candidate" >&2
    exit 1
}

settings=$(git -C "$app" show HEAD:settings.gradle.kts)
[[ $settings == *'includeBuild("../innertubex")'* ]] || {
    echo "Measured Metrolist settings layout changed" >&2
    exit 1
}
printf '%s\n' "${settings/includeBuild(\"..\/innertubex\")/includeBuild(\"$library\")}" >"$app/settings.gradle.kts"
install -m 0644 "$script_dir/LivePremiumAbBenchmarkTest.kt" "$app/$test_path"

printf 'Prepared app=%s library=%s\n' "$APP_REVISION" "$library_revision"
