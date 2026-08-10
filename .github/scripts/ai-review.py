#!/usr/bin/env python3
"""Automated PR review via OpenRouter free models.

Reads the PR diff from pr.diff, sends it to OpenRouter, and writes a
Markdown review to ai_review.md for the workflow to post as a PR comment.
"""
import json
import os
import urllib.error
import urllib.request

api_key = os.environ["OPENROUTER_API_KEY"]
pr_title = os.environ.get("PR_TITLE", "")

diff_path = "pr.diff"
try:
    with open(diff_path, "r", encoding="utf-8") as f:
        diff = f.read()[:20000]  # truncate to keep the request small
except FileNotFoundError:
    diff = ""

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
    f"Diff:\n{diff}"
)

payload = {
    "model": "google/gemma-3-27b-it:free",
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
except urllib.error.HTTPError as e:
    review = f"AI review failed (HTTP {e.code}): {e.read().decode('utf-8')[:500]}"
except Exception as e:
    review = f"AI review failed: {e}"

with open("ai_review.md", "w", encoding="utf-8") as f:
    f.write(f"### 🤖 AI Code Review\n\n{review}\n")
