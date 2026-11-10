# Referência SAP-1

`sap1.circ` é uma cópia integral do [projeto SAP-1-Logisim](https://github.com/ThalesMMS/SAP-1-Logisim/blob/ddb9dd86d601ac217157beedeb88464ee0128c87/circuits/sap1.circ), no commit `ddb9dd86d601ac217157beedeb88464ee0128c87`. A licença MIT original está em `SAP-1-LICENSE.txt`. O SHA-256 do circuito é `2103aae7364ef6bc96f8620d39e7223ce6e561b4cf51021e72a2e4479c962aac`.

O arquivo foi criado no Logisim-evolution 3.8.0. Contém sete circuitos, 156 componentes e 318 segmentos de fio. O programa gravado na ROM calcula `2 + 3 + 4 - 1 = 8`.

O teste JVM `Sap1IntegrationTest` importa o arquivo original, aplica um pulso à entrada `Reset` e avança o clock. O registro de saída está na posição `(600,340)` do circuito `main`. O barramento `Valor` fica sem sinal entre as transferências e não substitui o registro de saída como resultado final.

Um oráculo independente usa o simulador Java do Logisim-evolution. Com o JAR desktop `5.1.0dev-all`, o registro de saída começa em `00`, muda para `08` no passo 56 e conserva `08` até o passo 160. Cada passo alterna o clock uma vez. O oráculo não modifica o circuito.

O script `scripts/verify-sap1.sh` executa o teste mobile. Quando recebe o caminho de um JAR desktop, também executa os dois motores e compara os traços em `outputs/sap1-validation/`. A comparação validou 4.991 valores de pinos e 161 valores do registro de saída, sem diferenças.

```sh
./scripts/verify-sap1.sh /caminho/logisim-evolution-5.1.0dev-all.jar
```

O script procura a biblioteca Kotlin no cache do Gradle. `KOTLIN_STDLIB_JAR` permite indicar outro caminho. Os arquivos `comparison.json` e `manifest.json` da execução de referência registram os resultados e hashes dos artefatos usados.
