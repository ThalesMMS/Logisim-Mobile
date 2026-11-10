# Circuitos de exemplo

O catálogo `catalog.json` descreve os arquivos, as entradas, os passos e os resultados de cada exemplo. `scripts/generate_examples.py` gera `BundledCircuitExamples.kt` a partir desses mesmos arquivos. O aplicativo e os testes usam `CircuitExamples`; não há uma segunda cópia do desenho AND escrita à mão.

No aplicativo, abra **Ferramentas → Ajuda e exemplos → Exemplos**. Leia o roteiro e confirme **Abrir uma cópia** para abrir um projeto no workspace, separado do arquivo externo original. O teste do cenário de referência usa uma cópia independente; ele não altera nem testa as suas edições no circuito aberto. O [guia de uso](../docs/user-guide.md) descreve os controles e o [guia de compatibilidade](../docs/compatibility-guide.md) explica o alcance dos resultados.

| Arquivo | Capacidade | Resultado declarado |
| --- | --- | --- |
| `and.circ` | Combinacional | A saída fica em 1 quando ambas as entradas estão em 1. |
| `d-flip-flop.circ` | Sequencial | Q captura D na borda de subida e obedece ao clear assíncrono. |
| `bus-adder.circ` | Barramento de oito bits | `FF + 01 = 00`, com carry em 1. |
| `hierarchy.circ` | Subcircuito | Um módulo inversor transforma 0 em 1 e 1 em 0. |
| `rom.circ` | Memória | Os endereços 0 a 3 leem `12`, `34`, `56` e `78`. |
| `sap1.circ` | Processador hierárquico | O programa calcula `2 + 3 + 4 - 1 = 8`. |
| `corpus/bus-conflict.circ` | Diagnóstico | Drivers discordantes produzem erro somente nos bits em conflito. |

`sap1.circ` contém o computador SAP-1 completo, com sete circuitos e um programa que calcula `2 + 3 + 4 - 1 = 8`. A [referência SAP-1](SAP-1.md) registra a origem, a licença e o teste de execução.

Ao abrir o SAP-1 pela ajuda ou importar seu arquivo, selecione `main` e alterne a entrada `Reset` de `0` para `1` e de volta para `0` em **Sinais** antes de executar. Após 56 passos, o registro de saída deve mostrar `08`. O atalho SAP-1 da biblioteca aplica o pulso ao carregar; o teste de referência usa os passos `startup` do catálogo.

`CircCodec.readProject` importa todos os circuitos de um arquivo. `CircCodec.writeProject` exporta o projeto com seus metadados preservados. Os métodos `read` e `write` aceitam um único documento para compatibilidade com o exemplo AND.

A leitura XML limita o tamanho, o número de elementos e a profundidade. DTD e entidades externas são rejeitados. Uma falha de leitura retorna os diagnósticos em vez de importar parcialmente o projeto.

Para verificar que o catálogo embutido corresponde aos arquivos:

```sh
python3 scripts/generate_examples.py --check
./gradlew :core:jvmTest --tests '*CircuitExamplesTest'
```

`CircuitExamplesTest` está em `commonTest` e também pode ser executado no iOS Simulator pelo target Native. Ele abre todos os exemplos, aplica os estímulos declarados e repete a execução após exportar e reimportar cada projeto. A comparação independente com o desktop é descrita em [docs/corpus.md](../docs/corpus.md).
