# Reproduce the live evidence

This directory carries an opt-in patch for a Metrolist-KMP desktop test. The app-only code is applied to and compiled only in a temporary host checkout; InnerTubeX source sets and APIs do not reference Metrolist classes or dependencies.

## Exact checkouts

Use temporary detached app worktrees. Do not modify the primary Metrolist-KMP checkout.

```bash
git -C <innertubex-repository> fetch origin main pull/11/head:test/pr-11-original
git -C <metrolist-repository> worktree add --detach <baseline-app> 8835a0c374cf1c6efbb0e1f1150434db4672d5f3
git -C <metrolist-repository> worktree add --detach <candidate-app> 8835a0c374cf1c6efbb0e1f1150434db4672d5f3
git -C <innertubex-repository> worktree add --detach <baseline-library> 13f8e4d36d249024cfe356c9cd67cd7fd8ee6493
git -C <innertubex-repository> worktree add <candidate-library> test/pr-11-original
```

The measured candidate is the original PR #11 revision `68ba2a9627c7c85ec04968ef1d1649c2547a64f2`. Verify the checkout and the independently measured snapshot identity:

```bash
test "$(git -C <candidate-library> rev-parse HEAD)" = 68ba2a9627c7c85ec04968ef1d1649c2547a64f2
test "$(git -C <candidate-library> rev-parse HEAD:src)" = c31020cbf5a567b65bb98fe86d9b51d90d748066
test "$(git -C <candidate-library> rev-parse HEAD:api)" = e9a5c5097aaca69bfbf7151603cbc3cb32449871
git -C <innertubex-repository> diff --quiet \
  68ba2a9627c7c85ec04968ef1d1649c2547a64f2 \
  cbfd76d98d0fd0f6017c84f8b3400ad1afe572ed -- src api
```

Do not substitute PR #12 head `59da579d6d8452f80ad4f8dbf57343b8b260a0c2`; its `src` tree contains a later, unmeasured SABR fix.

## Apply and compile the host patch

From this evidence checkout, apply `metrolist-live-benchmark.patch` only to the temporary app checkouts:

```bash
tools/live-evidence/prepare-metrolist-checkout.sh <baseline-app> <baseline-library>
tools/live-evidence/prepare-metrolist-checkout.sh <candidate-app> <candidate-library>

flock -x /tmp/metrolist-gradle.lock timeout 1800s <baseline-app>/gradlew -p <baseline-app> \
  :shared:desktopTest \
  --tests com.metrolist.shared.youtube.innertube.LivePremiumAbBenchmarkTest.runSanitizedPremiumAbBenchmark \
  -PuseLocalInnerTubeX --no-daemon --no-configuration-cache --max-workers=2 --no-parallel \
  -Dorg.gradle.jvmargs='-Xmx2048m -XX:MaxMetaspaceSize=512m' \
  -Pkotlin.compiler.execution.strategy=in-process
```

With the opt-in environment unset, the test exits without network access. This compile dry-run also proves the patched test is owned and compiled by the host app, not InnerTubeX.

## Run

The script checks every revision and candidate tree before network access. It alternates arm order, runs each content profile in a fresh process three times per arm, retains two repeats per process, performs three forced-SABR pairs, and applies the required delays and deadlines.

```bash
INNERTUBE_TOKEN_FILE=<cookie-file-path> \
  tools/live-evidence/run-targeted-reruns.sh \
  <baseline-app> <baseline-library> \
  <candidate-app> <candidate-library> \
  <report-dir>
```

The private cookie is read only in process memory by the existing live suite. The harness bounds the account-menu response to 2 MiB, retains only boolean authentication evidence and HTTP status, and never stores account fields. Raw Gradle logs use a private temporary directory and are removed.

## Derive

```bash
tools/live-evidence/derive_report.py --self-test
tools/live-evidence/derive_report.py \
  --root reports/live-evidence-validation-20260916
```

This regenerates normalized row CSV/JSON, every timing summary, sanitized provenance, and the Markdown report. Original observations remain unchanged under `original/`; corrected phase labels exist only in derived rows.
