# Linee guida per il codice Java del prototipo

Bozza. Valgono per il prototipo Java (Java 25, JUnit 5, JMH). Il codice è da buttare: serve a
validare le decisioni del bundle e a essere tradotto in C, C++ o Rust. Le regole su lingua e
sviluppo incrementale sono in `CLAUDE.md` e non si ripetono qui.

## 1. Principi

1. **Equilibrio tra leggibilità e prestazioni.** Java non ha alias di tipo né struct (le value
   class non ci sono ancora), quindi un tipo dedicato costa un oggetto. Si accetta un
   primitivo al posto di un tipo apposito quando il nome dice con precisione cosa contiene
   (`long entityId`). Le ottimizzazioni più invasive si fanno solo con una misura JMH, relativa
   alla scansione lineare, e un commento ne dice il motivo.
2. **Oggetti ai bordi, primitivi dentro.** Record e interfacce nell'API e nel contratto del
   nodo; array di primitivi e indici in storage e indici spaziali. Un array di oggetti in Java
   è un array di riferimenti, e ogni accesso passa da un oggetto sparso nell'heap: si evita
   dove l'effetto sulle prestazioni è evidente (strutture con molti elementi, cicli delle
   query). Al suo posto si usano array paralleli di primitivi, uno per campo, raggruppati da
   un commento che nomina la struct che rappresentano, i campi e l'invariante comune
   (stessa lunghezza, stesso indice per lo stesso elemento):

   ```java
   // Entry: id and slot of an entity. The two arrays have the same length
   // and the same index for the same entry.
   private long[] ids;
   private int[] slots;
   ```

   Nella conversione il gruppo diventa un array di struct; se tenerlo come array separati
   (per esempio per scandire un solo campo) si decide allora.
3. **Traducibile in C e in Rust**, senza preferenze tra i due. Quindi:
   - niente costrutti senza un equivalente semplice in entrambi: riflessione, annotation lette
     a runtime, framework, eccezioni usate come flusso normale;
   - niente gerarchie di ereditarietà: interfacce e composizione (trait in Rust, funzioni
     parallele o puntatori a funzione in C). Vale per il codice da tradurre (`common`,
     `service`, `api`); benchmark e test possono estendere classi (stati di JMH, test di
     contratto);
   - proprietà dei dati chiara: ogni struttura ha un solo proprietario, niente riferimenti
     ciclici (per esempio puntatori al padre negli alberi: meglio indici in un array).
4. **Niente infrastruttura non richiesta**: DI, logging, livelli o astrazioni arrivano solo
   quando un concetto del bundle li richiede.

## 2. Naming

1. **I nomi vengono dal glossario del bundle.** Un concetto ha un solo nome, uguale in codice e
   documentazione (partition, commit, version, slot, entity). Un concetto nuovo entra prima nel
   glossario, poi nel codice.
2. **Dimensione nel suffisso**: `Point2`, `PartitionNode3`. Senza suffisso solo ciò che non
   dipende dalla dimensione (`PartitionId`, `SlotTable`).
3. **Tipi**: sostantivi del dominio. Niente `Manager`, `Helper`, `Util`, `Impl`, `Data`.
   L'implementazione si distingue con un prefisso che dice come lavora: `LocalPartitionNode2`,
   `LinearScanIndex2`.
4. **Metodi**: verbo per le azioni (`insertAll`, `commit`); sostantivo senza `get` per le
   proprietà (`size()`, `limits()`, `lastVersion()`); `find…` per le query spaziali; `…All`
   per le operazioni su più entità. Verbi diversi solo per semantiche diverse, documentate
   (`removePartition` contro `dropPartition`).
5. **Il nome dice con precisione cosa contiene.** `partition` è un riferimento all'oggetto
   partizione, `partitionId` il suo identificatore. Vale soprattutto per i primitivi
   (`entityIds`, `commit`, `count`).
6. **Nomi brevi solo in contesti ridotti e non esposti**: variabili locali di metodi brevi e
   indici dei cicli (`id`, `i`). Parametri, campi e tutto ciò che è visibile fuori dal metodo
   hanno il nome completo (`entityId`, non `id`). Eccezione: il campo di un record che porta
   il nome del tipo resta breve (`Entity2.id()`, non `Entity2.entityId()`).
7. **Nessun numero magico**: costanti con nome che dice il significato (`NONE`,
   `INITIAL_CAPACITY`).
8. **Test**: il nome è una frase sul comportamento, in camelCase
   (`versionOfACommitDoesNotSeeLaterWrites`).

## 3. Tipi e struttura

1. **Record** per i valori immutabili (`Point2`, `PartitionId`); validazione nel compact
   constructor.
2. **Sealed interface + `switch` esaustivo** per le varianti chiuse (`Region2`), senza
   `default`.
3. **`interface`** solo per i contratti del bundle o con più implementazioni.
4. **Visibilità minima**: classi `final`, package-private salvo necessità.
5. **2D e 3D duplicati**, non generici: niente boxing né astrazioni che non si traducono.

## 4. Errori

1. `IllegalArgumentException` per input non validi, `IllegalStateException` per operazioni
   non permesse nello stato attuale. Il messaggio riporta il valore
   (`"Unknown partition: " + partitionId`).
2. Validare all'ingresso del contratto, non nei cicli interni.

## 5. Commenti

1. **Javadoc** su ogni tipo e metodo del contratto: cosa fa, casi limite (vuoto, ordine,
   valori speciali), errori, thread-safety.
2. **Commenti nel codice**: il perché, non il cosa.
3. **Concorrenza**: per ogni stato condiviso, un commento dice chi scrive, chi legge e come
   avviene la pubblicazione.

## 6. Test

1. Un comportamento per test.
2. Il contratto comune a più implementazioni si verifica con una classe di test condivisa
   (`SpatialIndex2Contract`).
