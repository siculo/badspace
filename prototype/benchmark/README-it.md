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
./bench.sh run quick
```

Poi si apre in un browser il report scritto accanto ai risultati, per
esempio `results/2026-10-01-1430-quick.html`.

Su Windows si usa `.\bench.ps1` invece di `./bench.sh`.

Il primo comando crea `target/benchmarks.jar`. Va rilanciato dopo ogni
modifica al codice, altrimenti i benchmark girano sul codice vecchio.

Gli script `bench.sh` (Linux e macOS) e `bench.ps1` (Windows) eseguono
`java -jar target/benchmarks.jar` con gli argomenti dati, quindi tutti i
comandi di questo file funzionano con entrambi. Vanno lanciati da
`prototype/benchmark`. Usano il `java` di `JAVA_HOME` se è impostato,
altrimenti quello nel `PATH`. Aggiungono anche l'opzione della JVM
`--sun-misc-unsafe-memory-access=allow`, che serve solo a nascondere alcuni
avvisi di JMH con i JDK recenti.

## Eseguire i benchmark

```
./bench.sh run [quick|full] [--index NOME1,NOME2] [--include REGEX] [--param NOME=V1,V2] [--percentiles] [--output FILE]
./bench.sh run --plan FILE
```

| Opzione | Significato |
|---|---|
| `quick` | Profilo rapido (default): circa 15 minuti, per un controllo durante lo sviluppo. |
| `full` | Profilo completo: circa 3-4 ore, per le misure da conservare. |
| `--index NOME1,NOME2` | Gli indici da misurare, per nome (vedi [Parametri](#parametri)), per esempio `--index LINEAR_SCAN` o `--index UNIFORM_GRID_50,UNIFORM_GRID_200`. Default: `LINEAR_SCAN,UNIFORM_GRID_100`. È lo stesso di `--param index=...`. |
| `--include REGEX` | Esegue solo i benchmark il cui nome completo contiene una corrispondenza, per esempio `findNearest`, `insert\|remove` o `Footprint`. |
| `--param NOME=V1,V2` | Cambia i valori di un parametro, per esempio `--param size=1000,1000000`. Si può ripetere. |
| `--percentiles` | Misura anche i percentili di latenza (p50, p90, p99). Raddoppia la durata dell'esecuzione. |
| `--output FILE` | Dove scrivere i risultati. Default: `results/<data>-<profilo>.json`. |
| `--plan FILE` | Esegue le run di un file di piano, senza altre opzioni (vedi [Piani](#piani)). |

Alla fine dell'esecuzione viene scritto anche il report dei risultati, nella
stessa directory e con lo stesso nome del file JSON, ma con estensione
`.html`.

Il percorso di output è relativo alla directory di lavoro, quindi i comandi
vanno lanciati da `prototype/benchmark`.

### Esempi

```
# Solo la grid uniforme, con due dimensioni di cella
./bench.sh run quick --index UNIFORM_GRID_50,UNIFORM_GRID_200

# La griglia di quadtree contro la grid uniforme
./bench.sh run quick --index UNIFORM_GRID_100,GRID_QUADTREE_128

# Solo le query k-nearest, su partizioni piccole e grandi
./bench.sh run quick --include findNearest --param size=1000,1000000

# Solo le scritture, con una sola distribuzione
./bench.sh run quick --include "insert|remove|update" --param distribution=UNIFORM

# Solo la memoria per entità
./bench.sh run quick --include Footprint

