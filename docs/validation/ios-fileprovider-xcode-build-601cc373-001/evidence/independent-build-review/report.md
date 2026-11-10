# Fixture 601 build: read-only review

**Static byte/log checks pass. Prefix review remains pending.** This is not approval to install or register the provider. No build, signing, installation, Simulator API or target execution was performed by this review. Only the generated producer and retained logs were read.

Manifest source: `601cc3733be5aafe4e4d6ef5b565ffb337eeb1cd`; manifest SHA-256 `c2e976f7ed41dfa60fcd48ef8a4e8defcaadbb7f8042ebd08669dbdf4b778159`. Both bundle tree hashes were independently recomputed and equal the manifest: app `e24e3fb58b07439ba1f9e50e955c76db72509dbf07ba0d8ea19ecce7f3f038d5`, provider `e91733f45ac08a55374bce04083b333004ae4eda1d7bd83f758f84956ecc9f7f`. No embedded provisioning profile was found in either inventory.

The **main executables** own both `__TEXT,__entitlements` and `__TEXT,__ents_der`; the debug dylibs own neither. App XML/DER section sizes are 440/191 bytes; provider sizes are 449/200 bytes. The image slices, retained extraction files, original generated `.xcent`/`.xcent.der` files and their retained copies agree byte for byte. An independent bounded DER decode equals the XML dictionary.

Each dictionary contains the exact single App Group plus exactly one extra: `application-identifier = P4VGX44GD8.<that bundle identifier>`. This prefix is an observed generated value. Its origin is not established here and it is **not approved as a Team identifier**. Both source entitlement files are verified against the exported source hashes and contain only the App Group. The recorded Xcode command sets manual ad-hoc identity `-` and an empty development team.

Both main CodeDirectories are ad hoc, have no TeamIdentifier field, and their recorded code-page digests independently match the source bytes. Retained signature logs also report ad hoc and no TeamIdentifier. Code-signature entitlements are empty dictionaries; the App Group and extra application identifier are in the simulated Mach-O sections. These observations do not establish runtime trust, GroupContainers registration, provider discovery or the prefix's provenance.

The retained build log contains the actual packaging and DER steps and linker `-sectcreate` arguments for both main executable section pairs. The builder's post-build command list contains only signature display/verification operations, without replacement signing. See `log-review.md` for the independent log pass and `byte-review.json` for offsets, section owners, file inventories and hashes; `source-and-commands.json` retains exact input dictionaries and CLI.

Original producer files were not modified. Native execution and domain registration remain `NOT_RUN`; the original failed fixtures are unchanged evidence.
