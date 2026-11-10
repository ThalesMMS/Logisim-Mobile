# Board editor compared with Evolution (#342)

- Mobile: `362f605c8b0e8f462211261d95d7e9263ba4c960` (`core-jvm.jar` sha256 `58c3e9f465006d8171475faef06c77e3a5923db5752ee990108ce06010091492`)
- Evolution: `d58a61e0c290361722b43df5b1a6b86c1f7c7f0d` (`logisim-evolution-5.1.0dev-all.jar` sha256 `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038`)
- Environment: macOS host, JDK 21 (`scripts/java-home.sh`), `java.awt.headless=true`. Host only.

## Command

```sh
. scripts/oracle/menus/classpath.sh EVOLUTION_JAR
javac -nowarn -cp "$MENUS_CP" -d CLASSES scripts/oracle/menus/BoardEditorOracle.java
java -Djava.awt.headless=true -cp "CLASSES:$MENUS_CP" BoardEditorOracle OUT \
  core/src/jvmTest/resources/fpga/GECKO5_EDUCATION.xml core/src/jvmTest/resources/fpga/BASYS3.xml \
  ../logisim-evolution/src/main/resources/resources/logisim/boards/DE0_CV.xml
```

The oracle loads each board, writes it with Evolution’s `BoardWriterClass` and Mobile’s `FpgaBoardWriter`, and reads both files with Evolution’s `BoardReaderClass`. It compares the FPGA, clock, unused pins, and image pixels, as well as each resource’s type, label, rectangle, I/O standard, pull, current, activity, and pins.

## Result

| Board | Resources | Evolution vs. Mobile `describe()` | Evolution vs. Mobile pixels | Evolution writer preserves source pixels |
| --- | --- | --- | --- | --- |
| `GECKO5_EDUCATION` | 30 | match (`SAME`) | match | no |
| `BASYS3` | 100 | match (`SAME`) | match | no |
| `DE0_CV` | 36 | match (`SAME`) | match | yes |

Both writers preserve the source `describe()` for all three boards. Evolution’s `BoardWriterClass` changes image pixels when resaving `GECKO5_EDUCATION` and `BASYS3` (`evolution-writer-preserves-pixels=false`). Mobile preserves the source pixels. See the full output in [`board-editor.txt`](board-editor.txt).

Before the fix (issue #342, campaign #332), Mobile wrote `FPGAPinPullBehavior="Float"` and `ActivityLevel="Active high"` for resources that left them unspecified. Evolution then read pull 0 and a defined activity where it would otherwise read 255 (unknown).

The focused run `./gradlew :core:jvmTest --tests 'dev.logisim.mobile.core.FpgaBoard*'` passed all 11 tests (`FpgaBoardWriterTest.keepsUndeclaredPullAndActivityAbsentLikeEvolution`).
