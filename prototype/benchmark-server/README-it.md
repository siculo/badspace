# Servizio dei benchmark

Il servizio gira sulla macchina Linux dedicata ai benchmark e si avvia a mano.
Riceve un piano JSON e un branch o un tag di `origin`, aggiorna un clone dedicato,
compila il runner e avvia il piano. La pagina web e l'API mostrano lo storico,
lo stato, i log e i file da scaricare. Piani e risultati non devono passare da Git.

Il modulo è separato da `benchmark`: il jar del servizio resta in esecuzione
mentre il codice da misurare cambia. Il runner JMH e la sua interfaccia a riga
di comando restano quelli esistenti. Sono utilizzabili anche branch e tag che
non contengono il servizio, purché offrano il runner con `run --plan FILE`.

## Installazione sulla macchina Linux

Servono JDK 25, Maven e Git. Il clone deve avere il remote `origin` e credenziali
già disponibili senza interazione, per esempio tramite una chiave SSH.
Usare un clone **dedicato al servizio**: il servizio ne cambia il checkout e lo
lascia sul commit misurato, con HEAD detached. Le modifiche locali, inclusi i file
non ignorati e non tracciati, fanno fallire la richiesta senza essere cancellate.

Eseguire lo script interattivo dal clone, anche da una directory diversa dalla
radice del repository:

```sh
./prototype/benchmark-server/install.sh
```

Lo script chiede la directory di installazione, che deve essere esterna al
repository, e i valori delle proprietà. Invio accetta il valore predefinito.
`repository` viene impostato automaticamente alla radice del clone che contiene
lo script. Il Maven indicato viene usato anche per compilare il servizio.
Lo script copia jar, configurazione e `start.sh` nella directory scelta;
se trova file di installazione già presenti, chiede se aggiornare il servizio.
Prima di confermare occorre arrestare il servizio. L’aggiornamento sostituisce
jar e `start.sh`, conserva configurazione e risultati e chiede soltanto il Maven
da usare per compilare. Rifiutando, lo script termina senza modifiche.
La configurazione è leggibile soltanto dal proprietario.

I percorsi relativi degli eseguibili sono risolti dalla directory corrente;
il percorso relativo di `results` dalla directory di installazione.
I risultati devono essere separati dal clone. `java` vuoto usa il runtime del
servizio. `JAVA_HOME` sceglie il runtime di `start.sh`; per la compilazione Maven
deve usare un JDK adatto al commit scelto.

Entrambi gli script sono eseguibili. Dopo l'installazione avviare il servizio:

```sh
/home/benchmark/benchmark-service/start.sh
```

Non occorre installare un daemon. `Ctrl+C` arresta il servizio e richiede
l'arresto dell'esecuzione attiva. Per aggiornare il servizio, arrestarlo e
rieseguire lo script di installazione indicando la stessa directory; gli aggiornamenti richiesti tramite l'API riguardano
soltanto il codice da misurare.

La directory assume questa forma:

```text
benchmark-service/
  benchmark-server.jar
  start.sh
  server.properties
  results/
    .server.lock
    <run-id>/
      status.json
      plan.json
      run.log
      results/
        <risultato>.json
        <report>.html
```

## Pagina web

Aprire `http://<macchina>:8080/` dalla macchina di sviluppo. La pagina permette
di caricare un piano locale oppure incollarlo, selezionare un branch o un tag,
avviare il lavoro, fermarlo e scaricare i file. Il piano precompilato misura
soltanto la memoria della scansione lineare con pochi dati, per un primo controllo.

La pagina aggiorna ogni due secondi stato, commit, avanzamento e coda del log.
Il log mostra anche la compilazione e l'output di JMH, comprese le sue stime
di avanzamento. Queste ultime si riferiscono alla singola invocazione di JMH,
non all'intero piano; i messaggi `run N of M` indicano il punto del piano.
I report HTML scaricati funzionano senza servizio e senza connessione Internet.

Il servizio è pensato per una rete locale fidata: chi avvia un benchmark fa
compilare ed eseguire il codice di `origin`. La proprietà facoltativa `token`
richiede `Authorization: Bearer <token>` per tutta l'API. La pagina permette
di inserirlo e lo conserva nella sessione della scheda. Il servizio usa HTTP;
il token non cifra il traffico.

## API REST

