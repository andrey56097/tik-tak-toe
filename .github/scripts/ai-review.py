#!/usr/bin/env python3
"""Automated PR review via OpenRouter free models.

Reads the PR diff from pr.diff, sends it to OpenRouter, and writes a
Markdown review to ai_review.md for the workflow to post as a PR comment.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

api_key = os.environ.get("OPENROUTER_API_KEY", "")
if not api_key:
    print("::error::OPENROUTER_API_KEY is not set. Add it to GitHub Secrets.", file=sys.stderr)
    raise SystemExit(1)

pr_title = os.environ.get("PR_TITLE", "")

# Truncate the diff to stay within the model's context window. Free models
# have limited context, so be generous but bounded. Signal truncation so the
# model knows it's seeing a partial diff.
MAX_DIFF_CHARS = 60000

diff_path = "pr.diff"
try:
    with open(diff_path, "r", encoding="utf-8") as f:
        diff = f.read()
except FileNotFoundError:
    diff = ""

truncated = len(diff) > MAX_DIFF_CHARS
if truncated:
    diff = diff[:MAX_DIFF_CHARS]

prompt = (
    "You are a senior code reviewer for a Java 21 / Spring Boot 4 "
    "microservices project. Review the pull request diff below. Be concise "
    "and concrete.\n\n"
    "Respond with:\n"
    "- **Summary**: 1-2 sentences.\n"
    "- **Issues**: bullet list with severity (BLOCKING / WARNING / NIT), "
    "file and line when possible.\n"
    "- **Suggestions**: optional improvements.\n\n"
    f"PR title: {pr_title}\n\n"
    f"Diff{' (truncated)' if truncated else ''}:\n{diff}"
)

# Free models to try, in order (OpenRouter free tiers change often).
MODELS = [
    "google/gemma-4-31b-it:free",
    "google/gemma-4-26b-a4b-it:free",
    "openai/gpt-oss-20b:free",
]

# Max attempts per model before moving to the next one.
MAX_ATTEMPTS = 3
# Backoff (seconds) between attempts on rate-limit (HTTP 429).
BACKOFF = 10

review = None
last_error = ""
for model in MODELS:
    for attempt in range(MAX_ATTEMPTS):
        payload = {
            "model": model,
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": 2000,
        }
        req = urllib.request.Request(
            "https://openrouter.ai/api/v1/chat/completions",
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {api_key}",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=90) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            review = data["choices"][0]["message"]["content"]
            break
        except urllib.error.HTTPError as e:
            last_error = f"HTTP {e.code}: {e.read().decode('utf-8')[:300]}"
            if e.code == 429 and attempt < MAX_ATTEMPTS - 1:
                time.sleep(BACKOFF)
                continue
            break
        except Exception as e:
            last_error = str(e)
            if attempt < MAX_ATTEMPTS - 1:
                time.sleep(BACKOFF)
                continue
            break
    if review is not None:
        break

if review is None:
    # Fail the job instead of posting a "failed" comment — keeps PRs clean.
    print(f"::error::AI review failed: {last_error}", file=sys.stderr)
    raise SystemExit(1)

with open("ai_review.md", "w", encoding="utf-8") as f:
    f.write(f"### 🤖 AI Code Review\n\n{review}\n")
