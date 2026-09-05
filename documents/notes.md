# Working Notes (for Claude)

Notes to self about how to work on this project, and other context that doesn't belong in the README or design docs.

- The user (Ambeco) wrote the original base code; I've taken over implementation under their guidance. Don't assume I know the historical reasoning behind existing code — ask if it's unclear rather than guessing.
- Make reasonable, incremental commits as we progress, rather than one giant commit at the end.
- The user highly values comprehensive automated tests, run frequently. As of this writing (2026-09-05), the project does not yet have a test suite — this is a priority to establish, not just an afterthought.
- Default branch name for any new git repo: `main`, not `master`.
