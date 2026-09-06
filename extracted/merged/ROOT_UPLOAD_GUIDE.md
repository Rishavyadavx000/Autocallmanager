# AutoCallManager V1.0.7 GitHub Root Package

Upload the CONTENTS of this folder to the GitHub repository root, not this ZIP as a single file.

The repository root must directly contain:
- settings.gradle.kts
- build.gradle.kts
- app/
- admin-server/
- .github/workflows/main.yml

Do not keep only a ZIP file in the repository. The workflow is intentionally root-first so both Android and Admin Panel jobs use the same checked-out source tree.
