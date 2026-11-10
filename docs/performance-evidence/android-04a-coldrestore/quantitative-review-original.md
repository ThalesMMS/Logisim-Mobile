A única abertura capturada falhou na prontidão de 30 s após o retorno de `am start -W`. Há hierarquia fresca ainda em loading aos 33,637 s após o pedido de lançamento. O ensaio contém sondagens e SIGQUIT; não é uma medição de energia nem um orçamento de dispositivo.

Artefatos: `/Users/thales/GitHub/logisim-mobile-worktrees/performance-coldrestore-04a/outputs/performance/coldrestore-04a-20261104T212354Z`. Pin atestado pelo operador: `04a0b34a38689932a3f3aaed7f4ea5cb6b63c8db`. APK antes/depois igual a `e93a285e3294f91f971a0cc9c80b1f2cd51dc29fa0bdf7989a8ca3f2c38bb5c3`. O `receipt.json` passivo termina `captured`; isso comprova coleta, não prontidão.

T=0 é o pedido de lançamento, em 2026-11-04 21:26:38,796639 UTC. Os elapsed de hierarquia são calculados logo após a resposta; o evento JSON pode ser escrito um pouco depois.

| Marco | Tempo desde T=0 | Tempo após retorno de am start |
| --- | ---: | ---: |
| am start retornou | 10,923 s | 0 s |
| Primeira hierarquia fresca, loading | 18,323 s | 7,400 s |
| Pedido SIGQUIT ao PID 9898 | 18,648 s | 7,725 s |
| Segunda hierarquia fresca, loading | 25,728 s | 14,805 s |
| Terceira hierarquia fresca, loading | 33,637 s | 22,715 s |
| Erro final de hierarquia / prontidão | 41,530 s | 30,607 s |

`am-start.txt` declara COLD, TotalTime=10416 ms e WaitTime=10498 ms; o comando levou 10,914 s no relógio do observador. O último dump recebeu o orçamento restante de 7,082 s. Nenhum evento editor-ready foi registrado. As screenshots com rótulos 12s e 30s terminaram em T+13,764 s e T+32,553 s; os rótulos são alvos, não instantes exatos da captura. O log do novo PID confirma SIGQUIT recebido às 21:26:57,687 UTC e traces enviados ao tombstoned às 21:26:59,195 UTC. Essa intervenção impede tratar toda a janela como não perturbada. Não há ANR desde o boot no lastanr.

Amostragem e incerteza:

- 20 sondas: 0000/0001 pertencem ao PID antigo 8880/starttime114759; 0002 não encontrou PID; 0003–0019 são 17 sondas do PID 9898/starttime266496 constante. Não subtraí contadores entre processos.
- Duração por sonda: mínimo 0.569525 s, mediana 3.797367 s, máximo 8.504151 s. Soma 76.728858 s em span 77.692342 s, 98.76% ocupado por comandos de sondagem. O intervalo nominal 1 s não produziu 1 Hz; espaçamento de midpoints foi 0,908–8,046 s, mediana 4,049 s.
- Cada leitura processual/thread/IO pode ocorrer em qualquer ponto do envelope antes/depois da sonda. Os arquivos são lidos sequencialmente, não como um snapshot atômico. Houve enumeração de 2 threads do PID antigo quando status ainda dizia25, e24 threads quando status do PIDnovo dizia25.
- O HZ do alvo não foi registrado. Ticks são o resultado primário. Conversões abaixo são explicitamente condicionais a USER_HZ=100; não são confirmação desse valor.

CPU do PID9898 entre as sondas0003 e0019: utime9→2294 e stime11→794, delta **2285 user +783 system =3068 ticks**. O midpoint de tempo entre envelopes é65,470989 s; o intervalo real entre as leituras fica limitado por62,416883–68,525095 s. A100Hz seriam30,68 CPU-s e46,86% de um core pelo midpoint. O envelope conservador, incluindo±2 ticks por quantização dos dois contadores, é44,74–49,19% de um core. Não é taxa pontual no instante do timeout, não cobre o começo inteiro do lançamento e não é percentual do aparelho inteiro.

| Thread e identidade | Primeira→última observação | Delta user+system, ticks | Estados pontuais |
| --- | --- | ---: | --- |
| Main9898/start266496 | 0003→0019 | 1013+67=1080 | R9/S8 |
| DefaultDispatch10060/start267195 | 0007→0019 | 699+22=721 | R12/S1 |
| RenderThread10005/start267065 | 0006→0019 | 103+551=654 | R9/S5 |
| Jit thread pool9916/start266727 | 0003→0019 | 371+123=494 | R16/S1 |

HeapTaskDaemon teve25 ticks e SignalCatcher17. O outro DefaultDispatch10061 teve9 ticks e futex nas13 observações. Os intervalos das threads diferem, portanto a tabela não deve ser somada como divisão exata da CPU do processo. Exemplos de não atomicidade: primeira leitura Main tem utime10 quando a leitura anterior do processo tem9; no fim, processo(2294,794) difere da soma das threads(2263,800). `wchan` também é lido depois do stat: Main apresenta0×10/futex×6/epoll×1; worker10060 apresenta futex×1/0×12; Render apresenta0×8/epoll×6. R e wchan0 não medem ocupação contínua nem identificam uma função. O nome DefaultDispatch está truncado e não prova parse ou settle.

I/O e memória do novo PID, sondas0003→0019:

- read_bytes +3.784.704 B; write_bytes +90.112 B; cancelled_write_bytes0. rchar+15.761.085 B, wchar+2.466.491 B, syscr+79.451 e syscw+6.116. rchar/wchar não equivalem a acesso físico ao armazenamento.
- Da sonda0010, cujo envelope éT+24,262..32,766 s, até0019: +2231 CPUticks, **read_bytes sem aumento**, write_bytes+4096 B, rchar+75.248 B. Isso não sustenta a hipótese de leitura física contínua como explicação de todo o atraso posterior; cache, computação, JIT, renderização e espera continuam possíveis.
- RSS observado57436→193740 KiB, máximo195568 KiB. São amostras RSS, não PSS, pico garantido ou crescimento de leak comprovado.

Os10 arquivos workspace preexistentes mantêm hashes exatos, incluindo catálogo e as5 revisões dos2 projetos. Há1 novo window-link e `files/profileInstalled` mudou; portanto não diga que todo o diretório files ficou idêntico. Nenhum arquivo de projeto/catalog foi alterado por esta análise. O log foi capturado por UID e contém PIDs históricos; somente9898 sustenta observações desta abertura.

O host declarou load averages181.85/120.85/62.73. A coleta quase contínua de /proc, UiAutomator, screenshots e SIGQUIT é custo observador não isolado, além dessa carga do host. Os dados demonstram CPU avançando em Main, worker, Render e JIT, mas não atribuem a demora a parse/settle, nem provam deadlock, energia, limite de memória ou orçamento físico. Não foram usados ADB, Gradle, novo lançamento ou alterações de artefatos nesta análise.
