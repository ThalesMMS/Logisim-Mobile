# Mounted visual-export qualification

Both mobile harnesses use `qualification.circ` and `contract.json`. The fixture derives from `examples/compatibility/annotations-symbols.circ`: it retains its embedded red/half-alpha-green image and custom-symbol geometry, labels the independent image/text, adds 72 symbol instances and a 6000×3600 world-coordinate wire frame, and renames the main circuit. The contract records both source hashes.

The frame fixes the whole drawing bounds independently of font metrics. White background, margin 10 and scale 0.25 produce a 1508×908 PNG, below the default raster limit. Selecting only `EXPORT34_IMAGE` with margin 10 and scale 1 produces a 140×100 image. The printable PDF remains a single A4 page containing that image; 140×100 is its raster size, not its page size.

The contract's color samples are interior pixels of the stored image or background. Independent platform decoders should allow the stated channel rounding tolerance. A harness must check the actual saved/received bytes and a recognized preview region. A callback or a fixture Activity that bypasses the shipping editor does not qualify this flow.

These files are test inputs and expected observations, not a runtime qualification receipt. Record the installed app/build hashes, fixture hash, output bytes/digests, environment and screenshots when running the mounted editor scenarios. Native share/print preview can be cancelled after observation; no physical printer or printer job is required.
