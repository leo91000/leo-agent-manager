# Conversation storage operations

The accepted product behavior is in [CONVERSATION-LIFECYCLE.md](CONVERSATION-LIFECYCLE.md). Archival is disabled by default. Deletion uses a fixed 30-day trash period independently of that setting.

## Server configuration

Install the AWS CLI and GNU tar on the manager. The manager image includes them. Configure a dedicated private S3 bucket with all four public-access blocks enabled, no Object Lock, and no enabled bucket lifecycle rules. The application validates these conditions before activation; it never creates infrastructure or changes bucket policies. Standard AWS CLI credential resolution applies (prefer a workload role). Never put credentials in application settings.

Set `ARCHIVE_S3_BUCKET` on the manager. Alternatively, mount a server-owned `DATA_DIR/archive-s3.json` containing `{"bucket":"your-private-bucket"}`. The optional `awsBinary` property selects the server's AWS CLI executable; this file must not be writable by application users or agents. Normal AWS environment settings control region and credentials. Prefer a server-only workload role; otherwise the Compose file forwards `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` from the operator's environment (for example Coolify secrets). `AWS_*` and `ARCHIVE_*` variables are removed from every process the manager starts, including agents and Git, except its own AWS CLI calls.

### S3-compatible providers

Set `ARCHIVE_S3_ENDPOINT` (or `endpoint` in `archive-s3.json`) to an `https://` endpoint, and `ARCHIVE_S3_COLD_STORAGE_CLASS` (or `coldStorageClass`) to the provider's cold tier: `GLACIER` (default, AWS) or `DEEP_ARCHIVE`. When a provider does not implement public access blocks, activation instead requires a bucket ACL without public grants and no bucket policy.

For OVHcloud, use a dedicated Public Cloud project with a bucket in the Paris 3-AZ region, the only region with Cold Archive: `ARCHIVE_S3_ENDPOINT=https://s3.eu-west-par.io.cloud.ovh.net`, `AWS_DEFAULT_REGION=eu-west-par`, `ARCHIVE_S3_COLD_STORAGE_CLASS=DEEP_ARCHIVE`, and the S3 credentials of an Object Storage user of that project. Cold Archive retrieval can take up to 48 hours and bills [at least 180 days](https://docs.ovhcloud.com/en/guides/storage-and-backup/object-storage/s3-restoring-objects). In other OVHcloud regions, `DEEP_ARCHIVE` maps to Infrequent Access.

Use the [AWS operation-to-permission mapping](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-with-s3-policy-actions.html) when preparing IAM policies. The role needs bucket inspection (HeadBucket, GetBucketPublicAccessBlock, GetLifecycleConfiguration, GetBucketObjectLockConfiguration, ListBucketVersions, ListBucketMultipartUploads) and object operations under `leo-conversations/` (GetObject, PutObject, RestoreObject, DeleteObject, DeleteObjectVersion, AbortMultipartUpload and multipart upload permissions). Keep that prefix exclusive to this application. Versioned buckets are supported; purge removes versions and delete markers, and interrupted multipart uploads. External replicas and backups are outside the application's deletion scope.

In Settings / Conversation storage, choose inactivity and warm-storage days. Defaults are 30 and 90. Activation previews the existing eligible count and asks for confirmation. The scheduler handles one conversation per pass, at most once every 15 seconds, independently of agent dispatch. Changing rules affects future eligibility and transitions. Disabling archival stops new automatic archives/transitions; restores, previously started transfers and expired-trash purges continue.

## Data and encryption

Each conversation has an immutable archive object. The bundle includes the full run record, event transcript, messages, questions, attachment metadata, checkpoint, run directory, attachments and deliverable bytes. Public deliverables retain a local warm copy and authoritative metadata. The bundle never restores public link grants.

With the Firecracker runner, the authoritative writable workspace disk is exported while holding the same disk lock as VM startup. It includes native agent sessions and the writable environment. The runner does not mount the guest filesystem. Legacy shared workspaces use the existing migration to independent run-owned clones before archival; shared project directories are never deleted. Legacy direct/worktree conversations on development servers without a configured Firecracker runner stay active until that runner is configured. A sparse compressed disk image travels through the shared, private `DATA_DIR/archive-transfers` directory. The manager encrypts the bundle before sending it to S3. This preserves a complete environment, including any sensitive files that the environment contains.

Archive frames use the existing application AES-256-GCM vault, unique nonces, archive-and-position associated data and an authenticated end marker. Keep **both the application database and `DATA_DIR/mcp-encryption-key`** in the operator's protected backups. S3 objects alone cannot be decrypted without the key. The key is never uploaded with archives. AWS server-side encryption is also requested.

Upload is followed by a complete download and SHA-256 comparison before any local deletion. Disk, files and database cleanup are retryable. A failed transfer leaves the local copy intact. Staging needs enough free disk space for compressed, encrypted and verification copies; failures remain visible on the conversation and retry after a minute. Unfinished jobs survive a manager restart. Do not move the bucket or rotate/delete the encryption key while archives exist.

## Cold storage and deletion

After the configured warm interval, the object is copied to S3 Glacier Flexible Retrieval. Noncurrent object versions are removed after a successful transition so old warm versions do not accumulate. Explicit restoration requests Standard retrieval, then polls readiness. Retrieval is asynchronous and can take hours; the temporary restored S3 copy lasts three days. Opening the archived conversation does not initiate retrieval.

A restored conversation receives a new inactivity grace period and remains paused until the user continues. Its obsolete remote archive is removed after the local restore commits. If the native agent session fails with the current engine, the user can explicitly request a fresh session using the preserved transcript and workspace.

Trash immediately revokes public links, cancels queued sends and stops active work after confirmation. Cancelled texts remain recoverable without automatic dispatch. Recovering trash restores the previous active/archived state, never public links or an agent execution. At expiry, the application removes local content and all versions under that conversation's S3 prefix; a storage failure keeps the purge pending rather than declaring success.

AWS can charge retrieval, requests and early deletion fees. S3 Glacier Flexible Retrieval has a [minimum storage duration](https://docs.aws.amazon.com/AmazonS3/latest/userguide/glacier-storage-classes.html); a user deletion can occur before it ends. Consult the current AWS pricing for the selected region. The application does not choose a cheaper deletion date at the expense of the requested trash expiry.

## Validation

Integration tests exercise the authenticated HTTP interface with a filesystem AWS CLI fixture, including archival round trips, preservation after a corrupt transfer, restart recovery, Glacier waiting, trash precedence and purge. This fixture is not an AWS service test. The authenticated runner HTTP test round-trips a synthetic sparse disk and verifies locking and log cleanup. It does not boot a VM. On a Docker host with Firecracker and KVM, run `node tests/runner-smoke.mjs IMAGE`: the first real guest is now exported, deleted and imported before the next guest verifies unpublished files, session-state files and cached Docker images. Web and Android evidence is recorded with the implementation results; JVM UI tests alone do not validate a physical device or emulator.

Primary references: [AWS CLI S3 copy](https://docs.aws.amazon.com/cli/latest/reference/s3/cp.html), [RestoreObject](https://docs.aws.amazon.com/cli/latest/reference/s3api/restore-object.html), [retrieval options](https://docs.aws.amazon.com/AmazonS3/latest/userguide/restoring-objects-retrieval-options.html).
