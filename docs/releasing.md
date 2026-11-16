# Releasing

Releases are built and published by [`.github/workflows/release.yml`](../.github/workflows/release.yml) when a
tag `vX.Y.Z` is pushed. For a release of version `X.Y.Z`:

1. On `main`, check that `<version>` in the three `pom.xml` files (root, `opencanard-server`,
   `opencanard-jdbc-driver`) is `X.Y.Z`, and that `CHANGELOG.md` has a `## [X.Y.Z] - YYYY-MM-DD` section
   with the release date (the workflow uses that section as the release notes).
2. Optional dry run: run the **Release** workflow by hand from the Actions tab. It builds and tests everything,
   including the Docker image, keeps the files as a workflow artifact, and publishes nothing.
3. Tag and push:

   ```bash
   git tag vX.Y.Z
   git push origin vX.Y.Z
   ```

The workflow refuses a tag that does not match the version in `pom.xml`. It runs the integration tests
(including the TLS reverse-proxy test), then publishes:

- a GitHub release with `opencanard-X.Y.Z.zip`, `opencanard-jdbc-driver-X.Y.Z-all.jar`, the DBeaver plugin and
  `SHA256SUMS`;
- the Docker image `ghcr.io/<owner>/opencanard:X.Y.Z`, and `:latest`.

A version with a suffix (`0.2.0-rc1`, tag `v0.2.0-rc1`) is published as a prerelease and does not move `latest`.
The first push of the image creates a private package on GitHub; make it public in the package settings if the
image should be pullable without signing in.

After the release, set the next version in the `pom.xml` files and add a new `## [Unreleased]` section to
`CHANGELOG.md`.