# Misure da conservare, con i percentili
./bench.sh run full --percentiles --output results/reference-linear-full.json
```

### Piani

Per rifare le stesse misure senza riscrivere tutte le opzioni, si mettono le
run in un file di piano e lo si esegue:

```
./bench.sh run --plan plans/index-comparison-quick.json
```

`--plan` non accetta altre opzioni. Le run di un piano sono in gruppi: ogni
gruppo ha un profilo, le sue run e, se ha un nome in `report`, un report con
i risultati di tutte le sue run. Per esempio:

```json
{
  "groups": [
    {
      "profile": "quick",
      "report": "index-comparison",
      "runs": [
        { "name": "uniform-grid", "index": ["UNIFORM_GRID_50", "UNIFORM_GRID_100"] },
        // Sono ammessi i commenti come in Java.
        { "name": "coincident", "index": "GRID_QUADTREE_256",
          "params": { "distribution": "COINCIDENT", "size": [1000, 100000] },
          "include": "update|insert", "percentiles": true },
        { "name": "linear", "index": "LINEAR_SCAN", "output": "results/reference-linear-quick.json" }
      ]
    },
    { "profile": "full", "runs": [ { "name": "uniform-grid", "index": "UNIFORM_GRID_100" } ] }
  ]
}
```

| Campo | Significato |
|---|---|
| `profile` | `quick` (default) o `full`, per tutte le run del gruppo. |
| `report` | Nome facoltativo del report del gruppo: `results/<data>-<report>-<profilo>.html`. |
| `name` | Nome della run, obbligatorio: lettere, cifre e `. _ + -`. |
| `index`, `include`, `params`, `percentiles`, `output` | Come le opzioni di `run`. Un valore singolo si può scrivere senza array, e i numeri con o senza virgolette. Output di default: `results/<data>-<nome>-<profilo>.json`. |

La data è l'inizio del piano, la stessa per tutti i suoi file. Ogni run
scrive anche il suo report, come con le opzioni.

Prima della prima run il tool controlla tutto il piano: i campi, i nomi e i
valori dei parametri, i pattern, e che due file non abbiano lo stesso
percorso. Così un errore ferma il piano subito, non dopo ore.

I piani da conservare vanno in `plans/`. `index-comparison-quick.json` e
`index-comparison-full.json` misurano la grid uniforme (celle da 25 a 400),
la griglia di quadtree (celle da 64 a 256) e la scansione lineare, che è
l'ultima run, in un unico report. Quello rapido dura circa 70 minuti. Per
lasciare un piano in esecuzione dopo il logout:

```
nohup ./bench.sh run --plan plans/index-comparison-full.json > plan.log 2>&1 &
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
| `index` | tutti | `LINEAR_SCAN`, `UNIFORM_GRID_100` | gli stessi |
| `distribution` | tutti | `UNIFORM`, `CLUSTERS`, `HOTSPOT`, `CORRIDORS`, `COINCIDENT`, `FAR_CLUSTER`, `ORIGIN_CLUSTER` | gli stessi |
| `size` | tutti | 1000, 100000 | 1000, 10000, 100000, 1000000 |
| `batchSize` | insert, remove, update, get | 1, 100 | 1, 10, 100, 1000 |
| `movement` | update | `LOCAL`, `TELEPORT` | gli stessi |
| `selectivity` | findInRegion | 0.001, 0.01 | 0.0001, 0.001, 0.01, 0.1 |
| `shape` | findInRegion | `BOX` | `BOX`, `CIRCLE` |
| `queryCenter` | findInRegion, findNearest | `UNIFORM`, `DATA` | gli stessi |
| `k` | findNearest | 1, 10 | 1, 10, 100 |

Il nome di un indice dà l'indice e i suoi parametri:

- `LINEAR_SCAN` per la scansione lineare;
- `UNIFORM_GRID_<lato della cella>` per una grid uniforme, per esempio
  `--index UNIFORM_GRID_50,UNIFORM_GRID_200` per confrontare due
  dimensioni di cella;
- `GRID_QUADTREE_<lato della cella>` o
  `GRID_QUADTREE_<lato della cella>_<capacità della foglia>` per una
  griglia di quadtree, per esempio `GRID_QUADTREE_128` (capacità della
  foglia 16, il default) o `GRID_QUADTREE_128_32`. Il lato della cella
  deve essere una potenza di 2.

Il mondo è un quadrato di lato 10000, da 0 a 10000 su ogni asse; le
distribuzioni con un solo cluster lo spostano. Le distribuzioni sono:

- `UNIFORM`: ogni punto ha la stessa probabilità.
- `CLUSTERS`: 16 gruppi densi con distribuzione normale.
- `HOTSPOT`: il 90% delle entità in un quadrato che copre il 5% del mondo.
- `CORRIDORS`: 8 strisce sottili orizzontali e verticali.
- `COINCIDENT`: 1000 posizioni, ognuna condivisa da molte entità.
- `FAR_CLUSTER`: un solo gruppo denso con distribuzione normale, al centro
  di un mondo lontano dall'origine (da 1e8 - 5000 a 1e8 + 5000).
- `ORIGIN_CLUSTER`: un solo gruppo denso con distribuzione normale
  sull'origine, al centro di un mondo da -5000 a 5000. L'origine è un
  bordo delle celle per ogni dimensione di cella, quindi il gruppo si
  divide tra le celle intorno.

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
./bench.sh report [--output FILE] RISULTATI.json...
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
./bench.sh report results/prima.json results/dopo.json
```

I file dei risultati si possono aprire anche con il
[JMH Visualizer](https://jmh.morethan.io/) online. La memoria per entità vi
compare come un benchmark in più, con modalità `footprint`.

## Aggiungere un nuovo indice

1. Aggiungere un record a `IndexConfig` e creare l'indice in
   `LocalPartitionNode2.createPartition`.
2. Dare un nome all'indice in `IndexNames.parse`, e aggiungere il nome a
   `IndexNames.DEFAULT` e al `@Param` di `WorkloadState.index` se le
   esecuzioni devono misurarlo di default.
3. Ricompilare il jar, eseguire i benchmark e aprire il report con la
   scansione lineare come riferimento. Per misurare solo il nuovo indice si usa
   `--index NUOVO_INDICE` e si passano al report i risultati di
   riferimento insieme a quelli nuovi:

   ```
   ./bench.sh report --output results/confronto.html results/reference-linear-quick.json results/<file>.json
   ```

## Misure affidabili

- Il jar va ricostruito dopo ogni modifica e dopo ogni `git pull`. Se un
  file sorgente è più nuovo del jar, il tool stampa un avviso all'inizio,
  perché l'esecuzione misurerebbe il codice vecchio. Ogni risultato ha il
  commit del codice (`"commit"`), con `-dirty` alla fine se c'erano
  modifiche non committate.
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
