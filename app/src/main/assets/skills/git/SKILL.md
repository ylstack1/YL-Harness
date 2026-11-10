---
name: git
description: Status, branch, commit, push or pull. Never force-push unless asked.
category: repo
---

# Git

Use the git tools first. Push only when asked.

## When to use
- status, diff, commit, branch, pull, push, clone

## When not to use
- The workspace is not a repo and the user did not ask to init one

## Procedure
1. `git_status`. If git is missing, tell the user to install the Linux environment.
2. `git_diff` before you commit. Check: you know every path.
3. `git_commit` with a message that says why. It already stages all.
4. Push / pull / branch with `git_push`, `git_pull`, `git_checkout create=true`. Check: the remote output.
5. Pull request, only when asked: work on a feature branch, `git_commit`, `git_push`, then
   `github_create_pull_request` with a title and a short `summary`. It writes the description
   from commits, files and this chat's checks, so run the build/tests first. Share the link it returns.
6. Never `git push --force` or `--force-with-lease` unless the user typed that.

## Pitfalls
- Do not rewrite history to "clean up" without asking.
- Commit messages are sentences, not "update files".

## Verification
`git_status` matches what you told the user (clean, or the leftover files listed).
