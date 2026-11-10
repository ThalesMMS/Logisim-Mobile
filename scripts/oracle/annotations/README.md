# Annotation and appearance desktop oracle

Build the current mobile core first, including the registered annotation definitions and the shared custom-appearance binder:

```sh
./gradlew :core:jvmTest --tests '*AnnotationCircRoundTripTest' :core:jvmJar
scripts/oracle/annotations/run.sh /path/to/logisim-evolution-all.jar
```

The committed fixture contains multiline text with significant spaces, font and alpha color, a two-pixel embedded RGBA PNG, image sizing/cover/attribution, and two instances of a custom circuit with a north-facing anchor. The Java oracle calls the original desktop loader, compares every terminal and annotation bounds, reads the decoded image pixels, asks desktop Logisim to save, and imports that file back into the mobile codec. It checks that standard annotation attributes and embedded bytes survive. Mobile-only group membership is not asserted after a desktop save.

Output appears under `outputs/annotations-desktop`. This proves codec and desktop-reader behavior; it does not prove native picker/share/print delivery or canvas pointer/touch behavior.
