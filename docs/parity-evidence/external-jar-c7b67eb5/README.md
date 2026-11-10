# Evolution JAR library through the companion’s isolated runner (#349)

- Mobile: `c7b67eb5d89aa1592c98690dc2fddc74ec6c6bbf`
- Evolution: `d58a61e0c290361722b43df5b1a6b86c1f7c7f0d` (`logisim-evolution-5.1.0dev-all.jar` sha256 `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038`)
- Environment: macOS 27.0.1 arm64, `sandbox-exec` (perfil `macos-seatbelt-v1`), Homebrew OpenJDK 21.0.13. Host only.

## Command

```sh
python3 scripts/oracle/external-jar/run.py EVOLUTION_JAR --out docs/parity-evidence/external-jar-c7b67eb5
LOGISIM_COMPANION_EVOLUTION_JAR=EVOLUTION_JAR LOGISIM_TEST_JAR_JAVA=$JAVA_HOME/bin/java LOGISIM_TEST_DRIVE_JAR=drive.jar \
  python3 -m unittest -v companion.tests.test_jar_runtime companion.tests.test_host_isolation companion.tests.test_hdl_sim
```

The oracle compiles `companion/tests/fixtures/DriveLibrary.java` against the Evolution JAR (library SHA-256 in this receipt `f0c3b781…`, with a new compiled JAR on each run) and applies 144 frames to `jar-drive-contention.circ`. These cover all 0/1/?/E values and 0/1/? enables for the two drivers.

- Mobile uses the companion’s `jar-run` operation with planning, confirmation, and the macOS sandbox (`JarCircuitRunner`, with the JAR in its own class loader).
- `EvolutionJarOracle.java` opens the project with Evolution’s `Loader`, which loads `jar#external/drive.jar#…` through its JAR loading path.

## Result

- `PASS: 144/144 frames equal between the companion's isolated jar-run and Evolution's own JAR loader` ([log](logs/external-jar.txt), [results.json](results.json)). Outputs cover 0, 1, ?, and E.
- The companion ran 56 tests (JAR, isolation, and HDL) with status OK and 1 skip for the missing Gray library. `test_live_driver_trace_separates_contention_release_unknown_and_error` passed in the actual sandbox. [Test log](logs/companion-tests.txt).

## Cause

Homebrew’s JDK `libfontmanager.dylib` links to `harfbuzz`, `freetype`, and their dependencies outside the JDK. The profile denied access to these files, so the library constructor failed when loading AWT icons with `UnsatisfiedLinkError`, reported as `InvocationTargetException: null`. The Java grant now covers the exact non-system dylibs the JDK links transitively (`LC_LOAD_DYLIB`, `@rpath`, `@loader_path`) under every symlink spelling. The runner reports the constructor error directly. `test_host_isolation` measures AWT text in the sandbox and fails without the fix.
