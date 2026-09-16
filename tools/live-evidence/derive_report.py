#!/usr/bin/env python3
"""Normalize sanitized benchmark rows and derive every published timing table."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import statistics
from collections import Counter
from pathlib import Path

BASELINE = "13f8e4d36d249024cfe356c9cd67cd7fd8ee6493"
CANDIDATE = "68ba2a9627c7c85ec04968ef1d1649c2547a64f2"
APP = "8835a0c374cf1c6efbb0e1f1150434db4672d5f3"
CASES = ("normal", "explicit", "kids")
ARMS = ("baseline", "candidate_control", "candidate_entitlement_hypothesis")
METRICS = ("resolve_ms", "first_media_ms", "time_to_first_media_ms", "cache_ms")


def median(values: list[int]) -> int | float:
    value = statistics.median(values)
    return int(value) if float(value).is_integer() else value


def stats(rows: list[dict], metric: str) -> dict:
    values = [row[metric] for row in rows if row.get(metric) is not None]
    if not values:
        return {"n": 0, "median": None, "min": None, "max": None}
    return {"n": len(values), "median": median(values), "min": min(values), "max": max(values)}


def row_stats(rows: list[dict]) -> dict:
    return {metric: stats(rows, metric) for metric in METRICS}


def original_arm(name: str) -> str:
    if name.startswith("baseline"):
        return "baseline"
    if name.endswith("_control"):
        return "candidate_control"
    return "candidate_entitlement_hypothesis"


def original_kind(name: str) -> str:
    if "forced_sabr" in name:
        return "forced_sabr"
    if "prewarm" in name:
        return "prewarm"
    return "primary"


def normalize_original(original_dir: Path) -> list[dict]:
    rows: list[dict] = []
    for path in sorted(original_dir.glob("playback-ab-*.json")):
        document = json.loads(path.read_text())
        implementation = document["implementation"]
        for index, source_row in enumerate(document["rows"]):
            row = dict(source_row)
            row["source_file"] = path.name
            row["implementation"] = implementation
            row["arm"] = original_arm(implementation)
            row["evidence_kind"] = original_kind(implementation)
            row["source_phase"] = row.pop("phase")
            row["process_phase"] = "process-first" if index == 0 else "process-warm"
            row["profile_phase"] = "profile-first" if row["iteration"] == 1 else "profile-repeat"
            rows.append(row)
    return rows


def normalize_reruns(rerun_dir: Path) -> tuple[list[dict], list[dict]]:
    rows: list[dict] = []
    documents: list[dict] = []
    for path in sorted(rerun_dir.glob("playback-ab-*.json")):
        document = json.loads(path.read_text())
        document["source_file"] = path.name
        documents.append(document)
        for source_row in document["rows"]:
            row = dict(source_row)
            for key in (
                "source_file",
                "implementation",
                "measured_at_utc",
                "app_revision",
                "library_revision",
                "arm",
                "pair_index",
                "order_position",
                "premium_hint_role",
                "premium_hint_requested",
                "premium_hint_applied",
                "authentication_verified",
            ):
                row[key] = document[key]
            row["evidence_kind"] = "forced_sabr" if document["forced_profile"] else "paired_profile"
            rows.append(row)
    return rows, documents


def grouped(rows: list[dict], predicate) -> list[dict]:
    return [row for row in rows if predicate(row)]


def original_summary(rows: list[dict]) -> dict:
    primary = [row for row in rows if row["evidence_kind"] == "primary"]
    result = {"all_rows": {}, "profile_repeats": {}}
    for case in CASES:
        result["all_rows"][case] = {}
        result["profile_repeats"][case] = {}
        for arm in ARMS:
            selected = grouped(primary, lambda row, c=case, a=arm: row["type"] == c and row["arm"] == a)
            result["all_rows"][case][arm] = row_stats(selected)
            repeats = [row for row in selected if row["profile_phase"] == "profile-repeat"]
            result["profile_repeats"][case][arm] = row_stats(repeats)
    return result


def rerun_summary(rows: list[dict]) -> dict:
    direct = [row for row in rows if row["evidence_kind"] == "paired_profile"]
    result = {"profile_first": {}, "profile_repeats": {}, "matched_deltas": {}}
    rerun_arms = ("baseline", "candidate_entitlement_hypothesis")
    for case in CASES:
        result["profile_first"][case] = {}
        result["profile_repeats"][case] = {}
        result["matched_deltas"][case] = {}
        for arm in rerun_arms:
            selected = grouped(direct, lambda row, c=case, a=arm: row["type"] == c and row["arm"] == a)
            result["profile_first"][case][arm] = row_stats(
                [row for row in selected if row["profile_phase"] == "profile-first"]
            )
            result["profile_repeats"][case][arm] = row_stats(
                [row for row in selected if row["profile_phase"] == "profile-repeat"]
            )
        for phase in ("profile-first", "profile-repeat"):
            deltas: dict[str, list[int]] = {metric: [] for metric in METRICS}
            for pair in ("1", "2", "3"):
                for iteration in (1, 2, 3):
                    baseline = next(
                        (
                            row
                            for row in direct
                            if row["type"] == case
                            and row["arm"] == "baseline"
                            and row["pair_index"] == pair
                            and row["iteration"] == iteration
                            and row["profile_phase"] == phase
                        ),
                        None,
                    )
                    candidate = next(
                        (
                            row
                            for row in direct
                            if row["type"] == case
                            and row["arm"] == "candidate_entitlement_hypothesis"
                            and row["pair_index"] == pair
                            and row["iteration"] == iteration
                            and row["profile_phase"] == phase
                        ),
                        None,
                    )
                    if baseline is None or candidate is None:
                        continue
                    for metric in METRICS:
                        if baseline.get(metric) is not None and candidate.get(metric) is not None:
                            deltas[metric].append(candidate[metric] - baseline[metric])
            result["matched_deltas"][case][phase] = {
                metric: {
                    "n": len(values),
                    "median": median(values),
                    "min": min(values),
                    "max": max(values),
                }
                for metric, values in deltas.items()
            }
    return result


def sabr_summary(rows: list[dict]) -> dict:
    sabr = [row for row in rows if row["evidence_kind"] == "forced_sabr"]
    result = {}
    for arm in ("baseline", "candidate_entitlement_hypothesis"):
        selected = [row for row in sabr if row["arm"] == arm]
        identities = {
            (row["profile"], row["itag"], row["codecs"], row["bitrate"], row["sample_rate"], row["audio_channels"])
            for row in selected
        }
        result[arm] = {
            "n": len(selected),
            "first_media_successes": sum(row.get("media_status") in range(200, 300) for row in selected),
            "seek_successes": sum(
                row.get("seek_position_ms") is not None and 14_000 <= row["seek_position_ms"] <= 17_000
                for row in selected
            ),
            "decode_30s_successes": sum(row.get("error") is None and row.get("decoded_30s_ms") is not None for row in selected),
            "failure_categories": dict(Counter(row.get("error") or "none" for row in selected)),
            "decoded_position_ms": stats(selected, "decoded_position_ms"),
            "seek_position_ms": stats(selected, "seek_position_ms"),
            "time_to_first_media_ms": stats(selected, "time_to_first_media_ms"),
            "format_identities": [list(identity) for identity in sorted(identities)],
        }
    return result


def authentication_summary(documents: list[dict]) -> dict:
    return {
        "runs": len(documents),
        "credentials_present": sum(document["credentials_present"] for document in documents),
        "account_menu_http_200": sum(document["authentication_endpoint_status"] == 200 for document in documents),
        "authenticated_account_menu_observed": sum(document["authenticated_account_menu_observed"] for document in documents),
        "authentication_verified": sum(document["authentication_verified"] for document in documents),
        "premium_entitlement": sorted({document["premium_entitlement"] for document in documents}),
    }


def build_provenance(documents: list[dict], harness: Path) -> dict:
    run_order = sorted(documents, key=lambda document: document["measured_at_utc"])
    return {
        "measured_at_utc": {
            "first": run_order[0]["measured_at_utc"],
            "last": run_order[-1]["measured_at_utc"],
        },
        "revisions": {
            "metrolist_kmp": APP,
            "baseline": BASELINE,
            "original_pr_11_candidate": CANDIDATE,
            "measured_candidate_snapshot": "cbfd76d98d0fd0f6017c84f8b3400ad1afe572ed",
            "unmeasured_later_pr_12_head": "59da579d6d8452f80ad4f8dbf57343b8b260a0c2",
        },
        "tree_identity": {
            "baseline": {
                "src": "db670237f791fcb132ab2c178e29056f802e263c",
                "api": "b04a3d599560cdbf7f65f1645c7a3854a2814a70",
            },
            "original_pr_11_candidate": {
                "src": "c31020cbf5a567b65bb98fe86d9b51d90d748066",
                "api": "e9a5c5097aaca69bfbf7151603cbc3cb32449871",
            },
            "measured_candidate_snapshot": {
                "src": "c31020cbf5a567b65bb98fe86d9b51d90d748066",
                "api": "e9a5c5097aaca69bfbf7151603cbc3cb32449871",
            },
            "later_pr_12_head": {
                "src": "2312ea1fadb49deba78f0427764fa3a3972b3c27",
                "api": "e9a5c5097aaca69bfbf7151603cbc3cb32449871",
            },
        },
        "original_observations": {
            "row_commit": "fef23f327344186f262c9da0f7b9145895da95dd",
            "sanitized_provenance_commit": "3d0fe40ca1e30f758f60e847d13602d2cabfe756",
            "authentication_boolean_is_not_reused": True,
        },
        "harness": {
            "artifact": "tools/live-evidence/metrolist-live-benchmark.patch",
            "host_target": "shared/src/desktopTest/kotlin/com/metrolist/shared/youtube/innertube/LivePremiumAbBenchmarkTest.kt",
            "sha256": hashlib.sha256(harness.read_bytes()).hexdigest(),
            "opt_in": True,
            "production_changes": False,
            "account_menu_body_limit_bytes": 2 * 1024 * 1024,
            "resolve_timeout_ms": 30_000,
            "first_media_timeout_ms": 30_000,
            "decode_timeout_ms": 60_000,
            "sample_timeout_ms": 120_000,
            "decode_seconds": 30,
            "seek_target_ms": 15_000,
            "playback_clock_lead_ms": 250,
            "post_run_changes": "ktlint-only formatting, equivalent constant concatenation, and host-patch packaging",
        },
        "method": {
            "audio_quality": "HIGH",
            "direct_cases": list(CASES),
            "profile_pairs_per_case": 3,
            "profile_iterations_per_process": 3,
            "profile_first_rows_per_arm_case": 3,
            "profile_repeat_rows_per_arm_case": 6,
            "forced_sabr_profile": "WEB_REMIX_SABR",
            "forced_sabr_pairs": 3,
            "stream_delay_seconds_minimum": 10,
            "group_delay_seconds_minimum": 20,
            "order": "alternating baseline and candidate by case and pair",
            "candidate_arm": "explicit entitlement hypothesis",
            "premium_entitlement": "not observed",
        },
        "reproduction": {
            "candidate_fetch": "git fetch origin pull/11/head:test/pr-11-original",
            "cookie_file": "<cookie-file-path>",
            "runner": "INNERTUBE_TOKEN_FILE=<cookie-file-path> tools/live-evidence/run-targeted-reruns.sh <baseline-app> <baseline-library> <candidate-app> <candidate-library> <report-dir>",
            "gradle_lock": "/tmp/metrolist-gradle.lock",
            "gradle_flags": "--no-daemon --no-configuration-cache --max-workers=2 --no-parallel -Dorg.gradle.jvmargs='-Xmx2048m -XX:MaxMetaspaceSize=512m' -Pkotlin.compiler.execution.strategy=in-process",
        },
        "sanitization": {
            "retained": ["boolean authentication evidence", "HTTP status", "aggregate timing", "format identity", "failure category"],
            "excluded": ["cookie", "account fields", "media identifiers", "titles", "request headers", "PO tokens", "signed URLs", "raw responses"],
            "private_logs_committed": False,
        },
        "run_order": [
            {
                "measured_at_utc": document["measured_at_utc"],
                "implementation": document["implementation"],
                "arm": document["arm"],
                "pair_index": document["pair_index"],
                "order_position": document["order_position"],
                "library_revision": document["library_revision"],
            }
            for document in run_order
        ],
    }


def write_rows(path: Path, rows: list[dict]) -> None:
    path.with_suffix(".json").write_text(json.dumps({"rows": rows}, indent=2, sort_keys=True) + "\n")
    fields = sorted({key for row in rows for key in row})
    with path.with_suffix(".csv").open("w", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def value(summary: dict, metric: str = "time_to_first_media_ms") -> str:
    item = summary[metric]
    return f'{item["median"]} [{item["min"]}..{item["max"]}] (n={item["n"]})'


def sabr_failures(item: dict) -> Counter:
    return Counter({name: count for name, count in item["failure_categories"].items() if name != "none"})


def format_sabr_failures(item: dict) -> str:
    failures = sabr_failures(item)
    return ", ".join(f"`{name}` ({count})" for name, count in sorted(failures.items())) or "none"


def sabr_result(sabr: dict) -> str:
    items = list(sabr.values())
    total = sum(item["n"] for item in items)
    first_media = sum(item["first_media_successes"] for item in items)
    seeks = sum(item["seek_successes"] for item in items)
    decodes = sum(item["decode_30s_successes"] for item in items)
    failures = sum((sabr_failures(item) for item in items), Counter())
    outcome = f"{decodes}/{total} paced 30 s decodes completed"
    if failures:
        observed = ", ".join(f"`{name}` ({count})" for name, count in sorted(failures.items()))
        outcome += f"; observed failures: {observed}"
    return f"- Forced WEB_REMIX_SABR first media succeeded {first_media}/{total}, 15 s seeks succeeded {seeks}/{total}, and {outcome}."


def sabr_detail(sabr: dict) -> str:
    items = list(sabr.values())
    total = sum(item["n"] for item in items)
    identities = {tuple(identity) for item in items for identity in item["format_identities"]}
    if len(identities) == 1:
        profile, itag, codecs, bitrate, sample_rate, channels = identities.pop()
        channel_label = {1: "mono", 2: "stereo"}.get(channels, f"{channels} channels")
        identity = (
            f"Both arms selected `{profile}`, itag {itag}, {codecs}, {bitrate} bps, "
            f"{sample_rate / 1000:g} kHz, {channel_label}."
        )
    else:
        identity = "SABR format identities differed by arm; exact identities are retained in `derived/summary.json`."

    seek_positions = [item["seek_position_ms"] for item in items if item["seek_position_ms"]["n"]]
    seek_successes = sum(item["seek_successes"] for item in items)
    if seek_positions and len({position["median"] for position in seek_positions}) == 1:
        seek = f'Validated seeks succeeded {seek_successes}/{total} at median {seek_positions[0]["median"]} ms.'
    else:
        seek = f"Validated seeks succeeded {seek_successes}/{total}; per-arm positions are retained in `derived/summary.json`."

    decodes = sum(item["decode_30s_successes"] for item in items)
    failures = sum((sabr_failures(item) for item in items), Counter())
    if failures:
        observed = ", ".join(f"`{name}` ({count})" for name, count in sorted(failures.items()))
        outcome = (
            "The old frozen-clock harness defect is corrected; paced playback observed "
            f"{observed}, with {decodes}/{total} 30 s decodes completing."
        )
    else:
        outcome = f"The corrected paced harness completed {decodes}/{total} 30 s decodes without a recorded failure."
    return f"{identity} {seek} {outcome} First-media success is not complete-playback evidence, and no production fix is claimed here."


def render_report(summary: dict) -> str:
    original = summary["original"]
    rerun = summary["rerun"]
    sabr = summary["sabr"]
    auth = summary["authentication"]
    lines = [
        "# Live playback evidence validation",
        "",
        "Measured 2026-09-16. This evidence-only report supersedes the methodology and reporting claims in PR #12; it does not include PR #12's later SABR production fix.",
        "",
        "## Result",
        "",
        f'- Authentication is now established for the rerun: all {auth["authentication_verified"]}/{auth["runs"]} bounded probes had credentials present, HTTP 200, and a parseable authenticated account-menu marker. No account details were retained.',
        '- Premium entitlement was **not observed**. Every candidate Premium value below is an explicit entitlement hypothesis, never a cookie inference or caller confirmation.',
        '- The negative normal-song result remains. Exact-format profile repeats were materially slower on the original PR #11 candidate because it selected WEB_REMIX instead of baseline VISIONOS.',
        sabr_result(sabr),
        '- A faster removable cross-platform token runtime is still unproven. Existing Android WebView, iOS WKWebView, and optional desktop WebView already share the common page-bound minter; QuickJS 1.0.14 remains only a disabled-by-default proof-of-concept candidate.',
        "",
        "## Exact revisions",
        "",
        f'- InnerTubeX baseline: `{BASELINE}`',
        f'- Original PR #11 candidate: `{CANDIDATE}`',
        '- Measured candidate snapshot `cbfd76d98d0fd0f6017c84f8b3400ad1afe572ed` has identical `src` and `api` trees to the original PR #11 revision.',
        '- PR #12 head `59da579d6d8452f80ad4f8dbf57343b8b260a0c2` contains a later SABR fix and was not measured as the candidate.',
        f'- Metrolist-KMP harness host: `{APP}`',
        "",
        "## Original observations, correctly classified",
        "",
        "The raw source rows are immutable under `original/`. Their old `phase` field is retained as `source_phase` in the normalized rows, while `process_phase` and `profile_phase` are derived independently. A profile's first row is not called warm merely because another profile ran earlier. Client-level caches can still carry across profiles when they share a client.",
        "",
        "All-row totals below preserve the observed three-row medians and ranges. They are directional observations, not significance claims.",
        "",
        "| Case | Baseline | Candidate control | Candidate entitlement hypothesis |",
        "|---|---:|---:|---:|",
    ]
    for case in CASES:
        lines.append(
            f'| {case} | {value(original["all_rows"][case]["baseline"])} | '
            f'{value(original["all_rows"][case]["candidate_control"])} | '
            f'{value(original["all_rows"][case]["candidate_entitlement_hypothesis"])} |'
        )
    lines += [
        "",
        "Profile-repeat-only totals use iterations 2 and 3, explicitly `n=2`:",
        "",
        "| Case | Baseline | Candidate control | Candidate entitlement hypothesis |",
        "|---|---:|---:|---:|",
    ]
    for case in CASES:
        lines.append(
            f'| {case} | {value(original["profile_repeats"][case]["baseline"])} | '
            f'{value(original["profile_repeats"][case]["candidate_control"])} | '
            f'{value(original["profile_repeats"][case]["candidate_entitlement_hypothesis"])} |'
        )
    lines += [
        "",
        "## Balanced targeted rerun",
        "",
        "Each content case ran in three fresh processes per arm. Every process had one profile-first row and two profile-repeat rows, with baseline/candidate order alternated. Streams retained at least 10 s spacing and profile groups at least 20 s spacing. Values are total-to-first-media median [range].",
        "",
        "| Case | Phase | Baseline | Candidate entitlement hypothesis | Matched candidate minus baseline |",
        "|---|---|---:|---:|---:|",
    ]
    for case in CASES:
        for key, label in (("profile_first", "process-first / profile-first"), ("profile_repeats", "process-warm / profile-repeat")):
            phase = "profile-first" if key == "profile_first" else "profile-repeat"
            delta = rerun["matched_deltas"][case][phase]["time_to_first_media_ms"]
            lines.append(
                f'| {case} | {label} | {value(rerun[key][case]["baseline"])} | '
                f'{value(rerun[key][case]["candidate_entitlement_hypothesis"])} | '
                f'{delta["median"]} [{delta["min"]}..{delta["max"]}] (n={delta["n"]}) ms |'
            )
    lines += [
        "",
        "All matched rows kept the same per-case itag 251 Opus identity, 48 kHz sample rate, and bitrate across arms. Normal remained 141473 bps, explicit 151216 bps, and kids 148600 bps. The normal regression was not obtained by lowering candidate quality.",
        "",
        "## Forced SABR decode and seek",
        "",
        "The corrected harness advances the decoder playback clock for every PCM frame, paces it within 250 ms of wall time, places the 15 s seek before the long decode, bounds each decode to 60 s and each sample to 120 s, and closes resources on interruption. External cancellation still propagates.",
        "",
        "| Arm | Runs | First media | 15 s seek | 30 s decode | Decoded position | Failure |",
        "|---|---:|---:|---:|---:|---:|---|",
    ]
    for arm in ("baseline", "candidate_entitlement_hypothesis"):
        item = sabr[arm]
        position = f'{item["decoded_position_ms"]["median"]} ms' if item["decoded_position_ms"]["n"] else "n/a"
        lines.append(
            f'| {arm} | {item["n"]} | {item["first_media_successes"]}/{item["n"]} | '
            f'{item["seek_successes"]}/{item["n"]} | {item["decode_30s_successes"]}/{item["n"]} | '
            f'{position} | {format_sabr_failures(item)} |'
        )
    lines += [
        "",
        sabr_detail(sabr),
        "",
        "## Sidecar conclusion",
        "",
        "PR #13's feasibility report remains the applicable design evidence, with one correction: its cited live benchmark must use this report's phase labels and warm-state caveat. No QuickJS BotGuard implementation was benchmarked. Keep the desktop sidecar fallback until a disabled QuickJS adapter passes accepted-token, real-media, exact-quality, cancellation, expiry/session, packaged-target, latency, memory, and footprint gates.",
        "",
        "## Reproducibility and artifacts",
        "",
        "- `tools/live-evidence/metrolist-live-benchmark.patch`: privacy-safe opt-in patch compiled only in the host app; InnerTubeX source sets do not reference Metrolist classes.",
        "- `tools/live-evidence/prepare-metrolist-checkout.sh`: validates exact revisions, applies the host patch, and wires the temporary composite checkout.",
        "- `tools/live-evidence/run-targeted-reruns.sh`: exact balanced run order and bounded serialized Gradle invocation.",
        "- `tools/live-evidence/derive_report.py`: normalizes phases and regenerates all aggregate CSV, JSON, and Markdown outputs.",
        "- `original/`: immutable sanitized source observations from the first worker.",
        "- `reruns/`: sanitized per-process rows from the corrected harness.",
        "- `derived/`: normalized full rows and calculated summaries.",
        "",
        "Cookie contents, account fields, media identifiers, titles, request headers, PO tokens, signed URLs, and raw responses are absent. The published cookie path is `<cookie-file-path>` only.",
        "",
        "## Resolved review issues",
        "",
        "1. Split process-first/process-warm from profile-first/profile-repeat and recomputed repeat-only `n=2` summaries.",
        "2. Replaced SAPISID-plus-HTTP-200 inference with a bounded in-memory authenticated account-menu marker; Premium remains unobserved.",
        "3. Added clock progression, pacing, deadlines, seek-position validation, cancellation propagation, and cleanup to the harness. SABR outcomes and failure categories are rendered from the observed rows.",
        "4. Preserved a compilable opt-in host-app patch, exact checkout/fetch instructions, candidate tree identity, full row artifacts, and programmatic derivation without adding Metrolist dependencies to InnerTubeX.",
        "5. Kept sidecar feasibility separate from production and made no unsupported speed or removability claim.",
    ]
    return "\n".join(lines) + "\n"


def validate(documents: list[dict], rerun_rows: list[dict], original_rows: list[dict]) -> None:
    assert len(original_rows) == 42
    assert len([row for row in original_rows if row["evidence_kind"] == "primary"]) == 27
    assert len(documents) == 24
    assert all(document["app_revision"] == APP for document in documents)
    assert Counter(document["library_revision"] for document in documents) == Counter({BASELINE: 12, CANDIDATE: 12})
    assert all(document["authentication_verified"] for document in documents)
    assert len([row for row in rerun_rows if row["evidence_kind"] == "paired_profile"]) == 54
    assert len([row for row in rerun_rows if row["evidence_kind"] == "forced_sabr"]) == 6


def self_test() -> None:
    assert median([1, 9, 3]) == 3
    assert median([1, 2]) == 1.5
    assert stats([{"x": 4}, {"x": 2}, {"x": None}], "x") == {"n": 2, "median": 3, "min": 2, "max": 4}
    assert stats([{"x": None}], "x") == {"n": 0, "median": None, "min": None, "max": None}
    success = {
        "n": 3,
        "first_media_successes": 3,
        "seek_successes": 3,
        "decode_30s_successes": 3,
        "failure_categories": {"none": 3},
    }
    failure = {
        **success,
        "decode_30s_successes": 0,
        "failure_categories": {"decode-30s:eof:no-progress": 3},
        "seek_position_ms": {"n": 3, "median": 15_000, "min": 14_990, "max": 15_010},
        "format_identities": [["TEST_SABR", 251, "opus", 128_000, 48_000, 2]],
    }
    assert format_sabr_failures(success) == "none"
    rendered = sabr_result({"baseline": failure, "candidate": failure})
    detail = sabr_detail({"baseline": failure, "candidate": failure})
    assert "no-progress" in rendered and "attestation-required" not in rendered
    assert "no-progress" in detail and "attestation-required" not in detail


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("reports/live-evidence-validation-20260916"))
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return

    original_rows = normalize_original(args.root / "original")
    rerun_rows, documents = normalize_reruns(args.root / "reruns")
    validate(documents, rerun_rows, original_rows)
    summary = {
        "revisions": {"baseline": BASELINE, "candidate": CANDIDATE, "app": APP},
        "authentication": authentication_summary(documents),
        "original": original_summary(original_rows),
        "rerun": rerun_summary(rerun_rows),
        "sabr": sabr_summary(rerun_rows),
    }
    derived = args.root / "derived"
    derived.mkdir(parents=True, exist_ok=True)
    write_rows(derived / "original-normalized-rows", original_rows)
    write_rows(derived / "rerun-rows", rerun_rows)
    (derived / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
    harness = Path(__file__).with_name("metrolist-live-benchmark.patch")
    (args.root / "provenance.json").write_text(
        json.dumps(build_provenance(documents, harness), indent=2, sort_keys=True) + "\n"
    )
    (args.root / "LIVE-EVIDENCE-VALIDATION.md").write_text(render_report(summary))


if __name__ == "__main__":
    main()
