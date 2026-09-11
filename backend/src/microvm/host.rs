//! Trusted host controller. Only the guest interprets its writable filesystem.
use super::wire;
use crate::{
    error::{Error, Result},
    skills::{atomic_write, private_dir},
    validation::text,
};
use base64::{Engine, engine::general_purpose::STANDARD};
use serde_json::{Value, json};
use std::{
    os::fd::AsRawFd,
    path::{Path, PathBuf},
    process::Stdio,
    time::Duration,
};
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt, BufReader},
    net::{UnixListener, UnixStream},
    process::Command,
};
use tokio_util::sync::CancellationToken;

pub async fn command(binary: &str, args: &[&str]) -> Result<()> {
    let result = Command::new(binary)
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::piped())
        .kill_on_drop(true)
        .output()
        .await?;
    if !result.status.success() {
        tracing::warn!(binary, detail=%String::from_utf8_lossy(&result.stderr), "VM infrastructure operation failed");
        return Err(Error::new(
            503,
            format!("VM infrastructure operation failed: {binary}"),
        ));
    }
    Ok(())
}

pub async fn assets(state: &Path) -> Result<PathBuf> {
    if !Path::new("/dev/kvm").exists() {
        return Err(Error::new(503, "Firecracker requires /dev/kvm."));
    }
    let version = std::env::var("APP_RUNTIME_ID").unwrap_or_else(|_| "development".into());
    if !version
        .bytes()
        .all(|c| c.is_ascii_alphanumeric() || c == b'-')
    {
        return Err(Error::bad("Invalid VM image version."));
    }
    // The exclusive controller lock is already held and its previous container's
    // PID namespace is gone. Remove stale jail hard links before old image caches.
    if state.join("jails").exists() {
        tokio::fs::remove_dir_all(state.join("jails")).await?;
    }
    private_dir(&state.join("images")).await?;
    let mut images = tokio::fs::read_dir(state.join("images")).await?;
    while let Some(image) = images.next_entry().await? {
        if image.file_name() != version.as_str() {
            tokio::fs::remove_dir_all(image.path()).await?;
        }
    }
    let target = state.join("images").join(version);
    private_dir(&target).await?;
    let image = target.join("root.ext4");
    if !image.exists() {
        let temporary = target.join("root.ext4.partial");
        command(
            "zstd",
            &[
                "-d",
                "-f",
                "/opt/leo-vm/root.ext4.zst",
                "-o",
                temporary.to_str().unwrap(),
            ],
        )
        .await?;
        tokio::fs::rename(temporary, &image).await?;
    }
    use std::os::unix::fs::PermissionsExt;
    tokio::fs::set_permissions(&image, std::fs::Permissions::from_mode(0o444)).await?;
    Ok(target)
}

async fn connect(socket: &Path) -> Result<BufReader<UnixStream>> {
    let mut stream = UnixStream::connect(socket).await?;
    stream
        .write_all(format!("CONNECT {}\n", wire::PORT).as_bytes())
        .await?;
    let mut stream = BufReader::new(stream);
    let mut answer = String::new();
    stream.read_line(&mut answer).await?;
    if !answer.starts_with("OK ") {
        return Err(Error::new(503, "Guest connection is not ready."));
    }
    Ok(stream)
}

async fn request(socket: &Path, request: &Value) -> Result<Value> {
    let mut stream = connect(socket).await?;
    wire::write(stream.get_mut(), request).await?;
    wire::read(&mut stream)
        .await?
        .ok_or_else(|| Error::new(503, "Guest disconnected."))
}

fn console(
    mut stream: impl tokio::io::AsyncRead + Unpin + Send + 'static,
    path: PathBuf,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        use tokio::io::AsyncReadExt;
        if let Ok(mut log) = tokio::fs::File::create(path).await {
            let _ = tokio::io::copy(&mut (&mut stream).take(4 * 1024 * 1024), &mut log).await;
        }
        // Drain excess guest console output without allowing it to fill host storage.
        let _ = tokio::io::copy(&mut stream, &mut tokio::io::sink()).await;
    })
}

