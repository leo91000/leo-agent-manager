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
