# Leo Agent Manager

Leo Agent Manager permet de conduire des conversations avec des agents et de retrouver leur travail.

## Language

**Node d’exécution** :
Machine enregistrée dans Leo pour y effectuer le travail d’un agent, selon ses capacités et les autorisations accordées. Une node peut être disponible de manière intermittente.
_Avoid_ : agent (qui désigne l’agent chargé du travail, et non la machine)

**Capacité d’une node** :
Possibilité de travail vérifiée sur une node, distincte de la seule présence du matériel et de sa disponibilité au moment de la demande. Une capacité ne donne aucun droit supplémentaire à l’agent.

**Tag de node** :
Étiquette servant à décrire ou sélectionner une node parmi celles autorisées pour l’agent. Un tag ne constitue pas une autorisation d’accès aux projets ou aux comptes.

**Réservation de ressources** :
Part des ressources d’une node attribuée à une exécution dans les plafonds configurés. Elle est prise en compte avant d’accepter d’autres travaux sur cette node.

**Déplacement d’une conversation** :
Changement de node d’exécution d’une même conversation, avec une pause puis une reprise de sa session et de son environnement de travail. Il ne conserve pas les processus en cours ni leur mémoire.

**Environnement de travail d’une conversation** :
Ensemble des fichiers, outils installés et données locales conservés pour le travail d’une conversation. Il comprend les modifications non publiées des projets et les données nécessaires à leur reprise, au-delà du seul historique des messages.

**Sauvegarde de reprise** :
Copie datée et cohérente de la session et de l’environnement de travail d’une conversation, permettant sa reprise sur une autre node si la node d’origine est indisponible. Elle ne contient pas nécessairement le travail effectué après sa création et se distingue de l’archivage d’une conversation.

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
