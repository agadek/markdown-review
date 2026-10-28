# Contributing to Markdown Review

Thanks for helping. Bug reports with a small pair of Markdown files are as valuable as code, so if you are not sure
where to start, [open an issue](https://github.com/agadek/markdown-review/issues/new/choose) first. Issues labeled
[`good first issue`](https://github.com/agadek/markdown-review/labels/good%20first%20issue) are small and
self-contained.

## English only

Everything in the repository is in English: code, comments, README and other docs, test fixtures, UI strings and
commit messages. The plugin's user interface is English only as well (NFR-10 in
[docs/SPECIFICATION.md](docs/SPECIFICATION.md)), so there are no translations to keep in sync.

## Build and run

You need JDK 21; Gradle comes with the wrapper.

```
./gradlew test          # engine tests and headless IDE integration tests
./gradlew runIde        # a sandbox IntelliJ IDEA with the plugin installed
./gradlew buildPlugin   # the plugin zip in build/distributions/
./gradlew verifyPlugin  # the checks JetBrains Marketplace runs on an upload
```

CI runs `./gradlew test buildPlugin verifyPlugin` on every pull request, and a pull request needs it green. To try a
change, run `runIde`, open a project with a Markdown file under Git, change the file and open the diff from the Commit
window (Ctrl+D), or use **Compare Markdown Files...** from the project view's context menu.

## Where things are

- `pl.mdreview.engine`: the diff engine (blocks, word diff, moved blocks). It has no IntelliJ dependency and is
  tested with plain unit tests and fixtures.
- `pl.mdreview.render`: turns the engine's result into HTML and CSS.
- `pl.mdreview.ide`: the diff viewers, settings and actions on the IntelliJ Platform.
- [docs/ENGINE_CONTRACT.md](docs/ENGINE_CONTRACT.md) describes the contract between the engine and the IDE layer,
  [docs/SPECIFICATION.md](docs/SPECIFICATION.md) what the plugin does, and [docs/PHASE3.md](docs/PHASE3.md) what comes
  next.

## Fixtures

A fixture is a directory in `src/test/resources/fixtures/` with `old.md`, `new.md` and `EXPECTED.md`, the expected
block-level changes and word diff. `FixturesTest` runs every directory. Every confirmed rendering or diff bug gets a
fixture before it is fixed, cut down to the smallest pair that still shows the problem. The format and the list of
cases are in [src/test/resources/fixtures/README.md](src/test/resources/fixtures/README.md); add your case to its
table.

## Pull requests

1. Fork the repository and branch off `main`.
2. Keep one change per pull request, with tests: a fixture for engine and rendering changes, a test in
   `src/test/kotlin` for IDE behavior.
3. Add a line for users under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md) when the change is visible to them.
   The released sections become the change notes on JetBrains Marketplace.
4. Update the README and `docs/` if the change affects what they describe.
5. Fill in the pull request template.

Commit messages are short sentences in the imperative ("Fix the word diff for empty list items"), in English.

## License of contributions

Markdown Review is licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE), and companies can get a
[commercial license](docs/COMMERCIAL_LICENSE.md) through GitHub Sponsors. For that to keep working, the maintainer must
be able to license your contribution both ways. By opening a pull request you confirm that you wrote the change or
otherwise have the right to submit it, and you grant the maintainer a perpetual, worldwide, royalty-free license to
use, change and license your contribution under the PolyForm Noncommercial License and under commercial terms. You
keep the copyright of your contribution.

## Sponsoring

If you would rather support the project with money than with code, see
[GitHub Sponsors](https://github.com/sponsors/agadek) or [Ko-fi](https://ko-fi.com/gadek).
