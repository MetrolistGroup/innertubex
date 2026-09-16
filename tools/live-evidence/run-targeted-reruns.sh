#!/usr/bin/env bash
set -euo pipefail

APP_REVISION=8835a0c374cf1c6efbb0e1f1150434db4672d5f3
BASELINE_REVISION=13f8e4d36d249024cfe356c9cd67cd7fd8ee6493
CANDIDATE_REVISION=68ba2a9627c7c85ec04968ef1d1649c2547a64f2
CANDIDATE_SRC_TREE=c31020cbf5a567b65bb98fe86d9b51d90d748066
CANDIDATE_API_TREE=e9a5c5097aaca69bfbf7151603cbc3cb32449871
TEST=com.metrolist.shared.youtube.innertube.LivePremiumAbBenchmarkTest.runSanitizedPremiumAbBenchmark

if (($# != 5)); then
    echo "usage: INNERTUBE_TOKEN_FILE=<cookie-file-path> $0 <baseline-app> <baseline-library> <candidate-app> <candidate-library> <report-dir>" >&2
    exit 2
fi
: "${INNERTUBE_TOKEN_FILE:?set INNERTUBE_TOKEN_FILE to the private cookie file path}"
[[ -r $INNERTUBE_TOKEN_FILE ]] || {
    echo "cookie file is not readable" >&2
    exit 1
}

baseline_app=$(realpath "$1")
baseline_library=$(realpath "$2")
candidate_app=$(realpath "$3")
candidate_library=$(realpath "$4")
report_dir=$(realpath -m "$5")
mkdir -p "$report_dir"
private_log_dir=$(mktemp -d)
chmod 700 "$private_log_dir"
trap 'rm -rf "$private_log_dir"' EXIT

for app in "$baseline_app" "$candidate_app"; do
    [[ $(git -C "$app" rev-parse HEAD) == "$APP_REVISION" ]] || {
        echo "Metrolist checkout is not the measured revision" >&2
        exit 1
    }
done
[[ $(git -C "$baseline_library" rev-parse HEAD) == "$BASELINE_REVISION" ]] || {
    echo "baseline revision mismatch" >&2
    exit 1
}
[[ $(git -C "$candidate_library" rev-parse HEAD) == "$CANDIDATE_REVISION" ]] || {
    echo "candidate revision mismatch" >&2
    exit 1
}
[[ $(git -C "$candidate_library" rev-parse HEAD:src) == "$CANDIDATE_SRC_TREE" ]] || {
    echo "candidate src tree mismatch" >&2
    exit 1
}
[[ $(git -C "$candidate_library" rev-parse HEAD:api) == "$CANDIDATE_API_TREE" ]] || {
    echo "candidate api tree mismatch" >&2
    exit 1
}
grep -Fq "includeBuild(\"$baseline_library\")" "$baseline_app/settings.gradle.kts" || {
    echo "baseline app is not wired to the baseline library" >&2
    exit 1
}
grep -Fq "includeBuild(\"$candidate_library\")" "$candidate_app/settings.gradle.kts" || {
    echo "candidate app is not wired to the candidate library" >&2
    exit 1
}

run_one() {
    local arm=$1 app=$2 revision=$3 profile=$4 pair=$5 order=$6 iterations=$7 decode=$8 forced_profile=$9
    local hint=false
    [[ $arm == candidate_entitlement_hypothesis ]] && hint=true
    local label="${profile}_pair${pair}_${arm}_order${order}"
    local content_filter=$profile
    [[ $profile == sabr ]] && content_filter=normal
    local log="$private_log_dir/$label.log"
    local -a extra_env=()
    [[ -n $forced_profile ]] && extra_env+=(INNERTUBE_BENCHMARK_PROFILE="$forced_profile")

    if ! flock -x /tmp/metrolist-gradle.lock timeout 1800s env \
        RUN_LIVE_INNERTUBE_CLIENT_BENCHMARKS=true \
        INNERTUBE_TOKEN_FILE="$INNERTUBE_TOKEN_FILE" \
        INNERTUBE_BENCHMARK_REPORT_DIR="$report_dir" \
        INNERTUBE_BENCHMARK_LABEL="$label" \
        INNERTUBE_BENCHMARK_ITERATIONS="$iterations" \
        INNERTUBE_BENCHMARK_DECODE="$decode" \
        INNERTUBE_BENCHMARK_DECODE_EACH="$decode" \
        INNERTUBE_BENCHMARK_PREMIUM_HINT="$hint" \
        INNERTUBE_CONTENT_TYPE_FILTER="$content_filter" \
        INNERTUBE_BENCHMARK_ARM="$arm" \
        INNERTUBE_BENCHMARK_PAIR="$pair" \
        INNERTUBE_BENCHMARK_ORDER="$order" \
        INNERTUBE_APP_REVISION="$APP_REVISION" \
        INNERTUBE_LIBRARY_REVISION="$revision" \
        "${extra_env[@]}" \
        "$app/gradlew" -p "$app" :shared:desktopTest --tests "$TEST" -PuseLocalInnerTubeX \
        --no-daemon --no-configuration-cache --max-workers=2 --no-parallel \
        -DinnerTubeTokenFile="$INNERTUBE_TOKEN_FILE" \
        '-Dorg.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m' \
        -Pkotlin.compiler.execution.strategy=in-process --console=plain >"$log" 2>&1; then
        echo "run failed: $label (private log removed on exit)" >&2
        return 1
    fi
    [[ -s $report_dir/playback-ab-$label.json && -s $report_dir/playback-ab-$label.csv ]] || {
        echo "run produced no report: $label" >&2
        return 1
    }
    echo "completed $label"
}

run_pair() {
    local profile=$1 pair=$2 first=$3 iterations=$4 decode=$5 forced_profile=$6
    local second=baseline
    [[ $first == baseline ]] && second=candidate_entitlement_hypothesis
    local order=1 arm app revision
    for arm in "$first" "$second"; do
        if [[ $arm == baseline ]]; then
            app=$baseline_app
            revision=$BASELINE_REVISION
        else
            app=$candidate_app
            revision=$CANDIDATE_REVISION
        fi
        run_one "$arm" "$app" "$revision" "$profile" "$pair" "$order" "$iterations" "$decode" "$forced_profile"
        order=$((order + 1))
        sleep 10
    done
}

profiles=(normal explicit kids)
for profile_index in "${!profiles[@]}"; do
    profile=${profiles[$profile_index]}
    for pair in 1 2 3; do
        if ((((profile_index + pair) % 2) == 1)); then
            first=baseline
        else
            first=candidate_entitlement_hypothesis
        fi
        run_pair "$profile" "$pair" "$first" 3 false ""
    done
    sleep 20
done

for pair in 1 2 3; do
    if ((pair % 2 == 1)); then first=baseline; else first=candidate_entitlement_hypothesis; fi
    run_pair sabr "$pair" "$first" 1 true WEB_REMIX_SABR
done
