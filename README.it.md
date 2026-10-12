![BADSPACE](img/BADSPACE.jpeg)

[English](README.md)

BADSPACE è un progetto per un database real-time di entità in uno spazio 2D o 3D, pensato come base per software di simulazione, monitoraggio o gaming. L'obiettivo è tenere traccia della posizione delle entità e rispondere a query di prossimità (range, k-nearest, raycasting) mentre migliaia di entità si muovono a ogni tick. La scalabilità si basa sulla divisione del mondo in partizioni che si creano e si rimuovono dinamicamente, ognuna con un solo writer.

L'obiettivo è uno strumento flessibile, che i programmatori possono adattare alle proprie esigenze. Il progetto prevede l'uso come libreria o come servizio, la scelta delle strategie di partizionamento e di clustering, e API su cui costruire la propria logica applicativa. BADSPACE si rivolge a chi cerca gli strumenti per costruire la propria soluzione.

Il progetto è in fase prototipale: il prototipo in Java (`prototype/`) serve a validare le decisioni di progetto, documentate nel [bundle OKF](okf-bundle/index.md). Stato attuale:

- **Fatto:** libreria embedded con spazi 2D e 3D, partizioni su nodi locali, indici spaziali scelti alla creazione della partizione, inserimento, lettura, aggiornamento e rimozione delle entità.
- **In corso:** commit della partizione, con un writer e più reader concorrenti sulla stessa partizione.

## Configurazione per gli agenti

Le skill per gli agenti di programmazione sono in `.agents/skills`. Claude Code le legge da `.claude/skills`, che non è nel repository. Dopo il clone, eseguire lo script di configurazione da qualunque directory:

- Linux e macOS: `./agents-setup.sh`, che crea un link simbolico.
- Windows: `agents-setup.cmd`, che crea una junction di directory e non richiede permessi particolari.

Lo script si può eseguire di nuovo in qualunque momento, per esempio dopo aver spostato il repository: corregge solo quello che manca o è sbagliato.

Licenza: [Apache 2.0](LICENSE).
