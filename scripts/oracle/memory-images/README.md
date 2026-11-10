# Check memory image compatibility

Build the mobile core and the desktop fat JAR with JDK 21, then run:

```sh
./gradlew :core:jvmJar
./scripts/oracle/memory-images/run.sh /path/to/logisim-evolution-all.jar
```

The script compares 2,464 words in both directions. It writes and reads every format exposed by the desktop memory file chooser, using 1, 3, 8, 10, 16, 32, and 64-bit words. Files and the result are saved under `outputs/memory-image-oracle/`. The two JAR hashes identify the tested artifacts.

Override the mobile core JAR with the second argument. Set `KOTLIN_STDLIB_JAR` if the matching standard library is outside the Gradle cache.

This check covers valid images. Unit tests cover invalid tokens, width overflow, capacity limits, odd bit packing, sparse contents, and atomic editor operations. The mobile decoder rejects invalid input instead of accepting the desktop dialog's optional lossy conversion.
