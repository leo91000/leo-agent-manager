# Leo Agent Manager

Leo Agent Manager permet de conduire des conversations avec des agents et de retrouver leur travail.

## Language

**Conversation archivée** :
Une conversation conservée hors du stockage principal pour libérer de l’espace, dont une entrée reste visible dans l’application. Elle conserve l’historique, les fichiers et le contexte de travail nécessaires à sa reprise après restauration.

**Inactivité d’une conversation** :
Période écoulée depuis le dernier échange ou travail de l’agent ; une simple consultation ne la réinitialise pas. Une conversation avec un travail en cours, une réponse attendue ou des messages à exécuter, même en pause, n’est pas éligible à l’archivage automatique.

**Conversation à la corbeille** :
Une conversation supprimée par l’utilisateur, récupérable pendant 30 jours avant son effacement définitif et celui de ses données associées. La corbeille se distingue de l’archivage, qui vise la conservation.

**Restauration d’une conversation archivée** :
Remise à disposition, à la demande explicite de l’utilisateur, d’une conversation conservée avec son historique, ses fichiers et son contexte de travail pour permettre sa reprise. Une archive froide peut demander plusieurs heures avant d’être disponible.

**Récupération depuis la corbeille** :
Retour d’une conversation à son état précédent, actif ou archivé, sans relancer l’agent, les envois annulés ni les liens publics révoqués. Récupérer une conversation archivée ne déclenche pas la restauration de son archive.

**Délai de grâce d’une conversation** :
Période durant laquelle une conversation redevenue active après restauration ou récupération est protégée d’un nouvel archivage automatique. Ce délai correspond au délai d’archivage configuré ; une simple consultation ne le prolonge pas.

**Agent de code** :
Le moteur qui exécute le travail d’un agent : Codex (OpenAI) ou Claude Code (Anthropic). Chaque agent en choisit un, et une conversation peut en changer d’un message à l’autre.
_À éviter_ : fournisseur, driver, assistant de code.

**Compte d’agent de code** :
Un abonnement connecté à un agent de code (un compte ChatGPT pour Codex, un compte Claude pour Claude Code). Les exécutions consomment son usage. Un agent de code peut avoir plusieurs comptes ; un compte en pause n’est plus choisi pour les nouvelles exécutions.
_À éviter_ : connexion Claude, compte Codex (sauf pour désigner un compte de cet agent de code).

**Fenêtre d’usage** :
La période sur laquelle l’agent de code plafonne l’usage d’un compte : 5 heures, semaine, ou semaine limitée à un modèle.

**Capacité restante** :
La part d’usage restante la plus basse parmi les fenêtres d’usage qui s’appliquent à un modèle. Une nouvelle exécution prend le compte disponible qui a le plus de capacité restante ; c’est le compte **prochain**.

**Exécutions parallèles** :
Le nombre maximal d’exécutions simultanées sur un compte. Le réduire laisse finir les exécutions en cours.

**Réinitialisation en réserve** :
Une remise à zéro de fenêtre d’usage offerte par Codex, utilisée automatiquement quand la capacité restante d’un compte actif tombe à 2 %. Elle n’existe pas pour Claude Code.
