# Validation de l’archivage et de la corbeille

Implémentation réalisée à partir de la conception validée le 25 septembre 2026. L’archivage est désactivé par défaut et nécessite la configuration S3 côté serveur. Aucun déploiement en production ni changement d’infrastructure AWS n’a été effectué.

## Contrôles effectués

| Contrôle | Résultat |
| --- | --- |
| Suite backend Rust complète, exécution séquentielle | 154 tests réussis |
| Cycle de vie HTTP, relancé après la correction de récupération de corbeille | 10 tests réussis |
| Suite JavaScript/TypeScript | 244 tests réussis, 34 fichiers |
| Typage Vue et compilation web | Réussis |
| Clippy, tous les targets, avertissements interdits | Réussi |
| Navigation web responsive et conservation des brouillons | Réussie |
| Suppression web, corbeille et restauration | Réussies |
| Archive froide : ouverture sans restauration implicite et demande explicite | Réussies |
| Suppression depuis un autre client, disparition du transcript et rechargement | Réussie |
| Android : compilation des APK et parcours JVM complet | Réussis (1 test, aucune erreur) |
| Android : geste et restauration sur émulateur | Bloqués par l’échec du démarrage Android en émulation logicielle |
| Firecracker réel | Non exécuté : KVM absent de cet environnement |

Un test a reproduit puis validé la correction du cas suivant : restaurer une archive, la supprimer immédiatement, puis la récupérer avant le nettoyage distant conserve bien son état actif.

Les tests HTTP vérifient notamment la conservation des fichiers non publiés et de la session, les transferts corrompus, la reprise après redémarrage, la priorité de la suppression, la protection de la file, la révocation des liens publics et la purge. Le test HTTP du runner utilise un disque sparse synthétique et vérifie le verrouillage ainsi que l’effacement des journaux ; il ne démarre pas une VM.

La commande globale `pnpm check` a rencontré des délais d’attente trop courts dans des tests existants de redémarrage et de disponibilité de compte. La suite complète a ensuite réussi avec `pnpm exec vitest run --no-file-parallelism --expect.poll.timeout=10000`. Les assertions et les fichiers de ces tests sont restés inchangés. Le typage et le build ont été exécutés séparément avec `pnpm build`. La suite Rust a réussi avec `node scripts/test-backend.mjs -- --test-threads=1` après un échec intermittent du test existant de sortie volumineuse.

## Limites de la vérification

Le manque de KVM concerne la VM de travail de l’agent. Les workflows CI existants prévoient déjà KVM : le workflow Android active l’accès à l’accélération et celui de l’image exécute le smoke test Firecracker. `ConversationLifecycleDeviceTest` a été ajouté à la liste explicite des tests instrumentés Android de la CI. Ces modifications locales n’ont pas encore été exécutées en CI ; aucun résultat CI n’est revendiqué ici.

Les APK Android et le test JVM du parcours complet sont compilés et validés. Les tentatives sur émulateurs API 36 puis API 29 en mode logiciel n’ont pas permis d’exécuter le test instrumenté. Sur API 29, le service système de permissions a échoué avec `Error granting/upgrading runtime permissions`, puis le démarrage a dépassé son délai de dix minutes. L’émulateur a été arrêté. Le test `ConversationLifecycleDeviceTest` reste à exécuter sur un appareil ou un émulateur opérationnel ; le test JVM ne le remplace pas.

S3 et Glacier sont exercés avec un exécutable AWS de test utilisant le système de fichiers. Aucun transfert vers un compte AWS réel n’a été effectué. Les données locales ne sont libérées qu’après téléchargement et vérification SHA-256 de l’objet chiffré.

Le scénario `node tests/runner-smoke.mjs IMAGE` a été étendu pour exporter, supprimer et réimporter le disque entre deux vraies exécutions de VM. La seconde doit retrouver les fichiers non publiés, un fichier d’état de session et les images Docker en cache. Ce scénario reste à exécuter sur un hôte Docker avec KVM ; il ne garantit pas la compatibilité des versions futures des moteurs agents.

## Aperçus

- [Actives au premier plan, accès secondaire aux archives](/api/runs/add72dca-8dbf-4df3-b8e3-17edc0295507/artifacts/9d08ec05-a3d1-4c51-8314-ce44bdeb0191)
- [Corbeille, date d’effacement et restauration](/api/runs/add72dca-8dbf-4df3-b8e3-17edc0295507/artifacts/b57c40af-072a-4964-b813-fe3a4ad17359)
- [Archive froide et restauration explicite](/api/runs/add72dca-8dbf-4df3-b8e3-17edc0295507/artifacts/7c1412fc-1521-46b3-82cb-0f5bba27fd6e)
- [Guide de configuration et d’exploitation](/api/runs/add72dca-8dbf-4df3-b8e3-17edc0295507/artifacts/4dd3a958-db17-4a03-b489-98809a9b9df6)
