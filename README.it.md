![BADSPACE](img/BADSPACE.jpeg)

[English](README.md)

BADSPACE è un progetto per un database real-time di entità in uno spazio 2D o 3D, pensato come base per software di simulazione, monitoraggio o gaming. L'obiettivo è tenere traccia della posizione delle entità e rispondere a query di prossimità mentre migliaia di entità si muovono a ogni tick. La scalabilità si basa sulla divisione del mondo in partizioni che si creano e si rimuovono dinamicamente, ognuna con un solo writer.

L'obiettivo è uno strumento flessibile, che i programmatori possono adattare alle proprie esigenze. Il software sceglie come dividere le entità in partizioni, su quali nodi collocarle e quale indice spaziale usare. BADSPACE fornisce le operazioni e le garanzie su cui costruire la logica applicativa.

Il progetto è in fase prototipale. Il [prototipo Java](prototype/) è una libreria embedded con nodi locali e serve a validare le decisioni di progetto. Il [bundle OKF](okf-bundle/index.md) distingue l'[implementazione corrente](okf-bundle/current/index.md) dagli [sviluppi futuri](okf-bundle/future/index.md). Stato attuale:

- **Spazi e partizioni:** spazi 2D e 3D con tick indipendenti; ogni spazio crea i propri nodi locali con una factory, e le partizioni si creano e si rimuovono su questi nodi; indice spaziale, politica di rimozione e finestra degli snapshot scelti per partizione. Gli ID delle entità usano un generatore in stile Snowflake.
- **Scritture e letture:** scritture batch delle entità, commit delle partizioni e tick logici. Un writer possiede la partizione tramite fencing token; i reader acquisiscono snapshot stabili dei tick completati. Gli snapshot pubblicati restano disponibili nella finestra configurata e quelli acquisiti possono sopravvivere alla rimozione della partizione.
- **Metadati:** metadati applicativi delle entità, con scritture condizionate che possono modificare insieme posizioni e metadati.
- **Persistenza:** salvataggio sincrono dello snapshot pubblicato di una partizione nella directory del nodo, elenco ed eliminazione dei salvataggi, e caricamento di entità e metadati in una nuova partizione. Il software decide quando salvare e quali salvataggi conservare.
- **Query:** letture per ID, query range e k-nearest su una partizione; aggregazione di range e k-nearest sugli snapshot dello stesso tick di partizioni scelte dal software. Il chiamante sceglie il tick e gestisce gli snapshot non disponibili.

Il [TODO del prototipo](TODO.md) segue i prossimi blocchi, a partire dal nodo remoto gRPC. Raycasting, migrazione tra partizioni e recupero coordinato sono sviluppi futuri. Il salvataggio e caricamento attuale riguarda una partizione alla volta e non offre il recupero dell'intero spazio dopo un crash.

Gli [esempi](prototype/samples/README-it.md) mostrano come usare l'API, nell'ordine necessario per capirla.

## Configurazione per gli agenti

Le skill per gli agenti di programmazione sono in `.agents/skills`. Claude Code le legge da `.claude/skills`, che non è nel repository. Dopo il clone, eseguire lo script di configurazione da qualunque directory:

- Linux e macOS: `./agents-setup.sh`, che crea un link simbolico.
- Windows: `agents-setup.cmd`, che crea una junction di directory e non richiede permessi particolari.

Lo script si può eseguire di nuovo in qualunque momento, per esempio dopo aver spostato il repository: corregge solo quello che manca o è sbagliato.

Licenza: [Apache 2.0](LICENSE).
