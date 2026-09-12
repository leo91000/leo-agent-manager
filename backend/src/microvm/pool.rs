//! One anonymous spare shares the execution slot budget. Used VMs never enter the pool.
use super::host::Vm;
use crate::{
    config::id,
    error::{Error, Result},
    skills::private_dir,
    validation::text,
};
use serde_json::{Value, json};
use std::{path::PathBuf, sync::Arc, time::Duration};
use tokio::sync::{Mutex, Notify, OnceCell};
use tokio_util::{sync::CancellationToken, task::TaskTracker};

const CAPACITY: usize = 4;
#[derive(Default)]
struct Slots {
    occupied: [bool; CAPACITY],
    spare: Option<(u8, Vm)>,
    preparation_stop: Option<CancellationToken>,
}
pub struct Reservation {
    pool: Arc<Pool>,
    released: bool,
    slot: u8,
    spare: Option<Vm>,
}
pub struct Pool {
    state: PathBuf,
    image: PathBuf,
    slots: Mutex<Slots>,
    changed: Notify,
    stop: CancellationToken,
    cleanup: TaskTracker,
}
impl Pool {
    pub async fn new(state: PathBuf, image: PathBuf, stop: CancellationToken) -> Result<Arc<Self>> {
        // The controller lock and PID namespace fence all previous owners before this cleanup.
        let prepared = state.join("prepared");
        if prepared.exists() {
            tokio::fs::remove_dir_all(&prepared).await?;
        }
        private_dir(&prepared).await?;
        private_dir(&state.join("disks")).await?;
        Ok(Arc::new(Self {
            state,
            image,
            slots: Mutex::new(Slots::default()),
            changed: Notify::new(),
            stop,
            cleanup: TaskTracker::new(),
        }))
    }
    pub async fn health(&self) -> Value {
        let slots = self.slots.lock().await;
        json!({"capacity":CAPACITY,"occupied":slots.occupied.iter().filter(|v| **v).count(),"ready":usize::from(slots.spare.is_some()),"preparing":slots.preparation_stop.is_some()})
    }
    pub async fn reserve(self: &Arc<Self>, run_id: &str) -> Result<Reservation> {
        loop {
            // Register before checking to avoid a completed preparation being missed.
            let changed = self.changed.notified();
            tokio::pin!(changed);
            changed.as_mut().enable();
            {
                let mut slots = self.slots.lock().await;
                if self.stop.is_cancelled() {
                    return Err(Error::new(503, "VM controller is stopping."));
                }
                let free = slots.occupied.iter().position(|v| !v);
                let preserve_spare =
                    free.is_some() && self.state.join("disks").join(run_id).exists();
                if !preserve_spare && let Some((slot, vm)) = slots.spare.take() {
                    return Ok(Reservation {
                        pool: self.clone(),
                        released: false,
                        slot,
                        spare: Some(vm),
                    });
                }
                if let Some(index) = slots.occupied.iter().position(|v| !v) {
                    slots.occupied[index] = true;
                    return Ok(Reservation {
                        pool: self.clone(),
                        released: false,
                        slot: index as u8 + 1,
                        spare: None,
                    });
                }
                if let Some(stop) = &slots.preparation_stop {
                    stop.cancel();
                }
                if slots.preparation_stop.is_none() {
                    return Err(Error::new(503, "All VM slots are occupied."));
                }
            }
            tokio::select! { _ = changed => {}, _ = self.stop.cancelled() => return Err(Error::new(503,"VM controller is stopping.")) }
        }
    }
    pub async fn drain(&self) {
        self.cleanup.close();
        self.cleanup.wait().await;
    }
    pub async fn maintain(&self) {
        let mut retry_at = tokio::time::Instant::now();
        loop {
            if self.stop.is_cancelled() {
                break;
            }
            let slot = {
                let mut slots = self.slots.lock().await;
                if slots.spare.is_some() || tokio::time::Instant::now() < retry_at {
                    None
                } else {
                    slots.occupied.iter().position(|v| !v).map(|index| {
                        slots.occupied[index] = true;
                        let cancel = self.stop.child_token();
                        slots.preparation_stop = Some(cancel.clone());
                        (index as u8 + 1, cancel)
                    })
                }
            };
            if let Some((slot, prepare_stop)) = slot {
                let disk = self.state.join("prepared").join(id());
                let mut vm =
                    Vm::boot(&self.state, &self.image, disk.clone(), slot, &prepare_stop).await;
                if let Ok(prepared) = &mut vm {
                    let warm = tokio::select! { result = prepared.warm() => result, _ = prepare_stop.cancelled() => Err(Error::new(503,"VM preparation stopped.")) };
                    if let Err(error) = warm {
                        prepared.shutdown().await;
                        vm = Err(error);
                    }
                }
                let mut slots = self.slots.lock().await;
                slots.preparation_stop = None;
                match vm {
                    Ok(vm) => slots.spare = Some((slot, vm)),
                    Err(error) => {
                        retry_at = tokio::time::Instant::now() + Duration::from_secs(30);
                        tracing::warn!(message=%error.message,"VM spare preparation failed; cold boot remains available");
                        slots.occupied[usize::from(slot - 1)] = false;
                        drop(slots);
                        let _ = tokio::fs::remove_dir_all(disk).await;
                    }
                }
                self.changed.notify_waiters();
            }
            tokio::select! { _ = self.stop.cancelled() => break, _ = tokio::time::sleep(Duration::from_secs(1)) => {} }
        }
        let spare = self.slots.lock().await.spare.take();
        if let Some((slot, mut vm)) = spare {
            vm.shutdown().await;
            vm.discard_prepared().await;
            self.slots.lock().await.occupied[usize::from(slot - 1)] = false;
        }
        self.changed.notify_waiters();
    }
}

