# Revisão estática dos artefatos DIAG969 e 601

Os 196 checks de integridade passaram. O índice completo encontrou uma lacuna de cobertura nos manifestos. Os dois `__preview.dylib` também contêm o par de seções de entitlement. Seus bytes são iguais aos das seções dos executáveis principais e dos inputs de empacotamento. Os manifestos listam apenas main/debug, de modo que `sectionOwners: [main]` vale somente nesse escopo.

Os hashes foram recalculados dos arquivos originais com Python. Nenhum código do app ou provider foi executado. O JSON contém todos os arquivos regulares recursivos com caminho relativo, bytes e SHA-256. A árvore do app inclui o provider. Não há symlinks ou entradas especiais nos bundles.

## DIAG969

Manifesto: `0b8a220201e98ee40e5f0b8a4b56fda97464d97719ae3804f493d3dff8169763`.

| Bundle | Arquivos regulares | SHA-256 da árvore | SHA-256 do executável |
|---|---:|---|---|
| app | 11 | `dc6dd5ac1115cc15d806ebd9330074078b73dcf8352b3907f3b4698995529d04` | `5556f1c823a796b2cf44492e7f8f68afd20ba835dcb57d8a663b246abdeef703` |
| provider | 5 | `0224d5c6f1add718783039f979e1c40c494baadb3246fdc7caec765c6a506f0d` | `a51c80600d2ef3ac3a95b429254523038ad4caf7620834652d2cea696199083c` |

Os quatro slices dos executáveis principais coincidem byte a byte com os quatro dumps retidos, os quatro inputs gerados retidos e os quatro `.xcent` originais. XML e DER contêm o mesmo dicionário. Os dylibs de debug não contêm essas seções.

| Bundle | Imagem | Seção | Offset | Bytes | SHA-256 |
|---|---|---|---:|---:|---|
| App | main | `__entitlements` | 13124 | 440 | `279c91edc22ba362aeb82088100a50ce07e0b8710de79a13eea38a1737898d10` |
| App | main | `__ents_der` | 13564 | 191 | `65b1e6a3efe8427d4fa9c133701e5c0bc6294b51e57d1981a706ebf1c696cc96` |
| App | `__preview.dylib` | `__entitlements` | 824 | 440 | `279c91edc22ba362aeb82088100a50ce07e0b8710de79a13eea38a1737898d10` |
| App | `__preview.dylib` | `__ents_der` | 1264 | 191 | `65b1e6a3efe8427d4fa9c133701e5c0bc6294b51e57d1981a706ebf1c696cc96` |
| Provider | main | `__entitlements` | 10018 | 449 | `db38978790025ed2c514445b0dcb62286331f01cb5a580982c12def85896118a` |
| Provider | main | `__ents_der` | 10467 | 200 | `6dd8ac249508fe2e9e1aef11c16b43774d0bf00cfca0a04080a1fb74415226a3` |
| Provider | `__preview.dylib` | `__entitlements` | 824 | 449 | `db38978790025ed2c514445b0dcb62286331f01cb5a580982c12def85896118a` |
| Provider | `__preview.dylib` | `__ents_der` | 1273 | 200 | `6dd8ac249508fe2e9e1aef11c16b43774d0bf00cfca0a04080a1fb74415226a3` |

Os entitlements de fonte de ambos os bundles contêm somente o App Group `group.thalesmms.logisim.validation.revocationfixture`. O único campo adicional gerado é `application-identifier`. Os valores literais são:

- App: `P4VGX44GD8.thalesmms.logisim.validation.revocationfixture`.
- Provider: `P4VGX44GD8.thalesmms.logisim.validation.revocationfixture.provider`.

Não há `com.apple.developer.team-identifier` nos dicionários XML e DER lidos. O prefixo literal `P4VGX44GD8.` e os bundle IDs coincidem com o artefato 601. Isso descreve os bytes, sem aprovar uso futuro.

## Estabilidade do original 601

O manifesto continua com SHA-256 `c2e976f7ed41dfa60fcd48ef8a4e8defcaadbb7f8042ebd08669dbdf4b778159`. O índice completo dos bundles 601 também está no JSON.

| Bundle | Arquivos regulares | SHA-256 da árvore |
|---|---:|---|
| app | 11 | `e24e3fb58b07439ba1f9e50e955c76db72509dbf07ba0d8ea19ecce7f3f038d5` |
| provider | 5 | `e91733f45ac08a55374bce04083b333004ae4eda1d7bd83f758f84956ecc9f7f` |

As árvores e executáveis 601 coincidem com o manifesto do produtor; as árvores também coincidem com os pins recebidos. Os quatro hashes das seções XML/DER main e seus dicionários são iguais aos do DIAG969. Os previews 601 também contêm o mesmo par, com bytes iguais aos de main e dos inputs. Ambos os artefatos foram relidos ao final, e os manifestos e índices completos permaneceram iguais. Os arquivos de fonte exportados em `Fixture/` coincidem com os hashes registrados nos respectivos manifestos. Nenhum arquivo do produtor foi alterado por esta revisão.

## Limites da observação

Os manifestos declaram `nativeExecution: NOT_RUN`, `domainRegistration: NOT_RUN` e `physicalDevice: false`. Essas declarações não são observações de dispositivo. A ausência de instalação não foi observada. Esta revisão não realizou execução nativa, instalação, registro de domínio, build, assinatura ou queries com ferramentas Apple. Não reutilizou aprovação do 601 e não concede aprovação para uma execução futura.

Somente `index.json` e `review.md` foram escritos neste diretório. Não foram copiados binários. Pequenos cabeçalhos Mach-O e hashes de ranges dos load commands no JSON documentam a leitura estática.