async fn import(socket: &Path, source: &Path, target: &str) -> Result<()> {
    let mut stream = connect(socket).await?;
    let empty = tokio::fs::read_dir(source)
        .await?
        .next_entry()
        .await?
        .is_none();
    wire::write(
        stream.get_mut(),
        &json!({"op":"import","target":target,"replace":empty}),
    )
    .await?;
    let mut tar = Command::new("tar");
    tar.args(["--exclude=leo-auth.sock", "--exclude=*.sock"]);
    if target == "/home/node" {
        tar.arg("--exclude=./.codex/auth.json");
    }
    tar.arg("-C");
    tar.arg(source).args(["-cf", "-", "."]);
    let mut child = tar
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .kill_on_drop(true)
        .spawn()?;
    use tokio::io::AsyncReadExt;
    let mut archive = child.stdout.take().unwrap();
    let mut buffer = vec![0; 65536];
    loop {
        let count = archive.read(&mut buffer).await?;
        if count == 0 {
            break;
        }
        wire::write(
            stream.get_mut(),
            &json!({"type":"chunk","data":STANDARD.encode(&buffer[..count])}),
        )
        .await?;
    }
    wire::write(stream.get_mut(), &json!({"type":"end"})).await?;
    let code = child.wait().await?.code();
    if !matches!(code, Some(0 | 1)) {
        return Err(Error::bad("Workspace import failed."));
    }
    let result = wire::read(&mut stream)
        .await?
        .ok_or_else(|| Error::bad("Guest import disconnected."))?;
    if result["ok"] != true {
        return Err(Error::bad("Guest import failed."));
    }
    Ok(())
}

struct Network {
    tap: String,
    chain: String,
    guest: String,
    gateway: String,
}
impl Network {
    fn new(slot: u8) -> Self {
        Self {
            tap: format!("leo{slot}"),
            chain: format!("LEO{slot}"),
            guest: format!("10.231.{slot}.2"),
            gateway: format!("10.231.{slot}.1"),
        }
    }
    async fn create(&self, uid: u32) -> Result<()> {
        command(
            "ip",
            &[
                "tuntap",
                "add",
                "dev",
                &self.tap,
                "mode",
                "tap",
                "user",
                &uid.to_string(),
            ],
        )
        .await?;
        command(
            "ip",
            &[
                "addr",
                "add",
                &format!("{}/30", self.gateway),
                "dev",
                &self.tap,
            ],
        )
        .await?;
        command("ip", &["link", "set", &self.tap, "up"]).await?;
        command("iptables", &["-w", "-N", &self.chain]).await?;
        // No VM can contact the runner, a peer VM, LAN, or cloud metadata.
        command(
            "iptables",
            &["-w", "-I", "INPUT", "-i", &self.tap, "-j", "DROP"],
        )
        .await?;
        command(
            "iptables",
            &["-w", "-I", "FORWARD", "-i", &self.tap, "-j", &self.chain],
        )
        .await?;
        command(
            "iptables",
            &[
                "-w",
                "-A",
                &self.chain,
                "!",
                "-s",
                &self.guest,
                "-j",
                "DROP",
            ],
        )
        .await?;
        for subnet in [
            "0.0.0.0/8",
            "10.0.0.0/8",
            "100.64.0.0/10",
            "127.0.0.0/8",
            "169.254.0.0/16",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "224.0.0.0/3",
        ] {
            command(
                "iptables",
                &["-w", "-A", &self.chain, "-d", subnet, "-j", "DROP"],
            )
            .await?;
        }
        // Internet web traffic is enough for APIs, package registries and Git HTTPS.
        for (protocol, ports) in [("tcp", "80,443"), ("udp", "53")] {
            command(
                "iptables",
                &[
                    "-w",
                    "-A",
                    &self.chain,
                    "-p",
                    protocol,
                    "-m",
                    "multiport",
                    "--dports",
                    ports,
                    "-j",
                    "ACCEPT",
                ],
            )
            .await?;
        }
        command("iptables", &["-w", "-A", &self.chain, "-j", "DROP"]).await?;
        command(
            "iptables",
            &[
                "-w",
                "-I",
                "FORWARD",
                "-o",
                &self.tap,
                "-m",
                "conntrack",
                "--ctstate",
                "ESTABLISHED,RELATED",
                "-j",
                "ACCEPT",
            ],
        )
        .await?;
        command(
            "iptables",
            &[
                "-w",
                "-t",
                "nat",
                "-A",
                "POSTROUTING",
                "-s",
                &self.guest,
                "-j",
                "MASQUERADE",
            ],
        )
        .await
    }
    async fn remove(&self) {
        for args in [
            vec!["-w", "-D", "INPUT", "-i", &self.tap, "-j", "DROP"],
            vec!["-w", "-D", "FORWARD", "-i", &self.tap, "-j", &self.chain],
            vec![
                "-w",
                "-D",
                "FORWARD",
                "-o",
                &self.tap,
                "-m",
                "conntrack",
                "--ctstate",
                "ESTABLISHED,RELATED",
                "-j",
                "ACCEPT",
            ],
            vec![
                "-w",
                "-t",
                "nat",
                "-D",
                "POSTROUTING",
                "-s",
                &self.guest,
                "-j",
                "MASQUERADE",
            ],
            vec!["-w", "-F", &self.chain],
            vec!["-w", "-X", &self.chain],
        ] {
            let _ = Command::new("iptables")
                .args(args)
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .status()
                .await;
        }
        let _ = Command::new("ip")
            .args(["link", "del", &self.tap])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .await;
    }
}