| Metodo e percorso | Effetto |
| --- | --- |
| `POST /api/runs` | Accetta un piano e una revisione; restituisce `202`, ID e `Location` |
| `GET /api/runs` | Storico, esecuzione attiva, avanzamento e lista dei file |
| `GET /api/runs/<id>` | Stato, richiesta originale, commit risolto, avanzamento e file |
| `GET /api/runs/<id>/log` | Ultimi 64 KiB di log |
| `GET /api/runs/<id>/files/<nome>` | File, usando il nome restituito nella lista `artifacts` |
| `POST /api/runs/<id>/cancel` | Richiede l'arresto; ripetibile anche dopo la fine |

Si invia esattamente uno fra `branch` (ultimo commit remoto al momento del fetch)
e `tag` (commit indicato dal tag remoto). Il commit completo viene salvato nello
stato; i JSON dei benchmark mantengono anche il campo `commit` già prodotto dal
runner. Il piano inline usa il formato esistente, compresi commenti e virgole
finali. Non viene letto dalla directory `plans/` del clone.

Per esempio, creare `request.json` sulla macchina di sviluppo:

```json
{
  "branch": "feature/my-change",
  "plan": {
    "groups": [{
      "profile": "quick",
      "report": "comparison",
      "runs": [{
        "name": "linear",
        "index": "LINEAR_SCAN",
        "include": "Footprint",
        "params": { "size": 1000, "distribution": "UNIFORM" }
      }]
    }]
  }
}
```

```sh
curl -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  --data-binary @request.json http://benchmark-host:8080/api/runs
curl -H "Authorization: Bearer $TOKEN" http://benchmark-host:8080/api/runs/<id>
curl -H "Authorization: Bearer $TOKEN" -X POST http://benchmark-host:8080/api/runs/<id>/cancel
curl -H "Authorization: Bearer $TOKEN" \
  -o result.json http://benchmark-host:8080/api/runs/<id>/files/results/<nome>.json
```

Per inviare un piano esistente con `jq`, senza modificarlo o aggiungerlo a Git:

```sh
jq -n --arg branch feature/my-change --slurpfile plan my-plan.json \
  '{branch:$branch, plan:$plan[0]}' > request.json
```

`jq` richiede JSON standard; per piani con commenti si può usare la pagina web.
Gli eventuali campi `output` devono indicare file relativi sotto `results/`, per
esempio `results/reference-linear-quick.json`. I percorsi rimangono relativi alla
directory della singola esecuzione, così due esecuzioni non si sovrascrivono.
Il runner del commit scelto verifica l'intera semantica del piano prima di
iniziare le misure. Una richiesta non valida dà `400`; l'API limita il corpo a
1 MiB. Una seconda richiesta durante un lavoro attivo dà `409`: non c'è una coda.

## Ciclo di vita

Gli stati normali sono `ACCEPTED`, `FETCHING`, `BUILDING`, `RUNNING` e
`SUCCEEDED`. Il servizio esegue `git fetch` del riferimento esplicito, ne
risolve il commit, fa checkout detached e compila con:

```sh
mvn -B -f prototype/pom.xml -pl benchmark -am clean package -DskipTests
```

La compilazione pulita evita di riutilizzare il jar della revisione precedente.
Un fetch, una compilazione o un benchmark fallito produce `FAILED` e conserva
il log. L'arresto passa per `STOPPING` e poi `CANCELLED`; riguarda anche Maven e
le JVM figlie di JMH. I file già prodotti restano disponibili, ma possono essere
parziali. `SUCCEEDED` viene assegnato solo dopo l'uscita corretta del runner.
Se un processo non termina neppure dopo l'arresto forzato, il servizio segnala
il fallimento e rifiuta altre esecuzioni fino al riavvio.

Lo storico è ricaricato al riavvio. Un'esecuzione il cui stato salvato era ancora
attivo diventa `INTERRUPTED` e non riparte automaticamente. Un arresto forzato
del processo del servizio, per esempio `kill -9`, non permette di eseguire la
pulizia: prima di riavviarlo verificare e terminare eventuali Maven o JVM di
benchmark rimasti attivi. Per un arresto ordinato usare `Ctrl+C` o `SIGTERM`.

I lock impediscono a due istanze di usare la stessa directory dei risultati o
lo stesso clone. Il servizio non rimuove lo storico e non pubblica i risultati
nel repository. A servizio fermo si possono archiviare o cancellare le directory
delle esecuzioni non più necessarie.

## Verifica

```sh
mvn -f prototype/pom.xml -pl benchmark-server -am test
```

I test usano repository Git locali, build Maven, richieste HTTP e processi Java
reali. Il piccolo runner della fixture serve a osservare il commit compilato,
gli artefatti e l'arresto di una JVM figlia senza eseguire misure JMH lunghe.