impl Reservation {
    pub async fn execute(
        mut self,
        plan: Value,
        socket: Arc<OnceCell<PathBuf>>,
        stop: CancellationToken,
    ) -> Result<i32> {
        let disk = self.pool.state.join("disks").join(text(&plan, "runId"));
        if stop.is_cancelled() {
            self.finish().await;
            return Ok(143);
        }
        let operation = async {
            if disk.exists()
                && let Some(mut vm) = self.spare.take()
            {
                // Existing disks are authoritative; a prepared filesystem must never replace them.
                vm.shutdown().await;
                vm.discard_prepared().await;
            }
            // The spare is only a cache. A dead VMM must not fail a new conversation.
            // Retry is safe here: no account was bound and no user command was sent.
            if let Some(vm) = &mut self.spare
                && let Err(error) = vm.activate().await
            {
                tracing::warn!(message=%error.message,"Prepared VM unavailable; using cold boot");
                vm.shutdown().await;
                vm.discard_prepared().await;
                self.spare = None;
            }
            if let Some(vm) = &mut self.spare {
                vm.adopt(&self.pool.state, text(&plan, "runId")).await?;
            } else {
                self.spare = Some(
                    Vm::boot(&self.pool.state, &self.pool.image, disk, self.slot, &stop).await?,
                );
            }
            let vm = self.spare.as_mut().unwrap();
            let _ = socket.set(vm.socket.clone());
            vm.execute(&plan, &self.pool.state, stop.clone()).await
        };
        // Do not drop boot or cleanup futures on cancellation: their resource ownership must drain.
        let result = operation.await;
        self.finish().await;
        result
    }
    async fn finish(&mut self) {
        if let Some(mut vm) = self.spare.take() {
            vm.shutdown().await;
            vm.discard_prepared().await;
        }
        self.pool.slots.lock().await.occupied[usize::from(self.slot - 1)] = false;
        self.released = true;
        self.pool.changed.notify_waiters();
    }
}
impl Drop for Reservation {
    fn drop(&mut self) {
        if self.released {
            return;
        }
        let pool = self.pool.clone();
        let mut vm = self.spare.take();
        let slot = self.slot;
        self.pool.cleanup.spawn(async move {
            if let Some(vm) = &mut vm {
                vm.shutdown().await;
                vm.discard_prepared().await;
            }
            pool.slots.lock().await.occupied[usize::from(slot - 1)] = false;
            pool.changed.notify_waiters();
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn abandoned_reservations_release_capacity_and_shutdown_rejects_work() {
        let root = tempfile::tempdir().unwrap();
        let stop = CancellationToken::new();
        let pool = Pool::new(root.path().into(), root.path().into(), stop.clone())
            .await
            .unwrap();
        let mut reservations = Vec::new();
        for _ in 0..CAPACITY {
            reservations.push(pool.reserve("run").await.unwrap());
        }
        assert_eq!(pool.health().await["occupied"], 4);
        assert!(pool.reserve("run").await.is_err());
        drop(reservations.pop());
        tokio::time::timeout(Duration::from_secs(1), async {
            while pool.health().await["occupied"] == 4 {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        reservations.push(pool.reserve("run").await.unwrap());
        assert_eq!(pool.health().await["occupied"], 4);
        stop.cancel();
        assert!(pool.reserve("run").await.is_err());
        drop(reservations);
        pool.drain().await;
        assert_eq!(pool.health().await["occupied"], 0);
    }
    #[tokio::test]
    async fn restart_removes_only_unassigned_disks() {
        let root = tempfile::tempdir().unwrap();
        for directory in ["prepared/orphan", "disks/conversation"] {
            tokio::fs::create_dir_all(root.path().join(directory))
                .await
                .unwrap();
            tokio::fs::write(root.path().join(directory).join("sentinel"), b"saved")
                .await
                .unwrap();
        }
        let pool = Pool::new(
            root.path().into(),
            root.path().into(),
            CancellationToken::new(),
        )
        .await
        .unwrap();
        assert!(!root.path().join("prepared/orphan").exists());
        assert_eq!(
            tokio::fs::read(root.path().join("disks/conversation/sentinel"))
                .await
                .unwrap(),
            b"saved"
        );
        assert_eq!(pool.health().await["occupied"], 0);
    }
}
