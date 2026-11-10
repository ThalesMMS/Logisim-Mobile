# .circ version corpus

These small circuits exercise version-specific defaults and whether desktop Logisim Evolution can reopen exports.

- `logisim-2.7.2.circ` uses the older profile with legacy pins.
- `evolution-3.6.1.circ` uses the historical multiplexer enable behavior.
- `holy-cross-3.8.0.circ` uses the Classic appearance associated with the `-HC` source suffix.
- `evolution-5.1.0.circ` uses current defaults and a version suffix without a hyphen.

The files retain additional `main` metadata and explicit identities to detect information loss on round trips. `scripts/verify-circ-compatibility.sh` reopens the exports in Logisim Evolution.

The [Logisim 2.7.1 corpus](original-2.7.1/README.md) compares execution against Carl Burch's original JAR.