pub async fn execute(
    plan: Value,
    state: PathBuf,
    image: PathBuf,
    slot: u8,
    stop: CancellationToken,
) -> Result<i32> {
    let id = text(&plan, "id");
    let disk_dir = state.join("disks").join(text(&plan, "runId"));
    private_dir(&disk_dir).await?;
    let lock = std::fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(disk_dir.join("lock"))?;
    if unsafe { libc::flock(lock.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0 {
        return Err(Error::new(
            409,
            "The previous VM still owns this workspace.",
        ));
    }
    let disk = disk_dir.join("data.ext4");
    if !disk.exists() {
        let file = tokio::fs::File::create(disk_dir.join("data.partial")).await?;
        file.set_len(32 * 1024 * 1024 * 1024).await?;
        drop(file);
        command(
            "mkfs.ext4",
            &["-q", "-F", disk_dir.join("data.partial").to_str().unwrap()],
        )
        .await?;
        tokio::fs::rename(disk_dir.join("data.partial"), &disk).await?;
    }
    let uid = 40000 + u32::from(slot);
    let jail = state.join("jails/firecracker").join(id).join("root");
    private_dir(&jail).await?;
    std::os::unix::fs::chown(&disk, Some(uid), Some(uid))?;
    tokio::fs::hard_link(&disk, jail.join("data.ext4")).await?;
    tokio::fs::hard_link(image.join("root.ext4"), jail.join("root.ext4")).await?;
    tokio::fs::copy("/opt/leo-vm/vmlinux", jail.join("vmlinux")).await?;
    let network = Network::new(slot);
    let socket = jail.join("v.sock");
    let auth_listener = UnixListener::bind(jail.join("v.sock_5201"))?;
    std::os::unix::fs::chown(jail.join("v.sock_5201"), Some(uid), Some(uid))?;
    let auth_path = plan["imports"]
        .as_array()
        .into_iter()
        .flatten()
        .find(|m| m["target"] == "/home/node")
        .map(|m| Path::new(text(m, "source")).join(".codex/leo-auth.sock"));
    let relay_stop = CancellationToken::new();
    let relay_cancel = relay_stop.clone();
    let relay = tokio::spawn(async move {
        loop {
            tokio::select! {
                _ = relay_cancel.cancelled() => break,
                accepted = auth_listener.accept() => {
                    let Ok((mut guest,_))=accepted else {break};
                    if let Some(path)=&auth_path {
                        // Bound concurrency and lifetime; only this run's manager socket is reachable.
                        if let Ok(mut manager)=UnixStream::connect(path).await {
                            let _=tokio::time::timeout(Duration::from_secs(15),tokio::io::copy_bidirectional(&mut guest,&mut manager)).await;
                        }
                    }
                }
            }
        }
    });
    let mut child = None;
    let mut consoles = Vec::new();
    let result = async {
        network.create(uid).await?;
        let config = json!({
            "boot-source":{"kernel_image_path":"vmlinux","boot_args":format!("console=ttyS0 reboot=k panic=1 pci=off root=/dev/vda ro init=/sbin/leo-init ip={}::{}:255.255.255.252:leo:eth0:off",network.guest,network.gateway)},
            "drives":[{"drive_id":"root","path_on_host":"root.ext4","is_root_device":true,"is_read_only":true},{"drive_id":"data","path_on_host":"data.ext4","is_root_device":false,"is_read_only":false}],
            "machine-config":{"vcpu_count":2,"mem_size_mib":4096,"smt":false},
            "network-interfaces":[{"iface_id":"net","host_dev_name":network.tap,"guest_mac":format!("06:00:ac:10:{slot:02x}:02")}],
            "vsock":{"guest_cid":u32::from(slot)+3,"uds_path":"v.sock"}
        });
        atomic_write(&jail.join("config.json"), &serde_json::to_vec(&config)?).await?;
        std::os::unix::fs::chown(jail.join("config.json"), Some(uid), Some(uid))?;
        child = Some(
            Command::new("/usr/local/bin/jailer")
                .args([
                    "--id",
                    id,
                    "--exec-file",
                    "/usr/local/bin/firecracker",
                    "--uid",
                    &uid.to_string(),
                    "--gid",
                    &uid.to_string(),
                    "--cgroup-version",
                    "2",
                    "--chroot-base-dir",
                    state.join("jails").to_str().unwrap(),
                    "--resource-limit",
                    "fsize=34359738368",
                    "--resource-limit",
                    "no-file=256",
                    "--",
                    "--no-api",
                    "--config-file",
                    "config.json",
                ])
                .stdin(Stdio::null())
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .kill_on_drop(true)
                .spawn()?,
        );
        consoles.push(console(
            child.as_mut().unwrap().stdout.take().unwrap(),
            state.join(format!("{id}.boot.log")),
        ));
        consoles.push(console(
            child.as_mut().unwrap().stderr.take().unwrap(),
            state.join(format!("{id}.vmm.log")),
        ));
        let deadline = tokio::time::Instant::now() + Duration::from_secs(60);
        let status = loop {
            if child.as_mut().unwrap().try_wait()?.is_some() {
                return Err(Error::new(
                    503,
                    "Firecracker exited before the guest was ready. Check the VM boot log.",
                ));
            }
            if let Ok(Ok(status)) = tokio::time::timeout(
                Duration::from_secs(1),
                request(&socket, &json!({"op":"status"})),
            )
            .await
            {
                break status;
            }
            if tokio::time::Instant::now() > deadline {
                return Err(Error::new(503, "Guest startup timed out."));
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        };
        if status["version"] != 1 {
            return Err(Error::new(503, "Unsupported guest protocol."));
        }
        if status["initialized"] != true {
            let mut imported = Vec::<(&Path, &str)>::new();
            for mount in plan["imports"].as_array().into_iter().flatten() {
                let source = Path::new(text(mount, "source"));
                let target = text(mount, "target");
                // The workspace root already includes its projects. Separate
                // entries still carry their read-only policy, but need no second archive.
                if !imported.iter().any(|(parent, destination)| {
                    source.strip_prefix(parent).is_ok_and(|relative| {
                        Path::new(destination).join(relative) == Path::new(target)
                    })
                }) {
                    import(&socket, source, target).await?;
                    imported.push((source, target));
                }
            }
        } else {
            // Credentials and the volatile inbox are refreshed, never the saved workspaces.
            for mount in plan["imports"].as_array().into_iter().flatten() {
                if mount["target"] == "/run/leo-chat" {
                    import(&socket, Path::new(text(mount, "source")), "/run/leo-chat").await?;
                }
                if mount["target"] == "/home/node" {
                    let source = Path::new(text(mount, "source")).join(".config/gh");
                    if source.exists() {
                        import(&socket, &source, "/home/node/.config/gh").await?;
                    }
                }
            }
        }
        let mut stream = connect(&socket).await?;
        wire::write(stream.get_mut(), &json!({"op":"run","plan":plan})).await?;
        let mut timer = tokio::time::interval(Duration::from_millis(500));
        let inbox = plan["imports"]
            .as_array()
            .into_iter()
            .flatten()
            .find(|m| m["target"] == "/run/leo-chat")
            .map(|m| PathBuf::from(text(m, "source")));
        let mut last_inbox = Vec::new();
        let mut logs = tokio::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(state.join(format!("{id}.log")))
            .await?;
        let mut total = 0usize;
        loop {
            // Keep the read future alive across inbox ticks: dropping it halfway
            // through a fragmented frame would discard already-consumed bytes.
            let next = wire::read(&mut stream);
            tokio::pin!(next);
            let event = loop {
                tokio::select! {
                    event=&mut next=>break event,
                    _=timer.tick()=>{
                        if let Some(inbox)=&inbox {
                            let content=tokio::fs::read(inbox.join("messages.json")).await.unwrap_or_default();
                            if content!=last_inbox {
                                import(&socket,inbox,"/run/leo-chat").await?;
                                last_inbox=content;
                            }
                        }
                    }
                }
            };
            let event = event?.ok_or_else(|| {
                Error::new(
                    503,
                    "Guest disconnected. Its workspace disk has been preserved.",
                )
            })?;
            match text(&event, "type") {
                "output" => {
                    let bytes = STANDARD
                        .decode(text(&event, "data"))
                        .map_err(|_| Error::bad("Invalid guest output."))?;
                    total += bytes.len();
                    if total > 100_000_000 {
                        return Err(Error::bad("Guest output exceeded the run limit."));
                    }
                    wire::write(&mut logs, &event).await?;
                }
                "exit" => {
                    let code = event["code"]
                        .as_i64()
                        .filter(|n| (0..=255).contains(n))
                        .ok_or_else(|| Error::bad("Invalid guest exit status."))?;
                    let output = text(&plan["chat"], "output");
                    if !output.is_empty() {
                        atomic_write(Path::new(output), text(&event, "result").as_bytes()).await?;
                        std::os::unix::fs::chown(output, Some(1000), Some(1000))?;
                    }
                    return Ok(code as i32);
                }
                _ => return Err(Error::bad("Unknown guest event.")),
            }
        }
    };
    let result = tokio::select! {result=result=>result,_=stop.cancelled()=>Ok(143)};
    let _ = tokio::time::timeout(
        Duration::from_secs(2),
        request(&socket, &json!({"op":"shutdown"})),
    )
    .await;
    if let Some(mut child) = child
        && tokio::time::timeout(Duration::from_secs(8), child.wait())
            .await
            .is_err()
    {
        let _ = child.kill().await;
        let _ = child.wait().await;
    }
    relay_stop.cancel();
    for console in consoles {
        let _ = console.await;
    }
    relay.abort();
    let _ = relay.await;
    network.remove().await;
    let _ = tokio::fs::remove_dir_all(jail.parent().unwrap()).await;
    drop(lock);
    result
}
