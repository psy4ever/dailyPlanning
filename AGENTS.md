# Repository workflow

- Use `dev` for subsequent feature work, fixes, commits, and pushes. Keep its upstream as `origin/dev`.
- `main` holds delivered snapshots. Update it when the user explicitly requests a release, merge, or full snapshot of the current project.
- Keep real credentials, private keys, database files, local configuration, and build output out of Git; follow `.gitignore`.
- A Git push publishes source changes. Deploying the backend or installing an updated APK is a separate action.
- Keep documentation explicit about implemented behavior, verified deployment results, and planned features.
