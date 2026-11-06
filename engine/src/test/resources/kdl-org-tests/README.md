# kdl-org's KDL 2 test suite (vendored)

`input/` and `expected_kdl/` are kdl-org/kdl's `tests/test_cases/` as they are at commit
`89c1087d5e7f530de328f18b6a0fad54ca8ea227` (https://github.com/kdl-org/kdl/tree/89c1087d5e7f530de328f18b6a0fad54ca8ea227/tests/test_cases),
unchanged, byte for byte (`.gitattributes` keeps git from touching their line endings). `LICENSE.md` is that repository's
licence file, also unchanged.

**Licence.** These files are the work of the KDL contributors, licensed under the Creative Commons Attribution-ShareAlike
4.0 International License (CC BY-SA 4.0, http://creativecommons.org/licenses/by-sa/4.0/). That licence covers the files in
this folder only. It does not cover the parser that is tested against them (`engine/src/main/java/dev/launcher/engine/kdl/`)
or anything else in this repository.

**Use.** `dev.launcher.engine.kdl.KdlSuiteTest` parses every input. A case whose name ends `_fail` must be refused. Every
other case is written back with `KdlWriter` and compared with its file in `expected_kdl/`. Any case the test skips is
listed in the test itself with the reason.

To update: replace both folders from a newer commit, change the commit named above, and run the test.
