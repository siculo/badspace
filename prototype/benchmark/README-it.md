# Benchmark dei nodi delle partizioni

*[English](README.md)*

Questo modulo misura la velocità e la memoria degli indici spaziali di una
partizione. Usa [JMH](https://github.com/openjdk/jmh) e lavora sul contratto
pubblico del nodo (`PartitionNode2`), quindi misura anche il costo dello
storage, non solo quello dell'indice. Per ora solo 2D.

La scansione lineare è il riferimento: ogni nuovo indice si confronta con lei.

## Avvio rapido

Da `prototype/benchmark`:

```
mvn -f .. install -DskipTests
java --sun-misc-unsafe-memory-access=allow -jar target/benchmarks.jar run quick
```

Poi si apre in un browser il report scritto accanto ai risultati, per
esempio `results/2026-10-01-1430-quick.html`.

Il primo comando crea `target/benchmarks.jar`. Va rilanciato dopo ogni
modifica al codice, altrimenti i benchmark girano sul codice vecchio.
L'opzione della JVM `--sun-misc-unsafe-memory-access=allow` serve solo a
nascondere alcuni avvisi di JMH con i JDK recenti.

## Eseguire i benchmark

```
java -jar target/benchmarks.jar run [quick|full] [--include REGEX] [--param NOME=V1,V2] [--percentiles] [--output FILE]
```

| Opzione | Significato |
|---|---|
| `quick` | Profilo rapido (default): circa 10 minuti, per un controllo durante lo sviluppo. |
| `full` | Profilo completo: circa 2-3 ore, per le misure da conservare. |
| `--include REGEX` | Esegue solo i benchmark il cui nome completo contiene una corrispondenza, per esempio `findNearest`, `insert\|remove` o `Footprint`. |
| `--param NOME=V1,V2` | Cambia i valori di un parametro, per esempio `--param size=1000,1000000`. Si può ripetere. |
| `--percentiles` | Misura anche i percentili di latenza (p50, p90, p99). Raddoppia la durata dell'esecuzione. |
| `--output FILE` | Dove scrivere i risultati. Default: `results/<data>-<profilo>.json`. |

Alla fine dell'esecuzione viene scritto anche il report dei risultati, nella
stessa directory e con lo stesso nome del file JSON, ma con estensione
`.html`.

Il percorso di output è relativo alla directory di lavoro, quindi i comandi
vanno lanciati da `prototype/benchmark`.

### Esempi

```
# Solo le query k-nearest, su partizioni piccole e grandi
java -jar target/benchmarks.jar run quick --include findNearest --param size=1000,1000000

# Solo le scritture, con una sola distribuzione
java -jar target/benchmarks.jar run quick --include "insert|remove|update" --param distribution=UNIFORM

# Solo la memoria per entità
java -jar target/benchmarks.jar run quick --include Footprint

# Misure da conservare, con i percentili
java -jar target/benchmarks.jar run full --percentiles --output results/reference-linear-full.json
```

## Cosa si misura

| Benchmark | Cosa misura | Unità |
|---|---|---|
| `BuildBenchmark.build` | Riempire una partizione vuota con tutte le entità in una sola chiamata. | ms |
| `PartitionBenchmark.insert` | Inserire un batch di entità nuove. | µs per chiamata |
| `PartitionBenchmark.remove` | Rimuovere un batch di entità. | µs per chiamata |
| `PartitionBenchmark.update` | Spostare un batch di entità. | µs per chiamata |
| `PartitionBenchmark.get` | Leggere un batch di entità per ID. | µs per chiamata |
| `PartitionBenchmark.findInRegion` | Query di range. | µs per chiamata |
| `PartitionBenchmark.findNearest` | Query k-nearest. | µs per chiamata |
| `Footprint.bytesPerEntity` | Memoria usata dalla partizione, storage e indice insieme. | byte per entità |

Più basso è sempre meglio.

## Parametri

| Parametro | Usato da | Profilo rapido | Profilo completo |
|---|---|---|---|
| `index` | tutti | tutti i valori di `IndexType` | gli stessi |
| `distribution` | tutti | `UNIFORM`, `CLUSTERS`, `HOTSPOT`, `CORRIDORS`, `COINCIDENT` | gli stessi |
| `size` | tutti | 1000, 100000 | 1000, 10000, 100000, 1000000 |
| `batchSize` | insert, remove, update, get | 1, 100 | 1, 10, 100, 1000 |
| `movement` | update | `LOCAL`, `TELEPORT` | gli stessi |
| `selectivity` | findInRegion | 0.001, 0.01 | 0.0001, 0.001, 0.01, 0.1 |
| `shape` | findInRegion | `BOX` | `BOX`, `CIRCLE` |
| `queryCenter` | findInRegion, findNearest | `UNIFORM`, `DATA` | gli stessi |
| `k` | findNearest | 1, 10 | 1, 10, 100 |

Il mondo è un quadrato da 0 a 10000 su ogni asse. Le distribuzioni sono:

- `UNIFORM`: ogni punto ha la stessa probabilità.
- `CLUSTERS`: 16 gruppi densi con distribuzione normale.
- `HOTSPOT`: il 90% delle entità in un quadrato che copre il 5% del mondo.
- `CORRIDORS`: 8 strisce sottili orizzontali e verticali.
- `COINCIDENT`: 1000 posizioni, ognuna condivisa da molte entità.

Gli altri parametri funzionano così:

- `movement`: `LOCAL` sposta un'entità di un passo breve, come in un tick di
  movimento. `TELEPORT` la sposta in una posizione nuova presa dalla stessa
  distribuzione.
- `queryCenter`: `UNIFORM` mette le query in un punto qualsiasi del mondo,
  anche dove non ci sono entità. `DATA` le mette sulla posizione di
  un'entità, quindi le query vanno dove sono le entità.
- `selectivity`: la quota delle entità che una query di range trova. Ogni
  region è dimensionata sui dati per contenere quel numero di entità, così i
  risultati confrontano il costo della ricerca e non la dimensione del
  risultato.

Tutti i dati vengono da un seed fisso: due esecuzioni con gli stessi
parametri usano le stesse entità e le stesse query.

## Il report

```
java -jar target/benchmarks.jar report [--output FILE] RISULTATI.json...
```

Il report è un unico file HTML e non richiede una connessione a internet.
L'output di default è accanto all'ultimo file dei risultati, con lo stesso
nome ed estensione `.html`. Mostra una sezione per ogni
operazione. Ogni sezione ha:

- un grafico log-log per ogni distribuzione, con il numero di entità
  sull'asse x, il tempo sull'asse y e una linea per ogni serie;
- una tabella con i valori e, per ogni serie, il rapporto rispetto al
  riferimento: verde quando è migliore di almeno il 10%, rosso quando è
  peggiore di almeno il 10%.

Una serie è una coppia di file di risultati e indice, per esempio
`reference-linear-quick · LINEAR_SCAN`.

I controlli in alto scelgono:

- il riferimento (baseline);
- il valore di ogni parametro che non è sugli assi;
- la metrica (media o percentili, se l'esecuzione ha usato `--percentiles`);
- il tempo per entità invece del tempo per chiamata, per i benchmark a batch.

Per confrontare due esecuzioni, per esempio prima e dopo una modifica, si
passano entrambi i file:

```
java -jar target/benchmarks.jar report results/prima.json results/dopo.json
```

I file dei risultati si possono aprire anche con il
[JMH Visualizer](https://jmh.morethan.io/) online. La memoria per entità vi
compare come un benchmark in più, con modalità `footprint`.

## Aggiungere un nuovo indice

1. Aggiungere il valore a `IndexType` e creare l'indice in
   `LocalPartitionNode2.createPartition`.
2. Ricompilare il jar. I benchmark usano tutti i valori di `IndexType`, quindi
   il nuovo indice viene misurato senza altre modifiche.
3. Eseguire i benchmark e aprire il report con la scansione lineare come
   riferimento. Per misurare solo il nuovo indice si usa
   `--param index=NUOVO_INDICE` e si passano al report i risultati di
   riferimento insieme a quelli nuovi:

   ```
   java -jar target/benchmarks.jar report --output results/confronto.html results/reference-linear-quick.json results/<file>.json
   ```

## Misure affidabili

- I risultati sono confrontabili solo se vengono dalla stessa macchina. Vanno
  usati i rapporti, non i valori assoluti: i tempi in Java non sono quelli
  dell'implementazione finale.
- Per il profilo completo conviene chiudere gli altri programmi, collegare
  l'alimentazione e usare la modalità di risparmio energia "prestazioni
  migliori" di Windows.
- Il profilo rapido ha poche iterazioni, quindi il suo errore è ampio: mostra
  le tendenze grandi, non le differenze piccole. L'errore di ogni valore è nel
  JSON (`scoreError`) e nell'output di JMH.
- Inserimento e rimozione con batch da 1 includono anche un piccolo costo
  fisso di JMH, uguale per tutti gli indici, quindi i loro rapporti sembrano
  più vicini a 1.
- La memoria per entità viene dalla dimensione dell'heap dopo una garbage
  collection. È approssimata, ma basta per confrontare gli indici.

## Come funzionano i benchmark

- **La partizione non cambia durante una misura.** Inserimenti e rimozioni
  vengono annullati dopo ogni chiamata, fuori dal tempo misurato. Gli
  aggiornamenti spostano un batch e poi lo riportano indietro.
- **Gli input sono pronti prima della misura.** Batch, region e punti delle
  query sono preparati all'inizio, in un anello che le chiamate percorrono.
- **Ogni configurazione gira in una JVM nuova** (un fork di JMH), così le
  esecuzioni non si influenzano a vicenda.

## Risultati nel repository

La directory `results/` è esclusa da git, perché le misure dipendono dalla
macchina. Per conservare una misura si aggiungono il file JSON e il suo
report HTML con `git add -f`.

Le misure di riferimento di un indice si chiamano
`reference-<indice>-<profilo>.json`. Nel repository c'è
`reference-linear-quick.json`, la scansione lineare con il profilo rapido.
Come tutte le misure, vale solo per la macchina dove è stata presa: su
un'altra macchina va rifatta prima di confrontarla con un nuovo indice.

Le altre misure da conservare si chiamano
`<data>-<indice o modifica>-<profilo>.json`.

## Test unitari

I test unitari di questo modulo controllano i generatori dei dati, il
dimensionamento delle region e che i benchmark lascino la partizione come
l'hanno trovata. Girano con gli altri test del progetto:

```
mvn -f .. test
```
